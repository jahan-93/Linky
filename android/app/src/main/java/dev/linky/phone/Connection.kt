package dev.linky.phone

import android.os.Build
import android.os.SystemClock
import dev.linky.protocol.Frame
import dev.linky.protocol.FrameReader
import dev.linky.protocol.FrameWriter
import dev.linky.protocol.Identity
import dev.linky.protocol.Linky
import dev.linky.protocol.Msg
import dev.linky.protocol.Pairing
import dev.linky.protocol.Props
import dev.linky.protocol.Scroll
import dev.linky.protocol.Tls
import dev.linky.protocol.Touch
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 노트북 하나와의 연결 (설계서 1장).
 * 읽기 스레드 하나(run)가 메시지를 받아 단계(stage)에 맞게 처리한다.
 * 단계는 lock 안에서만 바꾸고, 끝(ENDED)이 되면 되돌리지 않는다.
 *
 * 쓰기 규칙 (설계서 1.7):
 * - 영상은 인코더 스레드가, 그 밖의 것(HELLO, PONG, NOTICE, 페어링)은 이 연결 전용 보내기 스레드가 쓴다.
 *   읽기 스레드, 메인 스레드, 공용 타이머는 소켓에 쓰지 않는다.
 * - 쓰기가 5초 넘게 막히면 감시 스레드(Watchdog)가 소켓을 끊는다. 소켓 닫기도 감시 스레드가 한다.
 */
class Connection(
    private val service: ShareService,
    private val link: Link,
    private val identity: Identity,
    val id: Int,
) {
    private enum class Stage { HELLO, PAIR_COMMIT, PAIR_REVEAL, PAIR_CONFIRM, SESSION, ENDED }

    private val lock = Any()
    @Volatile private var stage = Stage.HELLO
    @Volatile private var writer: FrameWriter? = null
    private val sender = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(SEND_QUEUE)) { r ->
        Thread(r, "linky-send-$id").apply { isDaemon = true }
    }
    private val acceptedAt = SystemClock.uptimeMillis()

    /** 인증 시한. 처음에는 CLIENT_HELLO까지 10초, 페어링 중에는 약 130초. PING으로 연장되지 않는다. */
    @Volatile private var deadline: ScheduledFuture<*>? =
        Watchdog.deadline(link, HELLO_DEADLINE_MS) { stage == Stage.HELLO }
    @Volatile private var writeGuard: ScheduledFuture<*>? = null

    /** 노트북 인증서 지문 hex와 화면 표시용 ID (핸드셰이크 뒤에 정해짐) */
    @Volatile var peerHex: String? = null
        private set
    @Volatile var peerId: String = ""
        private set
    @Volatile var peerName: String = link.tcp.inetAddress?.hostAddress ?: "노트북"
        private set
    val remoteAddress: InetAddress? get() = link.tcp.inetAddress

    // 읽기 스레드 전용
    private var peerFp = ByteArray(0)
    private var commit: ByteArray? = null
    private var phoneNonce: ByteArray? = null
    private var lastA11yNotice = -A11Y_NOTICE_GAP_MS
    private var lastPreAuthPing = -PREAUTH_PING_GAP_MS

    // 두 사람의 확인 (lock으로 보호)
    private var userOk: Boolean? = null
    private var desktopOk = false
    @Volatile private var pairTimeout: ScheduledFuture<*>? = null

    // 영상 설정 (CLIENT_HELLO, VIDEO_SETTINGS)
    @Volatile var bitrate = DEFAULT_BITRATE
        private set
    @Volatile var maxSize = DEFAULT_MAX_SIZE
        private set

    val isSession: Boolean get() = stage == Stage.SESSION

    /** 세션이 아닌 연결(인증 전이거나 끝나는 중). 연결 수 제한에 센다. */
    val isPreAuth: Boolean get() = stage != Stage.SESSION

    /** 읽기 스레드 본체. 어떤 오류가 나도 밖으로 던지지 않는다. */
    fun run() {
        AppState.log("노트북 접속: $peerName")
        try {
            link.tls.startHandshake()
            val fp = Tls.peerFingerprint(link.tls)
            peerFp = fp
            peerHex = Pairing.hex(fp)
            peerId = Pairing.shortId(fp)
            val out = TimedOutput(link.tls.outputStream)
            writer = FrameWriter(out)
            writeGuard = Watchdog.guard(link, WRITE_CHECK_MS) { out.blockedMs() > WRITE_STUCK_MS }
            // HELLO는 노트북의 CLIENT_HELLO를 받은 뒤에 보낸다 (설계서 1.2)
            val reader = FrameReader(link.tls.inputStream)
            while (true) {
                // 인증 전에는 작은 프레임만 받는다 (모르는 상대가 큰 메모리를 잡지 못하게)
                val frame = reader.read(if (isSession) Linky.MAX_FRAME else Linky.MAX_PREAUTH_FRAME) ?: break
                if (stage != Stage.ENDED) handle(frame) // 끝난 뒤 온 것은 읽어서 버린다
            }
        } catch (e: SocketTimeoutException) {
            if (stage != Stage.ENDED) AppState.log("$peerName: 응답이 없어 연결을 끊었습니다")
        } catch (e: Throwable) {
            // OutOfMemoryError 포함: 무슨 일이 있어도 앱이 죽지 않게 한다
            when {
                stage == Stage.ENDED -> {}
                stage == Stage.HELLO && SystemClock.uptimeMillis() - acceptedAt >= HELLO_DEADLINE_MS ->
                    AppState.log("$peerName: 10초 안에 인증을 시작하지 않아 연결을 끊었습니다")
                else -> AppState.log("$peerName: 연결 오류 (${e.message ?: e.javaClass.simpleName})")
            }
        } finally {
            markEnded()
            writeGuard?.cancel(false)
            sender.shutdown()
            Watchdog.close(link)
        }
    }

    private fun handle(f: Frame) {
        val p = f.payload
        when (f.type) {
            Msg.PING -> onPing(p)
            Msg.VIDEO_SETTINGS -> onVideoSettings(Props.decode(p))
            Msg.REQUEST_KEYFRAME -> if (isSession) service.requestKeyframe(this)
            // 조작은 지금 활성 세션에서 온 것만 실행한다. 세션 전이거나 밀려난 연결의 조작은 조용히 버린다
            Msg.TOUCH, Msg.SCROLL, Msg.KEY, Msg.TEXT -> if (isSession && service.isActive(this)) control(f.type, p)
            Msg.CLIENT_HELLO -> expect(Stage.HELLO) { onClientHello(Props.decode(p)) }
            Msg.PAIR_COMMIT -> expect(Stage.PAIR_COMMIT) { onCommit(p) }
            Msg.PAIR_REVEAL -> expect(Stage.PAIR_REVEAL) { onReveal(p) }
            Msg.PAIR_CONFIRM -> expect(Stage.PAIR_CONFIRM) { onDesktopConfirm(p) }
            // 인증 전에 모르는 메시지가 와도 로그를 채우지 않는다
            else -> if (isSession) AppState.log("$peerName: 알 수 없는 메시지 ${f.type} (무시)")
        }
    }

    private inline fun expect(s: Stage, block: () -> Unit) {
        if (stage == s) {
            block()
        } else {
            AppState.log("$peerName: 순서가 맞지 않는 메시지라 연결을 끊습니다")
            finish(NOTICE_ORDER)
        }
    }

    /** 정확히 8바이트인 PING에만 답한다. 인증 전에는 0.5초에 한 번까지만 (설계서 1.7). */
    private fun onPing(p: ByteArray) {
        if (p.size != 8) return
        if (!isSession) {
            val now = SystemClock.uptimeMillis()
            if (now - lastPreAuthPing < PREAUTH_PING_GAP_MS) return
            lastPreAuthPing = now
        }
        send(Msg.PONG, p)
    }

    // ---------- HELLO와 판단 (1.2, 1.3) ----------

    private fun onClientHello(h: Map<String, String>) {
        peerName = Props.cleanName(h["name"], peerName)
        takeVideoSettings(h)
        val hex = peerHex ?: return
        if (h["version"] != Linky.PROTOCOL_VERSION.toString()) {
            AppState.log("$peerName: 앱 버전이 다릅니다 (노트북 ${h["version"]?.take(8)}, 폰 ${Linky.PROTOCOL_VERSION})")
            sendHello(trusted = false, pairing = false)
            finish(NOTICE_VERSION)
            return
        }
        // 신뢰 여부와 페어링 창은 연결할 때가 아니라 지금 이 순간의 값으로 판단한다
        val phoneTrusts = service.trust.contains(hex)
        val desktopTrusts = h["trusted"] == "1"
        when {
            phoneTrusts && desktopTrusts -> {
                sendHello(trusted = true, pairing = false)
                service.trust.rename(hex, peerName) // 노트북 이름이 바뀌었으면 갱신 (삭제했으면 되살리지 않음)
                if (advance(Stage.HELLO, Stage.SESSION)) {
                    deadline?.cancel(false)
                    startSession()
                }
            }
            !AppState.pairingOpen -> {
                sendHello(phoneTrusts, pairing = false)
                AppState.log("$peerName: 페어링되지 않은 노트북이라 거절했습니다")
                finish(NOTICE_NOT_PAIRED)
            }
            !service.beginPairing(this) -> {
                sendHello(phoneTrusts, pairing = false)
                AppState.log("$peerName: 다른 노트북과 페어링 중이라 거절했습니다")
                finish(NOTICE_BUSY)
            }
            advance(Stage.HELLO, Stage.PAIR_COMMIT) -> {
                sendHello(phoneTrusts, pairing = true)
                // 사람이 숫자를 확인하는 동안(최대 2분)은 읽기 제한 시간과 인증 시한을 늘린다
                link.tls.soTimeout = PAIRING_DEADLINE_MS.toInt()
                deadline?.cancel(false)
                deadline = Watchdog.deadline(link, PAIRING_DEADLINE_MS) { stage in PAIRING }
                pairTimeout = service.runLater(PAIRING_TIMEOUT_MS) { onPairingTimeout() }
                AppState.log("$peerName: 페어링을 시작합니다")
            }
            else -> service.endPairing(this)
        }
    }

    private fun sendHello(trusted: Boolean, pairing: Boolean) = send(Msg.HELLO, Props.encode(mapOf(
        "name" to service.deviceName,
        "model" to Build.MODEL,
        "sdk" to Build.VERSION.SDK_INT.toString(),
        "version" to Linky.PROTOCOL_VERSION.toString(),
        "id" to Pairing.shortId(identity.fingerprint),
        "trusted" to flag(trusted),
        "pairing" to flag(pairing),
    )))

    // ---------- 숫자 비교 페어링 (1.4) ----------

    private fun onCommit(p: ByteArray) {
        if (p.size != 32) {
            finish(NOTICE_PAIR_FAILED)
            return
        }
        val nonce = Pairing.nonce()
        commit = p
        phoneNonce = nonce
        if (!advance(Stage.PAIR_COMMIT, Stage.PAIR_REVEAL)) return
        // 창 하나에 시도 한 번: 숫자를 미리 보고 끊은 뒤 맞을 때까지 다시 시도하는 것을 막는다
        AppState.closePairingWindow()
        send(Msg.PAIR_NONCE, nonce)
    }

    private fun onReveal(desktopNonce: ByteArray) {
        val c = commit
        val n = phoneNonce
        if (c == null || n == null || !Pairing.verifyCommit(c, desktopNonce)) {
            AppState.log("$peerName: 페어링 확인값이 맞지 않습니다")
            finish(NOTICE_PAIR_FAILED)
            return
        }
        val sas = Pairing.sas(identity.fingerprint, peerFp, n, desktopNonce)
        if (!advance(Stage.PAIR_REVEAL, Stage.PAIR_CONFIRM)) return
        service.showPrompt(this, sas)
        // 띄우는 사이에 연결이 끝났으면 방금 띄운 확인 창을 지운다
        if (stage != Stage.PAIR_CONFIRM) service.hidePrompt(this)
        AppState.log("$peerName: 숫자 확인을 기다립니다")
    }

    private fun onDesktopConfirm(p: ByteArray) {
        if (p.isEmpty() || p[0].toInt() != 1) {
            AppState.log("$peerName: 노트북에서 숫자가 다르다고 했습니다")
            finish(pairFailed = true)
            return
        }
        synchronized(lock) { desktopOk = true }
        AppState.log("$peerName: 노트북에서 숫자를 확인했습니다")
        tryCompletePairing()
    }

    /** 폰 사용자의 [일치]/[다름]. 메인 스레드에서 불러도 된다. */
    fun decide(ok: Boolean) {
        service.hidePrompt(this)
        service.runLater { onUserDecision(ok) }
    }

    private fun onUserDecision(ok: Boolean) {
        synchronized(lock) {
            if (stage != Stage.PAIR_CONFIRM || userOk != null) return
            userOk = ok
        }
        if (ok) {
            AppState.log("폰에서 숫자를 확인했습니다")
            tryCompletePairing()
        } else {
            AppState.log("폰에서 숫자가 다르다고 했습니다. 페어링을 취소합니다")
            finish(pairFailed = true)
        }
    }

    /** 두 사람 모두 확인했으면 신뢰 목록에 저장하고 세션을 시작한다. */
    private fun tryCompletePairing() {
        synchronized(lock) {
            if (stage != Stage.PAIR_CONFIRM || userOk != true || !desktopOk) return
            stage = Stage.SESSION
        }
        pairTimeout?.cancel(false)
        deadline?.cancel(false)
        peerHex?.let { service.trust.add(it, peerName) }
        service.endPairing(this)
        send(Msg.PAIR_RESULT, byteArrayOf(1))
        runCatching { link.tls.soTimeout = READ_TIMEOUT_MS }
        AppState.log("$peerName: 페어링 완료")
        startSession()
    }

    private fun onPairingTimeout() {
        if (stage in PAIRING) AppState.log("$peerName: 2분이 지나 페어링을 취소했습니다")
        finish(pairFailed = true)
    }

    // ---------- 세션 (2, 3장) ----------

    /** 앞서 넣은 HELLO나 PAIR_RESULT가 다 나간 뒤에 세션을 시작한다 (영상이 그보다 먼저 가지 않게). */
    private fun startSession() = enqueue { service.onAuthenticated(this) }

    /** bitrate, maxSize를 읽어 둔다. 지금과 다르면 true. */
    private fun takeVideoSettings(p: Map<String, String>): Boolean {
        val b = p["bitrate"]?.toLongOrNull()?.coerceIn(MIN_BITRATE, MAX_BITRATE)?.toInt() ?: bitrate
        val m = p["maxSize"]?.toIntOrNull()?.coerceAtLeast(0) ?: maxSize
        if (b == bitrate && m == maxSize) return false
        bitrate = b
        maxSize = m
        return true
    }

    /** 화질 변경. 지금과 같은 설정이면 인코더를 다시 만들지 않는다. 세션 전에 오면 저장만 한다. */
    private fun onVideoSettings(p: Map<String, String>) {
        if (!takeVideoSettings(p) || !isSession) return
        AppState.log("화질 설정: ${bitrate / 1_000_000}Mbps, 긴 변 ${if (maxSize > 0) "${maxSize}px" else "원본"}")
        service.onVideoSettings(this)
    }

    private fun control(type: Int, p: ByteArray) {
        val a11y = LinkyAccessibilityService.instance
        val streamer = a11y?.streamer
        when (type) {
            Msg.TOUCH -> {
                val t = Touch.decode(p)
                if (!t.x.isFinite() || !t.y.isFinite()) return // NaN·무한대 좌표는 버린다
                val at = service.videoToScreen(t.x, t.y)
                val ms = if (t.timeMs.isFinite()) t.timeMs else Double.NaN // NaN = 시각 모름
                when {
                    streamer == null -> if (t.action == Touch.DOWN) noticeA11yOff()
                    t.action == Touch.DOWN -> streamer.down(at.x, at.y, ms)
                    t.action == Touch.MOVE -> streamer.move(at.x, at.y, ms)
                    t.action == Touch.UP -> streamer.up(at.x, at.y, ms)
                }
            }
            Msg.SCROLL -> {
                val s = Scroll.decode(p)
                if (!s.x.isFinite() || !s.y.isFinite() || !s.notches.isFinite()) return
                val at = service.videoToScreen(s.x, s.y)
                if (streamer == null) noticeA11yOff() else streamer.scroll(at.x, at.y, s.notches)
            }
            Msg.KEY -> when {
                a11y == null -> noticeA11yOff()
                p.isEmpty() || !a11y.globalKey(p[0].toInt() and 0xFF) -> AppState.log("시스템 버튼을 실행하지 못했습니다")
            }
            Msg.TEXT -> {
                if (a11y == null) noticeA11yOff()
                else a11y.inputText(String(p, Charsets.UTF_8))?.let { send(Msg.NOTICE, it.toByteArray()) }
            }
        }
    }

    /** 접근성이 꺼져 있다는 안내. 휠을 굴릴 때마다 쏟아지지 않게 2초에 한 번만. */
    private fun noticeA11yOff() {
        val now = SystemClock.uptimeMillis()
        if (now - lastA11yNotice < A11Y_NOTICE_GAP_MS) return
        lastA11yNotice = now
        send(Msg.NOTICE, NOTICE_A11Y_OFF.toByteArray())
    }

    fun sendScreen(width: Int, height: Int) =
        send(Msg.SCREEN, Props.encode(mapOf("width" to width.toString(), "height" to height.toString())))

    /** 영상 보내기 (인코더 스레드에서 직접 쓴다). 실패하면 false. */
    fun sendVideo(type: Int, header: ByteArray, data: ByteArray, length: Int): Boolean {
        val w = writer ?: return false
        return try {
            w.write(type, header, data, 0, length)
            true
        } catch (e: IOException) {
            writeFailed(e)
            false
        }
    }

    // ---------- 보내기와 끝내기 ----------

    /** 영상 밖의 모든 쓰기. 보내기 스레드에 넣고 바로 돌아온다. */
    private fun send(type: Int, payload: ByteArray = FrameWriter.EMPTY) {
        val w = writer ?: return
        enqueue {
            try {
                w.write(type, payload)
            } catch (e: IOException) {
                writeFailed(e)
            }
        }
    }

    /** 보내기 스레드에서 넣은 순서대로 한다. 큐가 넘치면 상대가 읽지 않는 것이므로 끊는다. */
    private fun enqueue(task: () -> Unit) {
        try {
            sender.execute(task)
        } catch (e: RejectedExecutionException) {
            // 이미 끝난 연결이면 조용히 버린다
            if (!sender.isShutdown && markEnded() != null) {
                AppState.log("$peerName: 노트북이 받지 않아 연결을 끊었습니다")
                Watchdog.abort(link)
            }
        }
    }

    private fun writeFailed(e: IOException) {
        if (stage != Stage.ENDED) AppState.log("$peerName: 보내기 실패 (${e.message ?: e.javaClass.simpleName})")
        Watchdog.abort(link)
    }

    private fun advance(from: Stage, to: Stage): Boolean = synchronized(lock) {
        if (stage != from) return false
        stage = to
        true
    }

    /**
     * 끝 단계로 바꾼다. 처음 끝낸 경우에만 그 전 단계를 돌려준다 (아니면 null).
     * onlyIfPairing이면 페어링 중일 때만 끝낸다.
     */
    private fun markEnded(onlyIfPairing: Boolean = false): Stage? {
        val was = synchronized(lock) {
            val s = stage
            if (s == Stage.ENDED || (onlyIfPairing && s !in PAIRING)) return null
            stage = Stage.ENDED
            s
        }
        deadline?.cancel(false)
        pairTimeout?.cancel(false)
        service.endPairing(this)
        // 폰 난수를 보낸 뒤 성공하지 못하고 끝났다 (창은 이미 닫혔으므로 다시 열어야 한다)
        if (was == Stage.PAIR_REVEAL || was == Stage.PAIR_CONFIRM) service.onPairingInterrupted(peerName)
        if (was == Stage.SESSION) service.onSessionEnded(this)
        return was
    }

    /**
     * 안내(NOTICE)나 페어링 실패(PAIR_RESULT 0)를 보내고, 상대가 읽을 시간을 준 뒤 닫는다.
     * 그동안 읽기 스레드는 들어오는 것을 버린다. 막히지 않으므로 어느 스레드에서 불러도 된다.
     */
    fun finish(notice: String? = null, pairFailed: Boolean = false) {
        val was = markEnded(onlyIfPairing = pairFailed) ?: return
        // 노트북이 결과를 기다리는 단계에서만 보낸다 (그 전 단계에서 보내면 노트북에는 순서 오류가 된다)
        if (pairFailed && was == Stage.PAIR_CONFIRM) send(Msg.PAIR_RESULT, byteArrayOf(0))
        if (notice != null) send(Msg.NOTICE, notice.toByteArray())
        sender.shutdown() // 넣어 둔 것은 마저 보내고 보내기 스레드가 끝난다
        Watchdog.close(link, CLOSE_GRACE_MS)
    }

    private fun flag(b: Boolean) = if (b) "1" else "0"

    /** 소켓 쓰기가 얼마나 막혀 있는지 잰다. FrameWriter가 잠금 안에서만 쓰므로 한 번에 한 스레드만 들어온다. */
    private class TimedOutput(private val out: OutputStream) : OutputStream() {
        @Volatile private var since = 0L

        fun blockedMs(): Long {
            val s = since
            return if (s == 0L) 0 else SystemClock.uptimeMillis() - s
        }

        override fun write(b: Int) = timed { out.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) = timed { out.write(b, off, len) }
        override fun flush() = timed { out.flush() }
        override fun close() = out.close()

        private inline fun timed(block: () -> Unit) {
            since = SystemClock.uptimeMillis()
            try {
                block()
            } finally {
                since = 0
            }
        }
    }

    companion object {
        const val READ_TIMEOUT_MS = 10_000
        const val PAIRING_TIMEOUT_MS = 120_000L
        const val DEFAULT_BITRATE = 8_000_000
        const val DEFAULT_MAX_SIZE = 1920
        private const val HELLO_DEADLINE_MS = 10_000L
        /** 페어링 시한(2분)보다 조금 길게: 정상이면 2분 시한이 먼저 안내를 보내고 끝낸다 */
        private const val PAIRING_DEADLINE_MS = PAIRING_TIMEOUT_MS + 10_000
        private const val WRITE_STUCK_MS = 5_000L
        private const val WRITE_CHECK_MS = 1_000L
        private const val SEND_QUEUE = 64
        private const val PREAUTH_PING_GAP_MS = 500L
        private const val MIN_BITRATE = 500_000L
        private const val MAX_BITRATE = 50_000_000L
        private const val CLOSE_GRACE_MS = 1500L
        private const val A11Y_NOTICE_GAP_MS = 2000L
        private val PAIRING = setOf(Stage.PAIR_COMMIT, Stage.PAIR_REVEAL, Stage.PAIR_CONFIRM)

        const val NOTICE_VERSION = "앱 버전이 달라 연결할 수 없습니다. 두 앱을 같은 버전으로 맞춰주세요."
        const val NOTICE_NOT_PAIRED =
            "이 노트북은 아직 페어링되지 않았습니다. 폰의 Linky 앱에서 [새 노트북 연결]을 누른 뒤 다시 연결하세요."
        const val NOTICE_REPLACED = "다른 노트북이 연결되었습니다"
        const val NOTICE_A11Y_OFF = "폰에서 Linky 접근성 권한이 꺼져 있습니다"
        const val NOTICE_PAIR_FAILED = "페어링 확인값이 맞지 않아 연결을 끊었습니다. 다시 시도하세요."
        const val NOTICE_ORDER = "통신 순서가 맞지 않아 연결을 끊었습니다."
        const val NOTICE_BUSY = "폰이 다른 노트북과 페어링하는 중입니다. 잠시 후 다시 연결하세요."
        const val NOTICE_STOPPED = "폰에서 화면 공유를 중지했습니다."
        const val NOTICE_UNPAIRED = "폰에서 이 노트북의 페어링을 삭제했습니다."
        const val NOTICE_ENCODER = "폰의 영상 인코더에 문제가 생겨 연결을 끊었습니다. 다시 연결해 보세요."
    }
}
