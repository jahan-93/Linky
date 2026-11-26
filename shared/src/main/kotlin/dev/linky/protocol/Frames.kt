package dev.linky.protocol

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class Frame(val type: Int, val payload: ByteArray)

/** [길이 u32][종류 u8][내용] 프레임을 읽는다. 한 스레드에서만 읽을 것. */
class FrameReader(input: InputStream) {
    private val din = DataInputStream(BufferedInputStream(input, 64 * 1024))

    /**
     * 상대가 연결을 정상적으로 닫았으면 null.
     * maxLength: 인증 전에는 Linky.MAX_PREAUTH_FRAME을 넘겨서, 모르는 상대가 큰 메모리를 잡지 못하게 한다.
     */
    fun read(maxLength: Int = Linky.MAX_FRAME): Frame? {
        val length = try {
            din.readInt()
        } catch (e: EOFException) {
            return null
        }
        if (length < 1 || length > maxLength) throw IOException("잘못된 프레임 길이: $length (최대 $maxLength)")
        val type = din.readUnsignedByte()
        val payload = ByteArray(length - 1)
        din.readFully(payload)
        return Frame(type, payload)
    }
}

/** 프레임을 쓴다. 여러 스레드에서 동시에 불러도 프레임이 섞이지 않는다. */
class FrameWriter(output: OutputStream) {
    private val dout = DataOutputStream(BufferedOutputStream(output, 64 * 1024))
    private val lock = Any()

    fun write(type: Int, payload: ByteArray = EMPTY) = write(type, EMPTY, payload, 0, payload.size)

    /** 머리(header)와 큰 데이터(data)를 복사 없이 이어서 한 프레임으로 쓴다 (영상용). */
    fun write(type: Int, header: ByteArray, data: ByteArray, offset: Int, length: Int) {
        val total = 1 + header.size + length
        if (total > Linky.MAX_FRAME) throw IOException("프레임이 너무 큼: $total")
        synchronized(lock) {
            dout.writeInt(total)
            dout.writeByte(type)
            if (header.isNotEmpty()) dout.write(header)
            if (length > 0) dout.write(data, offset, length)
            dout.flush()
        }
    }

    companion object {
        val EMPTY = ByteArray(0)
    }
}
