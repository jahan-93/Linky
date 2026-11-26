package dev.linky.phone

import android.graphics.PointF
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Surface
import dev.linky.protocol.FrameWriter
import dev.linky.protocol.Msg
import dev.linky.protocol.VideoConfig
import dev.linky.protocol.VideoFrame
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * H.264 하드웨어 인코더 하나 (설계서 2.1). 세션 시작, 회전, 화질 변경 때마다 새로 만든다.
 * 시작한 뒤에는 꺼내기 스레드만 코덱을 다룬다. 해제도 그 스레드가 해서 스레드끼리 경합하지 않는다.
 */
class VideoEncoder private constructor(
    private val codec: MediaCodec,
    val surface: Surface,
    val width: Int,
    val height: Int,
    /** 이 인코더를 만든 긴 변 한도 (실패해서 크기를 줄여 다시 시도할 때 쓴다) */
    val limit: Int,
    private val sink: Connection,
    /** 코덱 오류. early = 영상을 한 장도 내기 전에 실패함 */
    private val onFailed: (encoder: VideoEncoder, early: Boolean) -> Unit,
) {
    @Volatile private var running = true
    private val keyframe = AtomicBoolean(false)
    private var drainer: Thread? = null

    // ---- 지연 관리 (꺼내기 스레드 전용) ----
    private var clockChecked = false
    private var skipping = false
    /** 마지막 키프레임을 다 보낸 시각(µs, System.nanoTime 기준) */
    private var lastKeyDoneUs = 0L
    private var keyRequestedAt = 0L
    private var dropped = 0
    private var lastDropLog = 0L

    fun start() {
        drainer = thread(name = "linky-encoder", isDaemon = true) { drain() }
    }

    /** 다음 꺼내기 때 키프레임을 요청한다 (어느 스레드에서 불러도 됨). */
    fun requestKeyframe() = keyframe.set(true)

    /**
     * 영상 기준 좌표(0~1)를 화면(screenW x screenH) 기준 좌표(0~1)로 바꾼다 (설계서 3장).
     * 영상 크기를 16의 배수로 내리면 비율이 조금 달라져, 화면이 영상 가운데에 맞춰 들어가고 가장자리에 여백이 생긴다.
     * 회전 직후 인코더를 새로 만들기 전에도 화면은 지금 영상 크기 안에 같은 방식으로 들어가므로 지금 화면 크기를 넘긴다.
     */
    fun toScreen(nx: Float, ny: Float, screenW: Int, screenH: Int): PointF {
        val sw = maxOf(1, screenW).toFloat()
        val sh = maxOf(1, screenH).toFloat()
        val scale = minOf(width / sw, height / sh)
        val cw = sw * scale
        val ch = sh * scale
        val ox = (width - cw) / 2
        val oy = (height - ch) / 2
        return PointF(((nx * width - ox) / cw).coerceIn(0f, 1f), ((ny * height - oy) / ch).coerceIn(0f, 1f))
    }

    /** 멈춘다. 꺼내기 스레드가 코덱을 해제하도록 잠깐 기다린다. */
    fun stop() {
        running = false
        val t = drainer
        if (t == null) {
            release()
            return
        }
        try {
            t.join(STOP_WAIT_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun drain() {
        val info = MediaCodec.BufferInfo()
        var buf = ByteArray(INITIAL_BUFFER)
        // 보내기가 실패하면(연결 끊김) 세션이 정리될 때까지 꺼내기만 계속한다 (화면이 버려진 surface에 그리지 않게)
        var sinkBroken = false
        var gotFrame = false
        try {
            while (running) {
                if (keyframe.getAndSet(false)) {
                    runCatching {
                        codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
                    }
                }
                val index = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                if (index < 0) continue // 시간 초과, 형식 변경

                // 꺼낸 데이터는 재사용 배열에 복사하고, 보내기 전에 버퍼부터 돌려준다
                var size = 0
                val out = if (info.size > 0) codec.getOutputBuffer(index) else null
                if (out != null) {
                    size = info.size
                    if (buf.size < size) buf = ByteArray(size + size / 2)
                    out.position(info.offset)
                    out.get(buf, 0, size)
                }
                val flags = info.flags
                val pts = info.presentationTimeUs
                codec.releaseOutputBuffer(index, false)
                if (size == 0 || !running || sinkBroken) continue

                val sent = if ((flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    val config = VideoConfig(width, height, buf.copyOf(size)).encode()
                    sink.sendVideo(Msg.VIDEO_CONFIG, FrameWriter.EMPTY, config, config.size)
                } else {
                    gotFrame = true
                    val key = (flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                    if (shouldDrop(pts, key)) continue
                    sink.sendVideo(Msg.VIDEO_FRAME, VideoFrame.header(pts, key), buf, size)
                        .also {
                            if (it) AppState.framesSent.incrementAndGet()
                            if (it && key) lastKeyDoneUs = System.nanoTime() / 1000
                        }
                }
                if (!sent) sinkBroken = true
            }
        } catch (e: Exception) {
            // 코덱 오류: 영상이 멈춘 채로 두지 않는다 (처음부터 실패했으면 더 작은 크기로 다시 시도)
            if (running) {
                AppState.log("영상 인코더 오류: ${e.message ?: e.javaClass.simpleName}")
                onFailed(this, !gotFrame)
            }
        } finally {
            release()
        }
    }

    /**
     * 지연이 쌓이지 않게 (설계서 2.1): 이 장을 버려야 하면 true.
     * 지연 = 지금 − 캡처 시각. 화면(VirtualDisplay)의 시각은 System.nanoTime과 같은 CLOCK_MONOTONIC이다.
     * 값이 터무니없으면(음수, 10초 넘음) 기준이 다른 기기로 보고 버리지 않는다.
     * 쓰기가 막혀 있던 시간도 다음 장의 지연으로 잡힌다.
     * 150ms를 넘으면 키프레임이 아닌 장은 버리고 키프레임을 요청한 뒤, 다음 키프레임부터 다시 보낸다.
     */
    private fun shouldDrop(ptsUs: Long, key: Boolean): Boolean {
        val lagUs = System.nanoTime() / 1000 - ptsUs
        val sane = lagUs in 0..MAX_SANE_LAG_US
        if (!clockChecked) {
            clockChecked = true
            if (!sane) AppState.log("이 폰은 영상 시각 기준이 달라 지연을 잴 수 없습니다 (건너뛰기 꺼짐)")
        }
        if (key) {
            if (skipping) {
                skipping = false
                reportDropped()
            }
            return false
        }
        // 큰 키프레임을 보내는 동안 쌓인 장들은 조금 늦을 수밖에 없다. 여기서 또 건너뛰면 키프레임 요청이 반복되므로 한도를 넉넉히 둔다
        val limit = if (ptsUs < lastKeyDoneUs) MAX_KEY_BACKLOG_US else MAX_LAG_US
        if (!skipping && !(sane && lagUs > limit)) return false
        // 키프레임을 요청한다. 오지 않으면 1초마다 다시 요청한다
        val now = SystemClock.uptimeMillis()
        if (!skipping || now - keyRequestedAt >= KEY_RETRY_MS) {
            keyRequestedAt = now
            keyframe.set(true)
        }
        skipping = true
        dropped++
        return true
    }

    /** 건너뛴 장 수를 로그에 남긴다 (5초에 한 번까지). */
    private fun reportDropped() {
        val now = SystemClock.uptimeMillis()
        if (now - lastDropLog < DROP_LOG_GAP_MS) return
        AppState.log("Wi-Fi가 느려 영상 ${dropped}장을 건너뛰고 키프레임부터 다시 보냈습니다")
        dropped = 0
        lastDropLog = now
    }

    private fun release() {
        running = false
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { surface.release() }
    }

    companion object {
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val FPS = 60
        private const val DEQUEUE_TIMEOUT_US = 100_000L
        private const val STOP_WAIT_MS = 1000L
        private const val INITIAL_BUFFER = 256 * 1024
        private const val MAX_LAG_US = 150_000L
        private const val MAX_KEY_BACKLOG_US = 1_000_000L
        /** 이보다 크거나 음수면 시각 기준이 다른 것으로 본다 (막힌 쓰기는 5초 안에 끊기므로 정상이면 넘지 않는다) */
        private const val MAX_SANE_LAG_US = 10_000_000L
        private const val KEY_RETRY_MS = 1000L
        private const val DROP_LOG_GAP_MS = 5000L
        /** 설정이 실패하면 차례로 시도하는 더 작은 긴 변 한도 */
        private val FALLBACKS = listOf(1920, 1280)

        /** 화면을 긴 변 maxSize 이하로 맞출 때의 한도. maxSize <= 0 이면 원본. */
        fun limitFor(screenW: Int, screenH: Int, maxSize: Int): Int {
            val longSide = maxOf(screenW, screenH)
            return if (maxSize <= 0) longSide else minOf(maxSize, longSide)
        }

        /** limit보다 작은 다음 시도 한도. 없으면 null. */
        fun smallerLimit(limit: Int): Int? = FALLBACKS.firstOrNull { it < limit }

        /**
         * 화면(screenW x screenH)을 긴 변 limit 이하로 맞춘 인코더를 만든다.
         * 인코더가 지원하지 않는 크기이거나 설정이 실패하면 더 작은 크기(1920 → 1280)로 다시 시도한다. 모두 실패하면 null.
         */
        fun create(
            screenW: Int,
            screenH: Int,
            limit: Int,
            bitrate: Int,
            sink: Connection,
            onFailed: (encoder: VideoEncoder, early: Boolean) -> Unit,
        ): VideoEncoder? {
            for (l in listOf(limit) + FALLBACKS.filter { it < limit }) {
                val (w, h) = fit(screenW, screenH, l)
                var codec: MediaCodec? = null
                var surface: Surface? = null
                try {
                    codec = MediaCodec.createEncoderByType(MIME)
                    val caps = codec.codecInfo.getCapabilitiesForType(MIME).videoCapabilities
                    // 세로 크기를 가로로만 적어둔 인코더도 있어 돌린 크기도 받아준다
                    require(caps == null || caps.isSizeSupported(w, h) || caps.isSizeSupported(h, w)) { "지원하지 않는 크기" }
                    codec.configure(format(w, h, bitrate), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    surface = codec.createInputSurface()
                    codec.start()
                    return VideoEncoder(codec, surface, w, h, l, sink, onFailed)
                } catch (e: Exception) {
                    AppState.log("인코더 설정 실패 (${w}x$h): ${e.message ?: e.javaClass.simpleName}")
                    runCatching { surface?.release() }
                    runCatching { codec?.release() }
                }
            }
            return null
        }

        /** 비율을 지키며 긴 변을 limit 이하로 줄이고, 가로·세로를 16의 배수로 내린다. */
        fun fit(w: Int, h: Int, limit: Int): Pair<Int, Int> {
            val scale = minOf(1.0, limit.toDouble() / maxOf(w, h, 1))
            return maxOf(16, (w * scale).toInt() / 16 * 16) to maxOf(16, (h * scale).toInt() / 16 * 16)
        }

        private fun format(w: Int, h: Int, bitrate: Int) = MediaFormat.createVideoFormat(MIME, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            // A16 화면은 90Hz라 그대로 두면 설정(60)보다 많은 장이 인코더에 들어간다
            setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, FPS.toFloat())
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10) // 키프레임은 필요할 때 요청
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000) // 화면이 멈춰도 100ms마다 반복
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            setInteger(MediaFormat.KEY_PRIORITY, 0) // 실시간
            if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
    }
}
