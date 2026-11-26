package dev.phonemirror.probe

import android.content.Context
import android.os.SystemClock
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * 0단계 테스트용 작은 HTTP 서버.
 * 노트북 브라우저에서 http://폰IP:8765/?t=토큰 으로 접속한다.
 *  - /stream : 화면을 연결 하나로 계속 보내는 MJPEG 스트림
 *  - /ws     : 실시간 조작 채널 (WebSocket, JSON 메시지)
 * ⚠️ 암호화가 없는 테스트용이므로 집 Wi-Fi에서만 쓰고, 다 쓰면 캡처를 중지할 것.
 */
class ProbeServer(private val port: Int) {
    @Volatile private var socket: ServerSocket? = null
    @Volatile private var stopped = false
    private val pool = Executors.newCachedThreadPool()
    private val pinger = Executors.newSingleThreadScheduledExecutor()
    private val clients: MutableSet<Socket> = ConcurrentHashMap.newKeySet()
    /** 조작 채널은 한 번에 하나만. 새로 연결되면 앞의 연결(끊겼을 수도 있는)을 닫는다. */
    private val activeWs = AtomicReference<Pair<Socket, WsConnection>?>(null)

    fun start() {
        thread(name = "probe-server", isDaemon = true) {
            val ss = try {
                ServerSocket().apply {
                    reuseAddress = true // 캡처를 빨리 껐다 켜도 포트를 바로 다시 쓸 수 있게
                    bind(InetSocketAddress(port))
                }
            } catch (e: Exception) {
                Probe.log("서버 시작 실패: ${e.message}")
                return@thread
            }
            socket = ss
            if (stopped) {
                runCatching { ss.close() }
                return@thread
            }
            Probe.log("테스트 서버 시작: 포트 $port")
            while (!ss.isClosed) {
                val client = try {
                    ss.accept()
                } catch (e: Exception) {
                    if (ss.isClosed) break else continue // 연결 하나의 오류로 서버 전체가 멈추지 않게
                }
                clients.add(client)
                try {
                    pool.execute { handle(client) }
                } catch (e: RejectedExecutionException) {
                    clients.remove(client)
                    runCatching { client.close() }
                }
            }
        }
    }

    fun stop() {
        stopped = true
        runCatching { socket?.close() }
        // 스트림·조작 채널처럼 오래 열려 있는 연결도 모두 닫는다
        for (c in clients) runCatching { c.close() }
        clients.clear()
        pinger.shutdownNow()
        pool.shutdownNow()
    }

    private fun handle(client: Socket) {
        try {
            client.use { serve(it) }
        } catch (_: Exception) {
            // 브라우저가 연결을 끊은 경우 등. 무시한다.
        } finally {
            clients.remove(client)
        }
    }

    private fun serve(s: Socket) {
        s.tcpNoDelay = true
        s.soTimeout = 5000
        val input = BufferedInputStream(s.getInputStream())
        val out = BufferedOutputStream(s.getOutputStream(), 64 * 1024)

        val requestLine = readLine(input) ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            if (headers.size > 64) return
        }

        val target = requestLine.split(' ').getOrNull(1) ?: "/"
        val path = target.substringBefore('?')
        val params = target.substringAfter('?', "")
            .split('&')
            .filter { it.isNotEmpty() }
            .associate { kv -> decode(kv.substringBefore('=')) to decode(kv.substringAfter('=', "")) }

        if (path == "/favicon.ico") return send(out, 404, "text/plain", ByteArray(0))
        if (params["t"] != Probe.token) {
            return send(out, 403, "text/plain; charset=utf-8", "토큰이 맞지 않습니다. 폰 앱에 표시된 주소로 접속하세요.".toByteArray())
        }

        when (path) {
            "/" -> send(out, 200, "text/html; charset=utf-8", page ?: "index.html 없음".toByteArray())
            "/stream" -> stream(s, out)
            "/ws" -> {
                val key = headers["sec-websocket-key"]
                if (headers["upgrade"]?.equals("websocket", ignoreCase = true) != true || key == null) {
                    send(out, 400, "text/plain", "WebSocket 요청이 아닙니다".toByteArray())
                } else {
                    websocket(s, input, out, key)
                }
            }
            "/frame.jpg" -> {
                val jpeg = Frames.latest()
                if (jpeg == null) send(out, 503, "text/plain", "아직 화면 없음".toByteArray())
                else send(out, 200, "image/jpeg", jpeg)
            }
            "/status" -> send(out, 200, "application/json; charset=utf-8", status().toByteArray())
            // 예전 방식(한 번에 보내는 조작) - 비교용으로 남겨 둔다
            "/tap" -> text(out, service { it.tapRatio(f(params, "x"), f(params, "y")).ok() })
            "/key" -> text(out, service { it.globalKey(params["k"].orEmpty()).ok() })
            "/text" -> text(out, service { it.inputText(params["v"].orEmpty()) })
            else -> send(out, 404, "text/plain", "없는 주소".toByteArray())
        }
    }

    // ---------- 화면 스트림 (MJPEG) ----------

    /**
     * 크롬·엣지는 MJPEG의 한 장을 "다음 장의 머리글"까지 받아야 화면에 그린다.
     * 그래서 한 장을 보낼 때마다 다음 장의 머리글까지 미리 보내서 바로 그려지게 한다.
     * (다음 장의 크기를 미리 알 수 없으므로 Content-Length는 쓰지 않는다. 브라우저는 경계로 장을 나눈다)
     */
    private fun stream(s: Socket, out: OutputStream) {
        s.soTimeout = 0
        // 보낼 버퍼를 작게 해서, Wi-Fi가 느릴 때 오래된 화면이 줄줄이 쌓이지 않게 한다
        runCatching { s.sendBufferSize = 128 * 1024 }
        out.write(
            ("HTTP/1.1 200 OK\r\n" +
                "Content-Type: multipart/x-mixed-replace; boundary=$BOUNDARY\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n\r\n").toByteArray(),
        )
        out.write(PART_HEADER)
        out.flush()
        Probe.streamClients.incrementAndGet()
        try {
            var lastSeq = -1L
            var idle = 0
            val session = Probe.captureSession
            while (Probe.capturing && Probe.captureSession == session && !s.isClosed) {
                val next = Frames.awaitNewer(lastSeq, 1000)
                val jpeg = if (next != null) {
                    lastSeq = next.first
                    idle = 0
                    next.second
                } else {
                    // 화면 변화가 없어도 2초마다 최신 화면을 다시 보내서, 떠난 브라우저를 알아채고 정리한다
                    if (++idle < 2) continue
                    idle = 0
                    Frames.latest() ?: continue
                }
                out.write(jpeg)
                out.write(PART_TRAILER_AND_NEXT_HEADER)
                out.flush()
            }
        } finally {
            Probe.streamClients.decrementAndGet()
        }
    }

    // ---------- 실시간 조작 (WebSocket) ----------

    private fun websocket(s: Socket, input: InputStream, out: OutputStream, key: String) {
        out.write(
            ("HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: ${WsConnection.acceptKey(key)}\r\n\r\n").toByteArray(),
        )
        out.flush()
        // 5초마다 핑을 보내고, 15초 동안 아무것도(퐁 포함) 안 오면 끊긴 것으로 본다
        s.soTimeout = 15_000
        val ws = WsConnection(input, out)
        val me = s to ws
        activeWs.getAndSet(me)?.let { (oldSocket, oldWs) ->
            // 밀려난 창에 이유를 알려서, 자동으로 다시 붙어 서로 빼앗는 일이 없게 한다
            runCatching { oldWs.sendClose(CLOSE_REPLACED) }
            runCatching { oldSocket.close() }
        }
        // 앞 연결에서 눌린 채 남은 손가락이 있으면 뗀다 (이 연결의 조작보다 먼저 처리됨)
        ProbeAccessibilityService.instance?.streamer?.releaseAll()
        val ping = pinger.scheduleAtFixedRate({
            try {
                ws.sendPing()
            } catch (e: Exception) {
                runCatching { s.close() }
            }
        }, 5, 5, TimeUnit.SECONDS)
        Probe.log("노트북 조작 채널 연결됨")
        try {
            ws.readLoop { text -> onControl(ws, text) }
        } finally {
            ping.cancel(false)
            // 드래그 중에 연결이 끊기면 손가락이 눌린 채로 남지 않게 뗀다
            if (activeWs.compareAndSet(me, null)) ProbeAccessibilityService.instance?.streamer?.releaseAll()
            Probe.log("노트북 조작 채널 끊김")
        }
    }

    /**
     * 조작 메시지 (JSON):
     *  {"t":"down|move|up","x":0~1,"y":0~1,"ms":브라우저 시각}
     *  {"t":"scroll","x":..,"y":..,"n":휠 칸 수(+는 아래로)}
     *  {"t":"key","k":"back|home|recents|notifications"}
     *  {"t":"text","v":"글자"}
     */
    private fun onControl(ws: WsConnection, text: String) {
        val m = try {
            JSONObject(text)
        } catch (_: JSONException) {
            return
        }
        val type = m.optString("t")
        val svc = ProbeAccessibilityService.instance
        val streamer = svc?.streamer
        if (svc == null || streamer == null) {
            // 이동 메시지마다 답하면 브라우저 로그가 넘치므로, 동작을 시작하는 메시지에만 알린다
            if (type == "down" || type == "scroll" || type == "key" || type == "text") {
                reply(ws, "접근성 서비스가 꺼져 있습니다")
            }
            return
        }
        val x = m.optDouble("x", 0.5).toFloat().let { if (it.isNaN()) 0.5f else it.coerceIn(0f, 1f) }
        val y = m.optDouble("y", 0.5).toFloat().let { if (it.isNaN()) 0.5f else it.coerceIn(0f, 1f) }
        val ms = m.optDouble("ms", Double.NaN)
        when (type) {
            "down" -> streamer.down(x, y, ms)
            "move" -> streamer.move(x, y, ms)
            "up" -> streamer.up(x, y, ms)
            "scroll" -> streamer.scroll(x, y, m.optDouble("n", 0.0).toFloat())
            "key" -> if (!svc.globalKey(m.optString("k"))) reply(ws, "버튼 실패: ${m.optString("k")}")
            "text" -> reply(ws, svc.inputText(m.optString("v")))
        }
    }

    private fun reply(ws: WsConnection, message: String) {
        runCatching { ws.sendText(JSONObject().put("t", "msg").put("m", message).toString()) }
    }

    private fun status(): String = JSONObject()
        .put("accessibility", ProbeAccessibilityService.instance != null)
        .put("capturing", Probe.capturing)
        .put("session", Probe.captureSession)
        .put("fps", Frames.fps(SystemClock.elapsedRealtime()))
        .put("viewers", Probe.streamClients.get())
        .put("segments", Probe.dragSegments)
        .put("dragCancels", Probe.dragCancels)
        .put("overheadMs", Math.round(Probe.segmentOverheadMs))
        .toString()

    // ---------- 도우미 ----------

    /** \r\n으로 끝나는 한 줄을 바이트 단위로 읽는다 (WebSocket 데이터를 미리 읽어버리지 않도록). */
    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else String(buf.toByteArray(), Charsets.UTF_8)
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
            if (buf.size() > 8192) return null
        }
        return String(buf.toByteArray(), Charsets.UTF_8)
    }

    private fun Boolean.ok() = if (this) "ok" else "실패"

    private fun service(block: (ProbeAccessibilityService) -> String): String =
        ProbeAccessibilityService.instance?.let(block) ?: "접근성 서비스가 꺼져 있습니다"

    private fun f(params: Map<String, String>, key: String) =
        params[key]?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.5f

    private fun decode(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    private fun text(out: OutputStream, body: String) =
        send(out, 200, "text/plain; charset=utf-8", body.toByteArray())

    private fun send(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val status = when (code) {
            200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"; else -> "Service Unavailable"
        }
        out.write(
            ("HTTP/1.1 $code $status\r\n" +
                "Content-Type: $type\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n\r\n").toByteArray(),
        )
        out.write(body)
        out.flush()
    }

    companion object {
        private const val BOUNDARY = "pmframe"
        private const val CLOSE_REPLACED = 4001
        private val PART_HEADER = "--$BOUNDARY\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray()
        private val PART_TRAILER_AND_NEXT_HEADER = "\r\n--$BOUNDARY\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray()

        /** assets/index.html 내용. 앱 시작 때 읽어 둔다. */
        @Volatile var page: ByteArray? = null

        fun loadPage(context: Context) {
            if (page == null) page = context.assets.open("index.html").use { it.readBytes() }
        }
    }
}
