package dev.linky.phone

import java.io.Closeable
import java.net.Socket
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket

/**
 * 노트북 연결 하나의 소켓: TCP 소켓과 그 위의 TLS 소켓.
 *
 * 닫을 때는 TCP를 먼저 닫는다. TLS 소켓을 먼저 닫으면 종료 알림(close_notify)을 쓰느라 막힐 수 있다.
 * TCP를 닫으면 그 소켓에서 읽거나 쓰며 막혀 있던 스레드도 깨어나고, 뒤이은 TLS 닫기는 막히지 않는다.
 */
class Link(val tcp: Socket, val tls: SSLSocket) : Closeable {
    /** 이미 보낸 것은 상대에게 마저 간다. */
    override fun close() {
        runCatching { tcp.close() }
        runCatching { tls.close() }
    }

    /** 보내지 못한 것은 버리고 바로 끊는다 (linger 0: 남은 데이터를 보내려고 기다리지 않는다). */
    fun abort() {
        runCatching { tcp.setSoLinger(true, 0) }
        close()
    }
}

/**
 * 소켓을 닫는 일만 하는 스레드 (설계서 1.7, 6장).
 * 인증 시간 제한, 막힌 쓰기 끊기, 안내를 보낸 뒤 잠시 기다렸다 닫기를 모두 여기서 한다.
 * 소켓에 쓰지 않으므로 상대가 읽지 않아도 이 스레드는 막히지 않는다.
 */
object Watchdog {
    private val exec = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "linky-watchdog").apply { isDaemon = true } }
        .apply { removeOnCancelPolicy = true }

    /** delayMs 뒤에 닫는다. */
    fun close(target: Closeable, delayMs: Long = 0) {
        exec.schedule({ runCatching { target.close() } }, delayMs, TimeUnit.MILLISECONDS)
    }

    /** 접속 받자마자 거절할 때: 바로 끊는다. */
    fun abort(socket: Socket) {
        exec.execute {
            runCatching { socket.setSoLinger(true, 0) }
            runCatching { socket.close() }
        }
    }

    fun abort(link: Link) {
        exec.execute { link.abort() }
    }

    /** delayMs 뒤에 expired()가 참이면 끊는다. expired는 상태를 읽기만 해야 한다. */
    fun deadline(link: Link, delayMs: Long, expired: () -> Boolean): ScheduledFuture<*> =
        exec.schedule({ if (expired()) link.abort() }, delayMs, TimeUnit.MILLISECONDS)

    /** periodMs마다 stuck()을 보고 참이면 끊는다. stuck은 상태를 읽기만 해야 한다. */
    fun guard(link: Link, periodMs: Long, stuck: () -> Boolean): ScheduledFuture<*> =
        exec.scheduleWithFixedDelay({ if (stuck()) link.abort() }, periodMs, periodMs, TimeUnit.MILLISECONDS)
}
