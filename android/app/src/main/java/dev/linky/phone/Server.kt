package dev.linky.phone

import android.os.SystemClock
import dev.linky.protocol.Identity
import dev.linky.protocol.Linky
import dev.linky.protocol.Tls
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/**
 * TLS 서버 (설계서 1.1). 노트북도 인증서를 내야 한다(needClientAuth).
 * 만들 때 포트를 열고, acceptLoop()는 접속 받기 스레드에서 돈다. 연결마다 읽기 스레드를 하나 만든다.
 *
 * 일반 TCP로 받은 뒤 그 위에 TLS를 씌운다. TCP 소켓을 직접 쥐고 있어야 막힌 연결을 바로 끊을 수 있다 (Watchdog).
 */
class Server(private val service: ShareService, private val identity: Identity) {
    private val tlsFactory = Tls.context(identity).socketFactory
    private val socket = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(Linky.PORT))
    }
    private val connections: MutableSet<Connection> = ConcurrentHashMap.newKeySet()
    private var nextId = 0
    private var lastRefusedLog = 0L
    @Volatile private var closed = false

    val fingerprint: ByteArray get() = identity.fingerprint

    fun acceptLoop() {
        while (!closed) {
            val s = try {
                socket.accept()
            } catch (e: Exception) {
                if (closed) break
                AppState.log("접속 받기 오류: ${e.message ?: e.javaClass.simpleName}")
                Thread.sleep(ACCEPT_RETRY_MS)
                continue
            }
            // 연결 하나의 문제로 서버가 멈추지 않게 한다
            try {
                if (!accept(s)) break
            } catch (e: Exception) {
                AppState.log("접속 처리 오류: ${e.message ?: e.javaClass.simpleName}")
                Watchdog.abort(s)
            }
        }
    }

    /** 새 연결을 시작한다. 서버가 닫혔으면 false. */
    private fun accept(s: Socket): Boolean {
        if (tooMany(s)) {
            Watchdog.abort(s)
            return true
        }
        s.tcpNoDelay = true
        // 보내기 버퍼를 작게 둬서 느린 Wi-Fi에서 영상이 커널 안에 몇 초씩 쌓이지 않게 한다 (설계서 2.1)
        s.sendBufferSize = SEND_BUFFER_BYTES
        s.soTimeout = Connection.READ_TIMEOUT_MS
        val tls = (tlsFactory.createSocket(s, s.inetAddress.hostAddress, s.port, true) as SSLSocket).apply {
            useClientMode = false
            needClientAuth = true
            Tls.restrictProtocols(this)
            soTimeout = Connection.READ_TIMEOUT_MS
        }
        val c = Connection(service, Link(s, tls), identity, ++nextId)
        connections += c
        // close()가 연결 목록을 이미 돌았다면 여기서 끝낸다 (중지 뒤에 세션이 남지 않게)
        if (closed) {
            connections -= c
            c.finish()
            return false
        }
        thread(name = "linky-conn-${c.id}", isDaemon = true) {
            try {
                c.run()
            } finally {
                connections -= c
            }
        }
        return true
    }

    /**
     * 인증 전 연결 수 제한 (설계서 1.6): 전체 4개, 같은 IP에서 2개.
     * 활성 세션은 세지 않는다. 인증 전 연결을 여러 개 열어 진짜 노트북을 막는 것을 막는다.
     */
    private fun tooMany(s: Socket): Boolean {
        val waiting = connections.filter { it.isPreAuth }
        val fromSame = waiting.count { it.remoteAddress == s.inetAddress }
        if (waiting.size < MAX_PREAUTH && fromSame < MAX_PREAUTH_PER_IP) return false
        val now = SystemClock.uptimeMillis()
        if (now - lastRefusedLog >= REFUSED_LOG_GAP_MS) {
            lastRefusedLog = now
            AppState.log("인증 전 연결이 너무 많아 새 접속을 거절했습니다 (${s.inetAddress?.hostAddress})")
        }
        return true
    }

    /** 이 노트북(지문 hex)의 연결을 모두 끝낸다. 진행 중인 것도 포함 (설계서 1.5). */
    fun finishPeer(fp: String, notice: String) {
        for (c in connections) if (c.peerHex == fp) c.finish(notice)
    }

    /** 포트를 닫고 모든 연결에 안내를 보낸 뒤 끊는다. 막히지 않으므로 어느 스레드에서 불러도 된다. */
    fun close(notice: String?) {
        closed = true
        Watchdog.close(socket)
        for (c in connections) c.finish(notice)
    }

    private companion object {
        /** 인증 전 연결도 프레임 버퍼를 잡으므로 동시에 받는 수를 적게 둔다 */
        const val MAX_PREAUTH = 4
        const val MAX_PREAUTH_PER_IP = 2
        const val SEND_BUFFER_BYTES = 256 * 1024
        const val ACCEPT_RETRY_MS = 500L
        const val REFUSED_LOG_GAP_MS = 10_000L
    }
}
