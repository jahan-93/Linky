package dev.linky.protocol

import java.nio.ByteBuffer

/** 터치. x, y는 영상 화면 기준 0~1 비율, timeMs는 노트북 쪽 시각(ms, 간격 계산용). */
data class Touch(val action: Int, val x: Float, val y: Float, val timeMs: Double) {
    fun encode(): ByteArray = ByteBuffer.allocate(SIZE)
        .put(action.toByte()).putFloat(x).putFloat(y).putDouble(timeMs).array()

    companion object {
        const val DOWN = 0
        const val MOVE = 1
        const val UP = 2
        private const val SIZE = 1 + 4 + 4 + 8

        fun decode(b: ByteArray): Touch {
            require(b.size >= SIZE) { "TOUCH 길이 오류" }
            val buf = ByteBuffer.wrap(b)
            return Touch(buf.get().toInt() and 0xFF, buf.float, buf.float, buf.double)
        }
    }
}

/** 마우스 휠. notches는 칸 수 (+ = 아래로 굴림 = 내용이 위로 올라감). */
data class Scroll(val x: Float, val y: Float, val notches: Float) {
    fun encode(): ByteArray = ByteBuffer.allocate(12).putFloat(x).putFloat(y).putFloat(notches).array()

    companion object {
        fun decode(b: ByteArray): Scroll {
            require(b.size >= 12) { "SCROLL 길이 오류" }
            val buf = ByteBuffer.wrap(b)
            return Scroll(buf.float, buf.float, buf.float)
        }
    }
}

/** 영상 설정: 인코더가 만든 SPS/PPS(Annex-B)와 영상 크기. */
class VideoConfig(val width: Int, val height: Int, val codecConfig: ByteArray) {
    fun encode(): ByteArray = ByteBuffer.allocate(8 + codecConfig.size)
        .putInt(width).putInt(height).put(codecConfig).array()

    companion object {
        fun decode(b: ByteArray): VideoConfig {
            require(b.size >= 8) { "VIDEO_CONFIG 길이 오류" }
            val buf = ByteBuffer.wrap(b)
            val w = buf.int
            val h = buf.int
            return VideoConfig(w, h, b.copyOfRange(8, b.size))
        }
    }
}

/**
 * 영상 한 장: [pts u64 µs][flags u8][H.264 Annex-B 데이터].
 * 보낼 때는 header()와 데이터를 FrameWriter.write(type, header, data, ...)로 복사 없이 보낸다.
 */
object VideoFrame {
    const val FLAG_KEY = 1
    const val HEADER_SIZE = 9

    fun header(ptsUs: Long, key: Boolean): ByteArray =
        ByteBuffer.allocate(HEADER_SIZE).putLong(ptsUs).put(if (key) FLAG_KEY.toByte() else 0).array()

    fun ptsUs(payload: ByteArray): Long = ByteBuffer.wrap(payload, 0, 8).long
    fun isKey(payload: ByteArray): Boolean = (payload[8].toInt() and FLAG_KEY) != 0
    /** 실제 H.264 데이터는 payload[HEADER_SIZE ..] */
}

/**
 * 간단한 키=값 묶음 (HELLO 같은 작은 정보용). UTF-8, 한 줄에 하나.
 * 값 안의 \ 와 줄바꿈은 \\ , \n 으로 바꿔 저장한다.
 */
object Props {
    /** HELLO 같은 Props 프레임의 최대 크기 */
    const val MAX_BYTES = 4096

    /**
     * 상대가 보낸 이름을 화면·로그에 쓰기 전에 정리한다.
     * 제어 문자와 보이지 않는 서식 문자(글자 방향 바꾸기 등)를 빼고 40글자로 자른다.
     */
    fun cleanName(raw: String?, fallback: String): String = raw.orEmpty()
        .filter { !it.isISOControl() && Character.getType(it) != Character.FORMAT.toInt() }
        .trim()
        .take(40)
        .ifBlank { fallback }

    fun encode(map: Map<String, String>): ByteArray = map.entries.joinToString("\n") { (k, v) ->
        require('=' !in k && '\n' !in k) { "키에 = 또는 줄바꿈 불가: $k" }
        "$k=" + v.replace("\\", "\\\\").replace("\n", "\\n")
    }.toByteArray(Charsets.UTF_8)

    fun decode(b: ByteArray): Map<String, String> = String(
        b.also { require(it.size <= MAX_BYTES) { "Props가 너무 큼: ${it.size}" } },
        Charsets.UTF_8,
    )
        .split('\n')
        .filter { '=' in it }
        .associate { line ->
            val k = line.substringBefore('=')
            val raw = line.substringAfter('=')
            val v = StringBuilder()
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                if (c == '\\' && i + 1 < raw.length) {
                    v.append(if (raw[i + 1] == 'n') '\n' else raw[i + 1])
                    i += 2
                } else {
                    v.append(c)
                    i++
                }
            }
            k to v.toString()
        }
}
