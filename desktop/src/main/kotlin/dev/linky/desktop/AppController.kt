package dev.linky.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntSize
import dev.linky.desktop.input.InputSink
import dev.linky.desktop.net.ConnPhase
import dev.linky.desktop.net.Connection
import dev.linky.desktop.net.ConnectionListener
import dev.linky.desktop.net.Discovery
import dev.linky.desktop.net.FoundPhone
import dev.linky.desktop.net.Target
import dev.linky.desktop.store.LocalDevice
import dev.linky.desktop.video.VideoSurface
import dev.linky.protocol.Linky
import dev.linky.protocol.Pairing
import dev.linky.protocol.Scroll
import dev.linky.protocol.Touch
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.swscale
import org.bytedeco.javacpp.Loader
import java.awt.EventQueue
import java.io.IOException
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Timer
import kotlin.concurrent.thread

/**
 * 앱 상태와 동작.
 * 상태는 UI(Swing) 스레드에서만 바꾼다. 다른 스레드의 알림은 post로 넘겨온다.
 */
class AppController(private val device: LocalDevice) : InputSink {
    var phones by mutableStateOf(emptyList<FoundPhone>()); private set
    var trusted by mutableStateOf(device.trust.snapshot()); private set
    var phase by mutableStateOf(ConnPhase.IDLE); private set
    var phoneName by mutableStateOf<String?>(null); private set
    var lastTarget by mutableStateOf<Target?>(null); private set
    var endedText by mutableStateOf<String?>(null); private set
    var sas by mutableStateOf<SasPrompt?>(null); private set
    var stats by mutableStateOf(Stats()); private set
    var videoSize by mutableStateOf(IntSize.Zero); private set
    var surface by mutableStateOf<VideoSurface?>(null); private set
    var quality by mutableStateOf(Quality.NORMAL); private set

    /** 새 영상이 올 때마다 늘어나 영상 영역을 다시 그리게 한다. */
    var frameTick by mutableLongStateOf(0L); private set
    val logs = mutableStateListOf<String>()

    /** 알림 띠 (최근 3개). 보안 경고가 뒤따르는 안내에 가려지지 않게 쌓는다. */
    val banners = mutableStateListOf<Banner>()

    /** 세션이 갑자기 끊겨 다시 연결하는 중. banner: 끊겼다는 띠 (다시 연결되면 치운다) */
    private class Retry(val target: Target, val attempt: Int, val banner: Banner?)

    private var connection: Connection? = null
    private var connectionId = 0
    /** 지금 연결이 자동 연결(자동 연결, 자동 재연결)인지 */
    private var autoConnection = false
    /** 자동 연결을 이미 해 본 mDNS 항목. 목록에서 사라지면 지운다 (다시 나타나면 한 번 더 한다). */
    private val autoTried = HashSet<String>()
    /** 자동 연결이 거절된 항목 (페어링 필요, 인증서 바뀜 등). 사용자가 직접 연결할 때까지 자동으로 붙지 않는다. */
    private val autoStopped = HashSet<String>()
    private var retry by mutableStateOf<Retry?>(null)
    private val retryTimer = Timer(0) { retryNow() }.apply { isRepeats = false }
    /** 종료 중에는 남은 알림이 새 연결을 만들지 못하게 한다 */
    private var shuttingDown = false
    private val framePending = AtomicBoolean()
    private val discovery = Discovery(
        onChange = { list -> post { phones = list; autoConnect() } },
        log = { post { addLog(it) } },
    )

    val inSession get() = phase == ConnPhase.SESSION

    /** 끊긴 세션에 다시 연결하려고 기다리거나 시도하는 중 */
    val retrying get() = retry != null

    fun start() {
        post { addLog("데이터 폴더: ${device.dir}") }
        discovery.start()
        // 신원 파일을 미리 읽어 첫 연결을 빠르게 한다
        thread(isDaemon = true, name = "Linky-신원") {
            val identity = runCatching { device.identity }
            post {
                identity.onSuccess {
                    addLog("이 노트북: ${device.name}, ID ${Pairing.shortId(it.fingerprint)}")
                    // 새 신원은 지문이 달라 기존 페어링이 모두 끊긴다
                    it.recoveredFrom?.let { why -> warn("저장된 신원 파일을 읽지 못해 새로 만들었습니다. 기존 페어링을 다시 해야 합니다: $why") }
                }.onFailure { warn("신원 파일을 만들 수 없습니다: ${it.message}") }
            }
        }
        // FFmpeg 네이티브 라이브러리를 미리 읽어 둔다 (처음 연결할 때 영상이 몇 초씩 늦지 않게)
        thread(isDaemon = true, name = "Linky-FFmpeg") {
            runCatching {
                Loader.load(avcodec::class.java)
                Loader.load(swscale::class.java)
            }.onFailure { post { warn("영상 해독기(FFmpeg)를 불러오지 못했습니다: ${it.message}") } }
        }
    }

    /** 앱 종료: 창이 닫힌 뒤 UI 스레드 밖에서 부른다. */
    fun shutdown() {
        var last: Connection? = null
        EventQueue.invokeAndWait {
            shuttingDown = true
            stopRetry()
            last = connection
            connection = null
        }
        last?.close()
        discovery.close()
    }

    fun isPaired(phone: FoundPhone): Boolean {
        val fp = phone.fp ?: return false
        return fp.length >= 8 && trusted.any { it.fp.startsWith(fp) }
    }

    fun isCurrent(phone: FoundPhone) =
        phase != ConnPhase.IDLE && lastTarget?.let { it.key == phone.key || it.host == phone.host } == true

    /** 사용자가 [연결]을 누름. 자동 연결 제한을 풀고, 필요하면 페어링도 한다. */
    fun connect(target: Target) {
        stopRetry()
        autoStopped.clear()
        banners.clear()
        open(target, auto = false)
    }

    /** IP 직접 입력 (포트는 47180 고정). 목록에 같은 주소가 있으면 그 정보(지문)를 쓴다. */
    fun connectManual(input: String) {
        val host = input.trim().substringBefore(':')
        if (host.isEmpty()) return
        connect(phones.firstOrNull { it.host == host }?.toTarget() ?: Target(host, Linky.PORT, host))
    }

    fun reconnect() {
        val t = lastTarget ?: return
        // mDNS로 찾은 폰이면 지금 주소로 다시 연결
        connect(phones.firstOrNull { it.key == t.key }?.toTarget() ?: t)
    }

    /** [연결 끊기]: 연결과 자동 재연결을 모두 멈춘다. */
    fun disconnect() {
        val c = connection
        if (c == null && retry == null) return
        c?.close()
        stopRetry()
        addLog(if (c != null) "연결을 끊었습니다" else "자동 재연결을 멈췄습니다")
        finish("연결을 끊었습니다", error = false, retryable = false)
    }

    fun forget(fpHex: String) {
        val name = trusted.firstOrNull { it.fp == fpHex }?.name ?: fpHex.take(16)
        try {
            device.trust.remove(fpHex)
            addLog("페어링 삭제: $name")
        } catch (e: IOException) {
            addLog("페어링 정보를 저장하지 못했습니다: ${e.message}")
        }
        trusted = device.trust.snapshot()
    }

    fun confirmPairing(match: Boolean) {
        val prompt = sas ?: return
        sas = prompt.copy(answered = true)
        addLog(if (match) "숫자 일치를 확인했습니다. 폰의 확인을 기다립니다" else "숫자가 다르다고 답했습니다")
        connection?.confirmPairing(match)
    }

    fun changeQuality(q: Quality) {
        quality = q
        addLog("화질: ${q.label} (${q.detail})")
        if (inSession) connection?.sendVideoSettings(q.bitrate, q.maxSize)
    }

    /** 글자 보내기. 보내지 못하면 false (입력칸을 비우지 않는다). */
    fun sendText(text: String): Boolean {
        if (text.isEmpty() || !inSession) return false
        return connection?.sendText(text) == true
    }

    fun dismissBanner(b: Banner) {
        banners.remove(b)
    }

    override fun touch(t: Touch) {
        if (inSession) connection?.sendTouch(t)
    }

    override fun scroll(s: Scroll) {
        if (inSession) connection?.sendScroll(s)
    }

    override fun key(code: Int) {
        if (inSession) connection?.sendKey(code)
    }

    private fun open(target: Target, auto: Boolean) {
        if (shuttingDown) return
        connection?.let {
            it.close()
            teardown()
        }
        markTried(target)
        endedText = null
        lastTarget = target
        phoneName = target.label
        phase = ConnPhase.CONNECTING
        autoConnection = auto
        val s = VideoSurface(::frameReady)
        surface = s
        val id = ++connectionId
        connection = Connection(target, device, quality, auto, s, Events(id)).also { it.start() }
    }

    /** 이 폰에 연결한 뒤에는 같은 항목이든, 같은 주소의 다른 항목이든 저절로 다시 붙지 않게 한다. */
    private fun markTried(target: Target) {
        target.key?.let { autoTried += it }
        phones.filter { it.host == target.host }.forEach { autoTried += it.key }
    }

    /** 페어링한 폰이 하나만 보이고 연결이 없으면 자동 연결 (그 폰이 목록에서 사라졌다 나타나기 전까지 한 번만). */
    private fun autoConnect() {
        autoTried.retainAll(phones.map { it.key }.toSet())
        if (connection != null) {
            // 직접 입력한 주소로 연결한 사이에 목록에 나타난 같은 폰도 이미 시도한 것으로 본다
            lastTarget?.let(::markTried)
            return
        }
        if (shuttingDown || retry != null) return
        val only = phones.filter { isPaired(it) && it.version == Linky.PROTOCOL_VERSION.toString() }.singleOrNull() ?: return
        if (only.key in autoTried || only.key in autoStopped) return
        addLog("페어링한 폰 ${only.name}이(가) 보여 자동으로 연결합니다")
        open(only.toTarget(), auto = true)
    }

    /** 연결이 끝남: 사용자가 끊었거나 연결이 알려옴. */
    private fun finish(message: String, error: Boolean, retryable: Boolean) {
        val target = lastTarget
        val wasSession = phase == ConnPhase.SESSION
        val wasAuto = autoConnection
        teardown()
        endedText = message
        if (retryable && target != null && (wasSession || retry != null)) {
            // 다시 연결하는 동안의 실패는 띠로 쌓지 않는다 (로그에는 남는다)
            val banner = if (retry == null) showBanner(message, error = true) else null
            scheduleRetry(target, banner)
        } else {
            if (error) showBanner(message, error = true)
            stopRetry()
            // 자동 연결이 거절되면 사용자가 직접 연결할 때까지 그 폰에 자동으로 붙지 않는다
            if (wasAuto && !wasSession && !retryable) target?.key?.let { autoStopped += it }
        }
        autoConnect()
    }

    /**
     * 세션이 갑자기 끊기면 2, 4, 8 … 최대 30초 간격으로 MAX_RETRIES번까지 다시 연결해 본다. 자동 연결이라 페어링은 하지 않는다.
     * 그 뒤에는 폰이 목록에서 사라졌다 다시 나타날 때 자동 연결이 이어받는다.
     */
    private fun scheduleRetry(target: Target, banner: Banner?) {
        val prev = retry
        val attempt = (prev?.attempt ?: 0) + 1
        if (attempt > MAX_RETRIES) {
            addLog("다시 연결하지 못해 자동 재연결을 멈췄습니다")
            stopRetry()
            return
        }
        val delay = minOf(RETRY_FIRST_MS shl (attempt - 1), RETRY_MAX_MS)
        retry = Retry(target, attempt, prev?.banner ?: banner)
        endedText = "연결이 끊겼습니다. ${delay / 1000}초 뒤 다시 연결합니다"
        addLog("${delay / 1000}초 뒤 다시 연결합니다 (${attempt}번째)")
        retryTimer.initialDelay = delay
        retryTimer.restart()
    }

    private fun retryNow() {
        val r = retry ?: return
        if (connection != null || shuttingDown) return
        // mDNS로 찾은 폰이면 지금도 목록에 있을 때만, 지금 주소로
        val target = if (r.target.key == null) r.target else phones.firstOrNull { it.key == r.target.key }?.toTarget()
        if (target == null) {
            addLog("폰이 목록에서 사라져 자동 재연결을 멈췄습니다")
            stopRetry()
            autoConnect()
            return
        }
        open(target, auto = true)
    }

    private fun stopRetry() {
        retryTimer.stop()
        retry = null
    }

    private fun teardown() {
        connection = null
        connectionId++ // 끝난 연결의 남은 알림은 무시
        surface?.release()
        surface = null
        phase = ConnPhase.IDLE
        sas = null
        videoSize = IntSize.Zero
        stats = Stats()
    }

    /** 해독 스레드에서 불림: 쌓인 알림이 없을 때만 UI에 한 번 알린다. */
    private fun frameReady() {
        if (framePending.compareAndSet(false, true)) {
            EventQueue.invokeLater {
                framePending.set(false)
                frameTick++
            }
        }
    }

    /** 알림 띠를 맨 아래에 더한다. 문구는 한 줄 로그와 같은 길이까지만. */
    private fun showBanner(text: String, error: Boolean): Banner {
        val b = Banner(text.take(MAX_LINE), error)
        banners.remove(b)
        banners += b
        if (banners.size > MAX_BANNERS) banners.removeRange(0, banners.size - MAX_BANNERS)
        return b
    }

    private fun warn(text: String) {
        addLog(text)
        showBanner(text, error = true)
    }

    /** 로그 한 줄은 300자까지 (상대가 보낸 긴 값이 메모리와 화면을 차지하지 못하게). */
    private fun addLog(text: String) {
        logs += "${LocalTime.now().format(TIME)}  ${text.take(MAX_LINE)}"
        if (logs.size > MAX_LOG) logs.removeRange(0, logs.size - MAX_LOG)
    }

    private fun post(block: () -> Unit) = EventQueue.invokeLater(block)

    /** 연결 하나의 알림. 이미 끝났거나 바뀐 연결의 알림은 버린다 (로그는 남긴다). */
    private inner class Events(private val id: Int) : ConnectionListener {
        private fun current(block: () -> Unit) = post { if (id == connectionId) block() }

        override fun log(text: String) = post { addLog(text) }
        override fun trustChanged() = post { trusted = device.trust.snapshot() }

        override fun phase(phase: ConnPhase, phoneName: String) = current {
            this@AppController.phase = phase
            this@AppController.phoneName = phoneName
            if (phase == ConnPhase.SESSION) {
                sas = null
                // 다시 연결되었으면 끊겼다는 띠를 치운다
                retry?.banner?.let { banners.remove(it) }
                stopRetry()
                // 처음 화질은 CLIENT_HELLO로 보냈다. 연결하는 사이에 바꿨을 때만 따로 보낸다.
                connection?.let { if (it.quality != quality) it.sendVideoSettings(quality.bitrate, quality.maxSize) }
            }
        }

        override fun notice(text: String) = current { showBanner(text, error = false) }

        override fun pairingCode(code: String, phoneId: String, warning: String?) {
            val laptopId = Pairing.shortId(device.identity.fingerprint)
            current { sas = SasPrompt(code, phoneName.orEmpty(), phoneId, laptopId, warning) }
        }

        override fun videoSize(width: Int, height: Int) = current { videoSize = IntSize(width, height) }
        override fun stats(fps: Float, kbps: Long) = current { stats = stats.copy(fps = fps, kbps = kbps) }
        override fun rtt(ms: Long) = current { stats = stats.copy(rttMs = ms) }

        override fun closed(message: String?, error: Boolean, retryable: Boolean) = current {
            message?.let(::addLog)
            finish(message ?: "연결 끊김", error, retryable)
        }
    }

    private companion object {
        const val MAX_LOG = 300
        const val MAX_LINE = 300
        const val MAX_BANNERS = 3
        const val RETRY_FIRST_MS = 2_000
        const val RETRY_MAX_MS = 30_000
        const val MAX_RETRIES = 8
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}
