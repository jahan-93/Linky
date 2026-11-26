package dev.phonemirror.probe

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * 아주 작은 WebSocket(RFC 6455) 서버 측 연결.
 * 노트북 브라우저 → 폰으로 조작 명령(JSON 텍스트)을 받는 데만 쓴다.
 */
class WsConnection(private val input: InputStream, private val out: OutputStream) {
    private val writeLock = Any()

    /** 연결이 닫힐 때까지 텍스트 메시지를 읽어 onText로 넘긴다. */
    fun readLoop(onText: (String) -> Unit) {
        val message = ByteArrayOutputStream()
        var messageOpcode = -1
        while (true) {
            val b0 = input.read()
            if (b0 < 0) return
            val b1 = readByte()
            val fin = (b0 and 0x80) != 0
            val opcode = b0 and 0x0F
            val masked = (b1 and 0x80) != 0
            var length = (b1 and 0x7F).toLong()
            if (length == 126L) {
                length = ((readByte() shl 8) or readByte()).toLong()
            } else if (length == 127L) {
                length = 0
                for (i in 0 until 8) length = (length shl 8) or readByte().toLong()
            }
            if (length < 0 || length > MAX_MESSAGE) throw IOException("메시지가 너무 큼: $length")
            // 제어 프레임(닫기·핑·퐁)은 나뉘지 않고 125바이트 이하여야 한다 (RFC 6455 5.5)
            if (opcode >= OP_CLOSE && (!fin || length > 125)) throw IOException("잘못된 제어 프레임")
            // 브라우저(클라이언트)가 보내는 프레임은 반드시 마스킹되어 있어야 한다
            if (!masked) throw IOException("마스킹되지 않은 프레임")
            val mask = readFully(4)
            val payload = readFully(length.toInt())
            for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()

            when (opcode) {
                OP_CONTINUATION, OP_TEXT, OP_BINARY -> {
                    if (opcode != OP_CONTINUATION) {
                        messageOpcode = opcode
                        message.reset()
                    } else if (messageOpcode < 0) {
                        throw IOException("시작 없는 이어지는 프레임")
                    }
                    message.write(payload)
                    if (message.size() > MAX_MESSAGE) throw IOException("메시지가 너무 큼")
                    if (fin) {
                        if (messageOpcode == OP_TEXT) onText(String(message.toByteArray(), Charsets.UTF_8))
                        message.reset()
                        messageOpcode = -1
                    }
                }
                OP_CLOSE -> {
                    runCatching { sendFrame(OP_CLOSE, payload.copyOf(minOf(payload.size, 2))) }
                    return
                }
                OP_PING -> sendFrame(OP_PONG, payload)
                OP_PONG -> Unit
                else -> throw IOException("알 수 없는 opcode $opcode")
            }
        }
    }

    fun sendText(text: String) = sendFrame(OP_TEXT, text.toByteArray(Charsets.UTF_8))

    /** 연결 확인용. 브라우저가 자동으로 퐁을 돌려보내므로, 노트북이 사라지면 읽기 시간 초과로 알 수 있다. */
    fun sendPing() = sendFrame(OP_PING, ByteArray(0))

    /** 이유 코드를 담아 닫기 프레임을 보낸다. (4001 = 다른 창이 조작권을 가져감) */
    fun sendClose(code: Int) = sendFrame(OP_CLOSE, byteArrayOf((code shr 8).toByte(), code.toByte()))

    private fun sendFrame(opcode: Int, payload: ByteArray) = synchronized(writeLock) {
        out.write(0x80 or opcode)
        val n = payload.size
        when {
            n < 126 -> out.write(n)
            n < 65536 -> {
                out.write(126)
                out.write(n shr 8)
                out.write(n and 0xFF)
            }
            else -> {
                out.write(127)
                for (i in 7 downTo 0) out.write(((n.toLong() shr (8 * i)) and 0xFF).toInt())
            }
        }
        out.write(payload)
        out.flush()
    }

    private fun readByte(): Int {
        val b = input.read()
        if (b < 0) throw EOFException()
        return b
    }

    private fun readFully(n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) throw EOFException()
            off += r
        }
        return buf
    }

    companion object {
        private const val OP_CONTINUATION = 0x0
        private const val OP_TEXT = 0x1
        private const val OP_BINARY = 0x2
        private const val OP_CLOSE = 0x8
        private const val OP_PING = 0x9
        private const val OP_PONG = 0xA
        private const val MAX_MESSAGE = 64 * 1024L
        private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

        /** 브라우저가 보낸 Sec-WebSocket-Key로 응답 키를 만든다. */
        fun acceptKey(clientKey: String): String {
            val sha1 = MessageDigest.getInstance("SHA-1").digest((clientKey.trim() + GUID).toByteArray(Charsets.US_ASCII))
            return Base64.encodeToString(sha1, Base64.NO_WRAP)
        }
    }
}
