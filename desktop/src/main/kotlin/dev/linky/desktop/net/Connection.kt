package dev.linky.desktop.net

import dev.linky.desktop.Quality
import dev.linky.desktop.store.LocalDevice
import dev.linky.desktop.store.TrustedPhone
import dev.linky.desktop.video.H264Decoder
import dev.linky.desktop.video.VideoSurface
import dev.linky.protocol.Frame
import dev.linky.protocol.FrameReader
import dev.linky.protocol.FrameWriter
import dev.linky.protocol.Linky
import dev.linky.protocol.Msg
import dev.linky.protocol.Pairing
import dev.linky.protocol.Props
import dev.linky.protocol.Scroll
import dev.linky.protocol.Tls
import dev.linky.protocol.Touch
import dev.linky.protocol.VideoConfig
import dev.linky.protocol.VideoFrame
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

/**
 * 폰 하나와의 연결 (설계서 1장, 2.2).
 * - 받기 스레드: 연결, TLS, HELLO, 페어링, 영상 해독을 차례로 한다.
 * - 보내기 스레드: 모든 송신과 2초 PING. 한 스레드라 보낸 순서가 지켜지고 UI가 막히지 않는다.
 * 자동 연결(auto)은 페어링을 시작하지 않는다. 페어링은 사용자가 [연결]을 직접 눌렀을 때만 한다.
 */
class Connection(
    private val target: Target,
    private val device: LocalDevice,
    /** 처음 영상 설정. CLIENT_HELLO에 넣어 폰이 세션 시작 때 인코더를 한 번만 만들게 한다. */
    val quality: Quality,
    private val auto: Boolean,
    surface: VideoSurface,
    private val listener: ConnectionListener,
) {
    private enum class Stage { CONNECTING, HELLO, PAIR_NONCE, PAIR_RESULT, REJECTED, SESSION }

    /** 끝난 이유 (ConnectionListener.closed 참고) */
    private class End(val message: String?, val error: Boolean, val retryable: Boolean)

    private val sender = Executors.newSingleThreadScheduledExecutor { Thread(it, "Linky-보내기").apply { isDaemon = true } }
    private val decoder = H264Decoder(surface, listener::videoSize)

    @Volatile private var raw: Socket? = null
    @Volatile private var ssl: SSLSocket? = null
    @Volatile private var writer: FrameWriter? = null
    @Volatile private var closing = false
    @Volatile private var stage = Stage.CONNECTING
    @Volatile private var answered = false
    @Volatile private var confirmed = false
    @Volatile private var lastPingMs = Long.MIN_VALUE
    @Volatile private var helloTimedOut = false

    // ---- 받기 스레드 전용 ----
    private var peerFp = ByteArray(0)
    private var desktopTrusts = false
    private var phoneName = target.label
    private var nonce = ByteArray(0)
    private var lookalikeWarning: String? = null
    private var lastNoticeAt: Long? = null
    private var preAuthNoticeShown = false
    private var keyRequestAt: Long? = null
    private var statsAt = 0L
    private var statsBytes = 0L
    private var statsShown = 0L

    fun start() = Thread(::runLoop, "Linky-받기").apply { isDaemon = true }.start()

    /** 사용자가 끊음. 소켓 닫기는 막힐 수 있어 따로 한다. */
    fun close() {
        if (closing) return
        closing = true
        val s = ssl ?: raw ?: return
        Thread({ runCatching { s.close() } }, "Linky-닫기").apply { isDaemon = true }.start()
    }

    // ---- UI에서 부르는 송신 (세션 중일 때만 의미가 있다) ----
    fun sendTouch(t: Touch) = send(Msg.TOUCH, t.encode())
    fun sendScroll(s: Scroll) = send(Msg.SCROLL, s.encode())
    fun sendKey(code: Int) = send(Msg.KEY, byteArrayOf(code.toByte()))
    fun sendText(text: String) = send(Msg.TEXT, text.toByteArray(Charsets.UTF_8))
    fun sendVideoSettings(bitrate: Int, maxSize: Int) =
        send(Msg.VIDEO_SETTINGS, Props.encode(mapOf("bitrate" to "$bitrate", "maxSize" to "$maxSize")))

    /** 페어링 숫자에 대한 사용자 답 (1.4의 5단계). */
    fun confirmPairing(match: Boolean) {
        if (stage != Stage.PAIR_RESULT || answered) return
        answered = true
        confirmed = match
        send(Msg.PAIR_CONFIRM, byteArrayOf(if (match) 1 else 0))
    }

    private fun runLoop() {
        var end = End(null, error = false, retryable = false)
        try {
            readLoop(open())
            if (!closing) end = endedByPhone()
        } catch (e: Throwable) {
            // Error(FFmpeg를 못 불러옴, 메모리 부족 등)도 잡아서 사용자에게 이유를 보여준다
            if (!closing) end = failed(e)
        } finally {
            sender.shutdownNow()
            runCatching { raw?.close() }
            runCatching { decoder.release() }
            listener.closed(end.message, end.error, end.retryable)
        }
    }

    /** 폰이 연결을 닫음. 바로 앞에 NOTICE가 왔으면 그 안내가 이유다. */
    private fun endedByPhone(): End = when {
        helloTimedOut -> failed(IOException())
        noticedRecently() -> End("폰이 연결을 끊었습니다.", error = false, retryable = false)
        stage == Stage.REJECTED -> End(NOT_PAIRED_TEXT, error = true, retryable = false)
        else -> End("폰이 연결을 끊었습니다.", error = true, retryable = true)
    }

    private fun failed(e: Throwable): End = when {
        helloTimedOut -> End("폰이 10초 안에 응답하지 않았습니다. 폰의 Linky 앱이 화면 공유 중인지 확인하세요.", error = true, retryable = true)
        // 안내를 받은 직후나 거절을 기다리던 중에 끊긴 것은 폰이 닫은 것과 같다
        e is IOException && (stage == Stage.REJECTED || noticedRecently()) -> endedByPhone()
        e is IOException -> End(describe(e), error = true, retryable = true)
        else -> End(describe(e), error = true, retryable = false)
    }

    private fun noticedRecently() = lastNoticeAt?.let { nowMs() - it < NOTICE_GRACE_MS } == true

    /**
     * 1.1: 일반 소켓으로 5초 제한 연결 → TLS(클라이언트) → 지문 확인 → CLIENT_HELLO.
     * 폰은 CLIENT_HELLO를 받은 뒤에 HELLO를 보내므로 HELLO를 기다리지 않고 바로 보낸다.
     */
    private fun open(): SSLSocket {
        listener.log("${target.host}:${target.port}에 연결하는 중…")
        val context = device.tls
        val socket = Socket().also { raw = it }
        ensureOpen() // close()가 소켓이 생기기 전에 불렸으면 여기서 멈춘다
        socket.connect(InetSocketAddress(target.host, target.port), CONNECT_TIMEOUT_MS)
        socket.tcpNoDelay = true
        socket.soTimeout = READ_TIMEOUT_MS
        val tls = (context.socketFactory.createSocket(socket, target.host, target.port, true) as SSLSocket).also { ssl = it }
        tls.useClientMode = true
        Tls.restrictProtocols(tls)
        tls.startHandshake()
        ensureOpen()

        peerFp = Tls.peerFingerprint(tls)
        listener.log("보안 연결 완료 (${tls.session.protocol}), 폰 ID ${Pairing.shortId(peerFp)}")
        desktopTrusts = device.trust.contains(Pairing.hex(peerFp))
        if (!desktopTrusts) refuseLookalike(target.label)

        writer = FrameWriter(tls.outputStream)
        stage = Stage.HELLO
        listener.phase(ConnPhase.AUTH, phoneName)
        send(
            Msg.CLIENT_HELLO,
            Props.encode(
                mapOf(
                    "name" to device.name,
                    "version" to Linky.PROTOCOL_VERSION.toString(),
                    "trusted" to if (desktopTrusts) "1" else "0",
                    "bitrate" to quality.bitrate.toString(),
                    "maxSize" to quality.maxSize.toString(),
                ),
            ),
        )
        // 폰이 10초 안에 HELLO를 보내지 않으면 끊는다 (PONG 등을 보내도 연장되지 않는 절대 시한)
        sender.schedule({
            if (stage == Stage.HELLO) {
                helloTimedOut = true
                runCatching { raw?.close() }
            }
        }, HELLO_DEADLINE_MS, TimeUnit.MILLISECONDS)
        return tls
    }

    private fun ensureOpen() {
        if (closing) throw IOException("연결을 닫았습니다")
    }

    private fun readLoop(socket: SSLSocket) {
        val reader = FrameReader(socket.inputStream)
        while (!closing) {
            // 인증 전에는 작은 프레임만 받는다 (모르는 상대가 큰 메모리를 잡지 못하게)
            val limit = if (stage == Stage.SESSION) Linky.MAX_FRAME else Linky.MAX_PREAUTH_FRAME
            val frame = reader.read(limit) ?: return
            handle(frame)
        }
    }

    private fun handle(f: Frame) {
        when (f.type) {
            Msg.HELLO -> onHello(Props.decode(f.payload))
            Msg.PAIR_NONCE -> onPairNonce(f.payload)
            Msg.PAIR_RESULT -> onPairResult(f.payload)
            Msg.NOTICE -> onNotice(f.payload)
            Msg.PONG -> onPong(f.payload)
            Msg.SCREEN, Msg.VIDEO_CONFIG, Msg.VIDEO_FRAME -> if (stage == Stage.SESSION) onVideo(f)
            // 인증 전에는 조용히 버린다 (모르는 상대가 로그와 화면을 바쁘게 만들지 못하게)
            else -> if (stage == Stage.SESSION) listener.log("알 수 없는 메시지(종류 ${f.type})는 무시합니다")
        }
    }

    /** 1.2 ~ 1.3: 버전 확인, 판단 */
    private fun onHello(p: Map<String, String>) {
        expect(stage == Stage.HELLO, Msg.HELLO)
        phoneName = Props.cleanName(p["name"], phoneName)
        listener.log(
            "폰 정보: $phoneName, 모델 ${Props.cleanName(p["model"], "?")}, SDK ${p["sdk"].brief()}, 통신 버전 ${p["version"].brief()}",
        )
        if (p["version"] != Linky.PROTOCOL_VERSION.toString()) throw LinkyException(VERSION_TEXT)
        p["id"]?.let(::checkAnnouncedId)
        if (!desktopTrusts) refuseLookalike(phoneName)

        sender.scheduleAtFixedRate(
            {
                val t = nowMs()
                lastPingMs = t
                write(Msg.PING, ByteBuffer.allocate(8).putLong(t).array())
            },
            0, PING_INTERVAL_MS, TimeUnit.MILLISECONDS,
        )
        val phoneTrusts = p["trusted"] == "1"
        when {
            phoneTrusts && desktopTrusts -> startSession(paired = false)
            p["pairing"] == "1" -> {
                // 자동 연결로는 페어링을 시작하지 않는다
                if (auto) throw LinkyException(AUTO_STOPPED_TEXT)
                startPairing()
            }
            else -> {
                // 폰이 NOTICE로 안내한 뒤 연결을 닫는다. 폰이 닫지 않으면 잠시 뒤 노트북이 닫는다.
                stage = Stage.REJECTED
                listener.log("아직 페어링되지 않은 사이입니다. 폰의 안내를 기다립니다")
                sender.schedule({ runCatching { raw?.close() } }, REJECT_WAIT_MS, TimeUnit.MILLISECONDS)
            }
        }
    }

    /** 폰이 HELLO에 적은 자기 ID가 TLS로 받은 인증서와 다르면, 다른 기기가 중간에서 전달하고 있는 것이다. */
    private fun checkAnnouncedId(id: String) {
        val said = id.lowercase().filter { it in '0'..'9' || it in 'a'..'f' }.take(64)
        if (said.length >= 8 && Pairing.hex(peerFp).startsWith(said)) return
        throw LinkyException(
            "보안 경고: 폰이 알린 ID(${said.take(16)})와 실제로 연결된 기기의 ID(${Pairing.shortId(peerFp)})가 다릅니다. " +
                "다른 기기가 중간에서 연결을 가로채고 있을 수 있어 끊었습니다.",
        )
    }

    /**
     * 모르는 인증서를 내민 기기가 페어링한 폰과 닮았는지 본다.
     * - mDNS 지문 앞부분이 같다(강한 신호): 폰 인증서가 바뀌었거나 흉내 내는 것. 이 연결로는 절대 페어링하지 않고 끊는다.
     *   [페어링 삭제] 뒤 사용자가 [연결]을 직접 눌러야 다시 페어링할 수 있다.
     * - 이름이나 주소만 같다(약한 신호): 같은 이름의 다른 폰일 수 있어 막지 않고, 페어링 창에 경고를 띄운다.
     */
    private fun refuseLookalike(name: String) {
        val now = Pairing.shortId(peerFp)
        device.trust.lookalikeByNameOrHost(name, target.host)?.let { similar ->
            lookalikeWarning = "주의: 페어링한 ${similar.name}(ID ${similar.shortId})과 이름이나 주소가 같지만 다른 기기(ID $now)입니다. " +
                "폰 앱에 보이는 폰 ID가 $now 인지 꼭 확인하세요."
        }
        val known = device.trust.lookalikeByFp(target.advertisedFp) ?: return
        listener.log("경고: 페어링한 ${known.name}(ID ${known.shortId})과 닮았지만 ID가 다른 기기(ID $now)라 끊었습니다")
        throw LinkyException(
            "보안 경고: 폰 인증서가 바뀌었습니다 — 같은 폰이 맞는지 폰 앱에 표시된 ID와 비교하세요. " +
                "페어링한 ${known.name}의 ID는 ${known.shortId}, 지금 연결한 기기의 ID는 ${now}입니다. " +
                "같은 폰이 맞으면 [페어링 삭제]로 예전 페어링을 지운 뒤 [연결]을 직접 눌러 다시 페어링하세요.",
        )
    }

    /** 1.4: 노트북이 먼저 약속값을 보낸다 */
    private fun startPairing() {
        nonce = Pairing.nonce()
        stage = Stage.PAIR_NONCE
        listener.phase(ConnPhase.PAIRING, phoneName)
        listener.log("새 폰입니다. 숫자 비교 페어링을 시작합니다")
        send(Msg.PAIR_COMMIT, Pairing.commit(nonce))
    }

    private fun onPairNonce(phoneNonce: ByteArray) {
        expect(stage == Stage.PAIR_NONCE && phoneNonce.size == 32, Msg.PAIR_NONCE)
        stage = Stage.PAIR_RESULT
        send(Msg.PAIR_REVEAL, nonce)
        listener.pairingCode(
            Pairing.sas(peerFp, device.identity.fingerprint, phoneNonce, nonce),
            Pairing.shortId(peerFp),
            lookalikeWarning,
        )
    }

    private fun onPairResult(p: ByteArray) {
        expect(stage == Stage.PAIR_RESULT && p.isNotEmpty(), Msg.PAIR_RESULT)
        if (p[0].toInt() != 1) {
            throw LinkyException(
                if (answered && !confirmed) "페어링을 취소했습니다 (숫자가 다름)."
                else "페어링이 완료되지 않았습니다. 폰에서 [다름]을 눌렀거나 2분이 지났습니다. 다시 연결해 주세요.",
            )
        }
        // 노트북 사용자가 [일치]를 누르기 전에 성공이 올 수는 없다
        expect(confirmed, Msg.PAIR_RESULT)
        listener.log("페어링 완료: $phoneName")
        startSession(paired = true)
    }

    /** paired: 이 연결에서 방금 페어링함. 아니면 연결하는 사이 [페어링 삭제]를 눌렀는지 한 번 더 본다. */
    private fun startSession(paired: Boolean) {
        if (!paired && !device.trust.contains(Pairing.hex(peerFp))) throw LinkyException(FORGOTTEN_TEXT)
        stage = Stage.SESSION
        rememberPhone(paired)
        statsAt = nowMs()
        listener.phase(ConnPhase.SESSION, phoneName)
        listener.log("연결되었습니다: $phoneName")
    }

    /** 페어링을 저장하고, 세션마다 폰의 지금 이름과 주소를 기억한다 (인증서가 바뀐 폰을 알아보는 데 쓴다). */
    private fun rememberPhone(paired: Boolean) {
        val phone = TrustedPhone(Pairing.hex(peerFp), phoneName, target.host)
        val changed = try {
            if (paired) device.trust.put(phone) else device.trust.refresh(phone)
        } catch (e: IOException) {
            listener.log("페어링 정보를 저장하지 못했습니다: ${e.message}")
            true
        }
        if (changed) listener.trustChanged()
    }

    /** 문구는 300자까지. 인증 전에는 연결마다 한 번만 보여준다 (모르는 상대가 안내를 쏟아내지 못하게). */
    private fun onNotice(payload: ByteArray) {
        if (stage != Stage.SESSION) {
            if (preAuthNoticeShown) return
            preAuthNoticeShown = true
        }
        val text = String(payload, Charsets.UTF_8)
            .filter { it == '\n' || (!it.isISOControl() && Character.getType(it) != Character.FORMAT.toInt()) }
            .trim()
            .take(MAX_NOTICE)
        if (text.isEmpty()) return
        lastNoticeAt = nowMs()
        listener.log("폰 안내: $text")
        listener.notice(text)
    }

    /** 우리가 마지막으로 보낸 PING에 대한 답만 쓴다 (꾸며낸 PONG을 쏟아내도 화면이 바빠지지 않게). */
    private fun onPong(p: ByteArray) {
        if (p.size != 8 || ByteBuffer.wrap(p).long != lastPingMs) return
        listener.rtt(nowMs() - lastPingMs)
        publishStats()
    }

    /** 2.2: 받는 스레드에서 바로 해독 */
    private fun onVideo(f: Frame) {
        when (f.type) {
            Msg.SCREEN -> Props.decode(f.payload).let { listener.log("폰 화면 크기: ${it["width"].brief()}×${it["height"].brief()}") }
            Msg.VIDEO_CONFIG -> {
                val config = VideoConfig.decode(f.payload)
                decoder.configure(config)
                listener.log("영상 설정: ${config.width}×${config.height}")
            }
            Msg.VIDEO_FRAME -> {
                statsBytes += f.payload.size
                if (f.payload.size > VideoFrame.HEADER_SIZE && !decoder.decode(f.payload)) requestKeyframe()
                publishStats()
            }
        }
    }

    private fun requestKeyframe() {
        val now = nowMs()
        if (keyRequestAt?.let { now - it < KEY_REQUEST_GAP_MS } == true) return
        keyRequestAt = now
        send(Msg.REQUEST_KEYFRAME, FrameWriter.EMPTY)
        listener.log("영상을 복구하려고 키프레임을 요청했습니다")
    }

    private fun publishStats() {
        if (stage != Stage.SESSION) return
        val now = nowMs()
        val elapsed = now - statsAt
        if (elapsed < 1000) return
        val shown = decoder.shownFrames
        listener.stats((shown - statsShown) * 1000f / elapsed, statsBytes * 8 / elapsed)
        statsAt = now
        statsShown = shown
        statsBytes = 0
    }

    private fun send(type: Int, payload: ByteArray): Boolean = try {
        sender.execute { write(type, payload) }
        true
    } catch (_: RejectedExecutionException) {
        false
    }

    /** 보내기 스레드에서만 부른다. 실패하면 소켓을 닫아 받기 스레드가 정리하게 한다. */
    private fun write(type: Int, payload: ByteArray) {
        val w = writer ?: return
        if (closing) return // 사용자가 끊은 연결로는 더 보내지 않는다
        try {
            w.write(type, payload)
        } catch (e: IOException) {
            if (!closing) {
                listener.log("보내기 실패: ${e.message}")
                runCatching { raw?.close() }
            }
        }
    }

    private fun expect(ok: Boolean, type: Int) {
        if (!ok) throw LinkyException("폰이 순서에 맞지 않는 메시지(종류 $type)를 보내 연결을 끊었습니다.")
    }

    private fun describe(e: Throwable): String = when (e) {
        is LinkyException -> e.message.orEmpty()
        is SocketTimeoutException ->
            if (stage == Stage.CONNECTING) "연결 시간이 초과되었습니다. 폰과 노트북이 같은 Wi-Fi에 있는지 확인하세요."
            else "폰의 응답이 10초 동안 없어 연결을 끊었습니다."
        is ConnectException -> "폰에 연결할 수 없습니다. 폰의 Linky 앱에서 [화면 공유 시작]을 눌렀는지 확인하세요."
        is NoRouteToHostException -> "폰까지 가는 경로가 없습니다. 같은 Wi-Fi에 있는지 확인하세요."
        is UnknownHostException -> "주소를 찾을 수 없습니다: ${target.host}"
        is SSLException -> "보안 연결(TLS)에 실패했습니다: ${e.message}"
        is IOException -> "연결이 끊겼습니다: ${e.message}"
        is OutOfMemoryError -> "메모리가 부족해 연결을 끊었습니다."
        is LinkageError ->
            if (stage == Stage.SESSION) "영상 해독기(FFmpeg)를 불러오지 못했습니다: ${e.message}"
            else "필요한 구성 요소를 불러오지 못했습니다: $e"
        is IllegalArgumentException -> "폰이 잘못된 메시지를 보내 연결을 끊었습니다."
        else -> "연결 중 오류가 났습니다: ${e.message ?: e.javaClass.simpleName}"
    }

    /** 상대가 보낸 짧은 값(버전, 숫자 등)을 로그에 쓸 때 */
    private fun String?.brief() = Props.cleanName(this, "?").take(8)

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 10_000
        const val PING_INTERVAL_MS = 2_000L
        const val KEY_REQUEST_GAP_MS = 1_000L
        const val NOTICE_GRACE_MS = 3_000L
        const val REJECT_WAIT_MS = 5_000L
        const val HELLO_DEADLINE_MS = 10_000L
        const val MAX_NOTICE = 300

        const val VERSION_TEXT = "앱 버전이 달라 연결할 수 없습니다. 두 앱을 같은 버전으로 맞춰주세요."
        const val NOT_PAIRED_TEXT =
            "이 노트북은 아직 페어링되지 않았습니다. 폰의 Linky 앱에서 [새 노트북 연결]을 누른 뒤 다시 연결하세요."
        const val AUTO_STOPPED_TEXT = "자동 연결을 멈췄습니다. 페어링이 필요하면 [연결]을 직접 누르세요."
        const val FORGOTTEN_TEXT = "이 폰의 페어링을 삭제해서 연결하지 않았습니다."

        fun nowMs() = System.nanoTime() / 1_000_000
    }
}
