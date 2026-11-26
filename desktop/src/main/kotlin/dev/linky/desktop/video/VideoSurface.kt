package dev.linky.desktop.video

import androidx.compose.ui.unit.IntSize
import org.bytedeco.javacpp.BytePointer
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode

/**
 * 해독한 최신 영상 한 장 (BGRA).
 * 해독 스레드가 Skia 비트맵 메모리에 직접 쓰고, UI 스레드가 그린다. 둘은 lock으로 나뉜다.
 * 비트맵은 크기가 바뀔 때만 새로 만든다.
 */
class VideoSurface(private val onFrame: () -> Unit) {
    private val lock = Any()
    private var bitmap: Bitmap? = null
    private var pixels: BytePointer? = null
    private var videoW = 0
    private var videoH = 0
    private var released = false

    /** UI가 알려주는 영상 영역 크기(px). 변환할 때 이 크기까지 줄여서 그릴 때는 1:1이 되게 한다. */
    @Volatile var viewport: IntSize = IntSize.Zero

    /**
     * 해독 스레드: 영상(srcW×srcH)을 변환할 대상 메모리를 빌려준다.
     * convert(대상, 줄 바이트 수, 너비, 높이)가 true를 돌려주면 새 장면으로 보여준다.
     */
    fun write(srcW: Int, srcH: Int, convert: (dst: BytePointer, stride: Int, w: Int, h: Int) -> Boolean) {
        synchronized(lock) {
            if (released) return
            val (w, h) = outputSize(srcW, srcH)
            val bmp = ensureBitmap(w, h) ?: return
            if (!convert(pixels ?: return, bmp.rowBytes, w, h)) return
            bmp.notifyPixelsChanged()
            videoW = srcW
            videoH = srcH
        }
        onFrame()
    }

    /** UI 스레드: 영역(areaW×areaH) 안에 비율을 유지해 그린다. */
    fun draw(canvas: Canvas, areaW: Float, areaH: Float) {
        synchronized(lock) {
            val bmp = bitmap ?: return
            val r = fitRect(areaW, areaH, videoW, videoH) ?: return
            // 가변 비트맵이라 복사본 이미지가 만들어진다. 그린 뒤 바로 닫아 프레임마다 새지 않게 한다.
            Image.makeFromBitmap(bmp).use { image ->
                canvas.drawImageRect(
                    image,
                    Rect.makeWH(bmp.width.toFloat(), bmp.height.toFloat()),
                    Rect.makeLTRB(r.left, r.top, r.right, r.bottom),
                    SamplingMode.LINEAR,
                    null,
                    true,
                )
            }
        }
    }

    /** 연결이 끝나면 UI 스레드에서 부른다. 이후의 write는 무시된다. */
    fun release() = synchronized(lock) {
        released = true
        pixels = null
        bitmap?.close()
        bitmap = null
    }

    /** 영역보다 크면 줄인다 (키우지는 않음). 너비는 4의 배수: 줄 길이를 16바이트 단위로 맞춰 swscale이 빠른 길을 쓰게. */
    private fun outputSize(srcW: Int, srcH: Int): Pair<Int, Int> {
        val vp = viewport
        val scale = if (vp.width <= 0 || vp.height <= 0) 1f
        else minOf(1f, vp.width / srcW.toFloat(), vp.height / srcH.toFloat())
        val w = ((srcW * scale).toInt() / 4 * 4).coerceAtLeast(4)
        val h = (srcH * scale).toInt().coerceAtLeast(2)
        return w to h
    }

    private fun ensureBitmap(w: Int, h: Int): Bitmap? {
        bitmap?.let { if (it.width == w && it.height == h) return it }
        pixels = null
        bitmap?.close()
        bitmap = null
        val bmp = Bitmap()
        if (!bmp.allocPixels(ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.OPAQUE))) {
            bmp.close()
            return null
        }
        val addr = bmp.peekPixels()?.use { it.addr } ?: 0L
        if (addr == 0L) {
            bmp.close()
            return null
        }
        bitmap = bmp
        pixels = NativeMemory(addr, bmp.rowBytes.toLong() * h)
        return bmp
    }

    /** 이미 있는 네이티브 메모리를 가리키는 포인터 (해제하지 않음, 주인은 비트맵). */
    private class NativeMemory(addr: Long, size: Long) : BytePointer() {
        init {
            address = addr
            capacity = size
            limit = size
        }
    }
}
