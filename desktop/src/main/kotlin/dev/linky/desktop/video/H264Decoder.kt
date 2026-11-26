package dev.linky.desktop.video

import dev.linky.protocol.VideoConfig
import dev.linky.protocol.VideoFrame
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_FLAG2_FAST
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_FLAG_LOW_DELAY
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264
import org.bytedeco.ffmpeg.global.avcodec.AV_INPUT_BUFFER_PADDING_SIZE
import org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc
import org.bytedeco.ffmpeg.global.avcodec.av_packet_free
import org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder
import org.bytedeco.ffmpeg.global.avcodec.avcodec_flush_buffers
import org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_open2
import org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_frame
import org.bytedeco.ffmpeg.global.avcodec.avcodec_send_packet
import org.bytedeco.ffmpeg.global.avutil.AVCOL_RANGE_JPEG
import org.bytedeco.ffmpeg.global.avutil.AVCOL_SPC_BT709
import org.bytedeco.ffmpeg.global.avutil.AVERROR_EOF
import org.bytedeco.ffmpeg.global.avutil.AV_LOG_ERROR
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGRA
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUVJ420P
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.ffmpeg.global.avutil.av_log_set_level
import org.bytedeco.ffmpeg.global.avutil.av_strerror
import org.bytedeco.ffmpeg.global.swscale.SWS_BILINEAR
import org.bytedeco.ffmpeg.global.swscale.SWS_CS_DEFAULT
import org.bytedeco.ffmpeg.global.swscale.SWS_CS_ITU709
import org.bytedeco.ffmpeg.global.swscale.sws_freeContext
import org.bytedeco.ffmpeg.global.swscale.sws_getCachedContext
import org.bytedeco.ffmpeg.global.swscale.sws_getCoefficients
import org.bytedeco.ffmpeg.global.swscale.sws_scale
import org.bytedeco.ffmpeg.global.swscale.sws_setColorspaceDetails
import org.bytedeco.ffmpeg.swscale.SwsContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.DoublePointer
import org.bytedeco.javacpp.IntPointer
import org.bytedeco.javacpp.PointerPointer

/**
 * H.264(Annex-B) 해독 → BGRA (설계서 2.2). avcodec을 직접 부른다.
 * 받기 스레드 한 곳에서만 쓰고, 연결이 끝나면 release()로 네이티브 자원을 모두 푼다.
 */
class H264Decoder(
    private val surface: VideoSurface,
    private val onVideoSize: (Int, Int) -> Unit,
) {
    private var ctx: AVCodecContext? = null
    private var packet: AVPacket? = null
    private var frame: AVFrame? = null
    private var sws: SwsContext? = null
    /** sws에 마지막으로 맞춘 설정. 다르면 문맥이 새로 만들어졌을 수 있어 색 정보를 다시 넣는다. */
    private var swsKey: SwsKey? = null
    private var input: BytePointer? = null
    private var dstData: PointerPointer<BytePointer>? = null
    private var dstStride: IntPointer? = null

    private var config: ByteArray? = null
    private var configPending = false
    private var waitKey = true
    private var lastW = 0
    private var lastH = 0

    /** 지금까지 화면에 보낸 장 수 (fps 계산용) */
    var shownFrames = 0L
        private set

    /** VIDEO_CONFIG: 해독기를 새로 만들고 SPS/PPS는 다음 프레임 앞에 붙인다. */
    fun configure(c: VideoConfig) {
        freeCodec()
        val codec = avcodec_find_decoder(AV_CODEC_ID_H264)?.takeIf { !it.isNull }
            ?: throw IllegalStateException("H.264 해독기를 찾을 수 없습니다")
        val cx = avcodec_alloc_context3(codec)?.takeIf { !it.isNull }
            ?: throw IllegalStateException("해독기 메모리를 만들 수 없습니다")
        cx.flags(cx.flags() or AV_CODEC_FLAG_LOW_DELAY)
        cx.flags2(cx.flags2() or AV_CODEC_FLAG2_FAST)
        cx.thread_count(1) // 프레임 스레딩은 지연을 늘린다
        val ret = avcodec_open2(cx, codec, null as AVDictionary?)
        if (ret < 0) {
            avcodec_free_context(cx)
            throw IllegalStateException("H.264 해독기를 열 수 없습니다 (${errorText(ret)})")
        }
        ctx = cx
        if (packet == null) {
            av_log_set_level(AV_LOG_ERROR)
            packet = av_packet_alloc()
            frame = av_frame_alloc()
            input = BytePointer(INITIAL_INPUT)
            dstData = PointerPointer(4)
            dstStride = IntPointer(4L)
        }
        config = c.codecConfig
        configPending = true
        waitKey = true
    }

    /** VIDEO_FRAME 내용 하나. false면 키프레임이 필요하다 (설정 전, 키프레임 대기 중, 해독 오류). */
    fun decode(payload: ByteArray): Boolean {
        val cx = ctx ?: return false
        val pkt = packet ?: return false
        val out = frame ?: return false
        if (waitKey && !VideoFrame.isKey(payload)) return false
        waitKey = false

        val prefix = if (configPending) config else null
        configPending = false
        fillPacket(pkt, prefix, payload, VideoFrame.HEADER_SIZE)
        if (avcodec_send_packet(cx, pkt) < 0) return fail(cx)
        while (true) {
            val r = avcodec_receive_frame(cx, out)
            if (r == AVERROR_EAGAIN || r == AVERROR_EOF) return true
            if (r < 0) return fail(cx)
            show(out)
        }
    }

    fun release() {
        freeCodec()
        packet?.let {
            it.data(null).size(0) // 데이터는 우리 버퍼라 FFmpeg이 풀면 안 된다
            av_packet_free(it)
        }
        frame?.let { av_frame_free(it) }
        sws?.let { sws_freeContext(it) }
        input?.close()
        dstData?.close()
        dstStride?.close()
        packet = null
        frame = null
        sws = null
        swsKey = null
        input = null
        dstData = null
        dstStride = null
    }

    /** 오류 뒤에는 다음 키프레임까지 버린다. SPS/PPS도 다시 붙인다. */
    private fun fail(cx: AVCodecContext): Boolean {
        avcodec_flush_buffers(cx)
        waitKey = true
        configPending = config != null
        return false
    }

    private fun freeCodec() {
        ctx?.let { avcodec_free_context(it) }
        ctx = null
    }

    /** [prefix][data[offset..]][0 패딩]을 재사용 버퍼에 채운다 (FFmpeg은 끝에 0 패딩을 요구한다). */
    private fun fillPacket(pkt: AVPacket, prefix: ByteArray?, data: ByteArray, offset: Int) {
        val pre = prefix?.size ?: 0
        val len = pre + data.size - offset
        val need = (len + AV_INPUT_BUFFER_PADDING_SIZE).toLong()
        var buf = input!!
        if (buf.capacity() < need) {
            buf.close()
            buf = BytePointer(need * 3 / 2)
            input = buf
        }
        if (prefix != null) buf.position(0).put(prefix, 0, pre)
        buf.position(pre.toLong()).put(data, offset, data.size - offset)
        buf.position(len.toLong()).put(PADDING, 0, PADDING.size)
        buf.position(0)
        pkt.data(buf).size(len)
    }

    private fun show(f: AVFrame) {
        val w = f.width()
        val h = f.height()
        if (w != lastW || h != lastH) {
            lastW = w
            lastH = h
            onVideoSize(w, h)
        }
        val dst = dstData ?: return
        val stride = dstStride ?: return
        surface.write(w, h) { pixels, rowBytes, outW, outH ->
            sws = sws_getCachedContext(sws, w, h, f.format(), outW, outH, AV_PIX_FMT_BGRA, SWS_BILINEAR, null, null, null as DoublePointer?)
            val s = sws?.takeIf { !it.isNull } ?: run {
                swsKey = null
                return@write false
            }
            val key = SwsKey(
                w, h, f.format(), outW, outH,
                fullRange = f.color_range() == AVCOL_RANGE_JPEG || f.format() == AV_PIX_FMT_YUVJ420P,
                bt709 = f.colorspace() == AVCOL_SPC_BT709,
            )
            if (key != swsKey) {
                setColorspace(s, key)
                swsKey = key
            }
            dst.put(0, pixels)
            stride.put(0L, rowBytes)
            sws_scale(s, f.data(), f.linesize(), 0, h, dst, stride) > 0
        }
        shownFrames++
    }

    /** 영상의 색 범위(제한/전체)와 색 행렬(BT.601/709)을 반영한다. 출력(BGRA)은 전체 범위. */
    private fun setColorspace(s: SwsContext, key: SwsKey) {
        val matrix = sws_getCoefficients(if (key.bt709) SWS_CS_ITU709 else SWS_CS_DEFAULT)
        sws_setColorspaceDetails(s, matrix, if (key.fullRange) 1 else 0, sws_getCoefficients(SWS_CS_DEFAULT), 1, 0, 1 shl 16, 1 shl 16)
    }

    private fun errorText(code: Int): String {
        val buf = ByteArray(128)
        av_strerror(code, buf, buf.size.toLong())
        return String(buf, Charsets.UTF_8).substringBefore('\u0000')
    }

    private data class SwsKey(
        val srcW: Int,
        val srcH: Int,
        val format: Int,
        val outW: Int,
        val outH: Int,
        val fullRange: Boolean,
        val bt709: Boolean,
    )

    private companion object {
        /** AVERROR(EAGAIN). Windows(MSVC, MinGW)의 EAGAIN은 11 */
        const val AVERROR_EAGAIN = -11
        const val INITIAL_INPUT = 512L * 1024
        val PADDING = ByteArray(AV_INPUT_BUFFER_PADDING_SIZE)
    }
}
