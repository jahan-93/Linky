package dev.phonemirror.probe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import java.io.ByteArrayOutputStream

/**
 * 화면 캡처(MediaProjection) 포그라운드 서비스.
 * 캡처한 화면을 JPEG로 만들어 Frames에 올리고, 노트북 브라우저용 테스트 서버도 함께 띄운다.
 *
 * 화면 관련 객체(reader, display, 비트맵)는 모두 "capture" 스레드에서만 다룬다.
 * (메인 스레드에서 닫으면 인코딩 중인 화면 버퍼가 사라져 앱이 죽을 수 있다)
 */
class CaptureService : Service() {
    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var worker: HandlerThread? = null
    private var handler: Handler? = null
    private var server: ProbeServer? = null
    private var displayListener: DisplayManager.DisplayListener? = null

    // ---- 아래는 capture 스레드 전용 ----
    private var held: Image? = null
    private var encodeScheduled = false
    private var lastEncode = 0L
    private var bitmap: Bitmap? = null
    private val jpegOut = ByteArrayOutputStream(256 * 1024)
    private var curW = 0
    private var curH = 0
    private var dpi = 0
    private val encodeTask = Runnable {
        encodeScheduled = false
        encodeHeld()
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (projection != null) return START_NOT_STICKY

        val resultCode = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA)
        }
        if (data == null) {
            Probe.log("캡처 허락 정보가 없습니다")
            stopSelf()
            return START_NOT_STICKY
        }

        // 안드로이드 14부터: MediaProjection을 만들기 전에 포그라운드 서비스가 먼저 시작돼야 한다
        startInForeground()

        val mp = getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, data)
        if (mp == null) {
            Probe.log("MediaProjection 생성 실패")
            stopSelf()
            return START_NOT_STICKY
        }
        projection = mp

        val thread = HandlerThread("capture").apply { start() }
        worker = thread
        val h = Handler(thread.looper)
        handler = h

        // 안드로이드 14부터: 콜백을 먼저 등록해야 화면을 만들 수 있다
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                Probe.log("화면 공유가 중지됨")
                stopSelf()
            }

            // 안드로이드 14+: 화면 회전 등으로 캡처 대상 크기가 바뀜
            override fun onCapturedContentResize(width: Int, height: Int) {
                resizeFor(width, height)
            }
        }
        projectionCallback = callback
        mp.registerCallback(callback, h)

        val (rw, rh) = realSize()
        val (w, hh) = fit(rw, rh)
        curW = w
        curH = hh
        dpi = resources.displayMetrics.densityDpi
        val r = newReader(w, hh, h)
        reader = r
        display = mp.createVirtualDisplay(
            "phone-mirror-probe", w, hh, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, h,
        )

        // 안드로이드 13 이하용 회전 감지 (14+에서도 같은 크기면 아무 일도 안 하므로 함께 둔다)
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY) realSize().let { (a, b) -> resizeFor(a, b) }
            }
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
        }
        displayListener = listener
        getSystemService(DisplayManager::class.java).registerDisplayListener(listener, h)

        Probe.capturing = true
        Probe.captureSession++
        Probe.frames = 0
        server = ProbeServer(PORT).also { it.start() }
        Probe.serverUrl = Probe.wifiIp(this)?.let { "http://$it:$PORT/?t=${Probe.token}" }
        Probe.log("캡처 시작 ${w}x$hh")
        return START_NOT_STICKY
    }

    private fun newReader(w: Int, h: Int, handler: Handler): ImageReader =
        // 하나를 들고 있는 동안에도 최신 화면까지 받아올 수 있게 3장
        ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3).apply {
            setOnImageAvailableListener({ onImage(it) }, handler)
        }

    /** 실제 화면 크기(회전 반영). */
    private fun realSize(): Pair<Int, Int> {
        val d = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        d.getRealMetrics(m)
        return m.widthPixels to m.heightPixels
    }

    /**
     * 긴 변을 1280px 이하로 줄인 캡처 크기.
     * 가로를 32의 배수로 맞추면 버퍼 줄 끝에 여백이 생기지 않아, 매 화면마다 잘라내는 복사를 피할 수 있다.
     */
    private fun fit(rw: Int, rh: Int): Pair<Int, Int> {
        val scale = minOf(1f, MAX_LONG_SIDE / maxOf(rw, rh))
        val w = maxOf(32, ((rw * scale).toInt() / 32) * 32)
        val h = maxOf(2, ((w.toLong() * rh / rw).toInt() / 2) * 2)
        return w to h
    }

    /** 화면이 회전하면 캡처 크기를 바꾼다. (capture 스레드에서 호출됨) */
    private fun resizeFor(contentW: Int, contentH: Int) {
        val d = display ?: return
        val h = handler ?: return
        if (contentW <= 0 || contentH <= 0) return
        val (w, hh) = fit(contentW, contentH)
        if (w == curW && hh == curH) return
        val nr = newReader(w, hh, h)
        d.resize(w, hh, dpi)
        d.surface = nr.surface
        held?.close()
        held = null
        reader?.setOnImageAvailableListener(null, null)
        reader?.close()
        reader = nr
        curW = w
        curH = hh
        Probe.log("화면 크기 변경: ${w}x$hh")
    }

    /**
     * 새 화면이 나올 때마다 호출 (capture 스레드).
     * 최신 화면 하나만 들고 있다가 최대 초당 약 30장으로 인코딩한다.
     * 너무 빨리 온 화면도 버리지 않고 잠시 뒤 인코딩해서, 움직임이 끝난 마지막 화면이 반드시 전달되게 한다.
     */
    private fun onImage(r: ImageReader) {
        if (r !== reader) return
        // 먼저 받아보고, 새 화면이 있을 때만 들고 있던 화면을 바꾼다
        // (앞 콜백이 이미 가져가 null이면, 들고 있던 마지막 화면을 그대로 둬야 한다)
        val image = try {
            r.acquireLatestImage()
        } catch (e: IllegalStateException) {
            null
        } ?: return
        held?.close()
        held = image
        Probe.frames++
        if (encodeScheduled) return
        val wait = lastEncode + MIN_FRAME_INTERVAL_MS - SystemClock.elapsedRealtime()
        if (wait > 0) {
            encodeScheduled = true
            handler?.postDelayed(encodeTask, wait)
        } else {
            encodeHeld()
        }
    }

    private fun encodeHeld() {
        val image = held ?: return
        held = null
        try {
            lastEncode = SystemClock.elapsedRealtime()
            val w = image.width
            val h = image.height
            val plane = image.planes[0]
            val paddedWidth = plane.rowStride / plane.pixelStride
            // 같은 크기 비트맵을 계속 재사용한다
            val full = bitmap?.takeIf { it.width == paddedWidth && it.height == h }
                ?: Bitmap.createBitmap(paddedWidth, h, Bitmap.Config.ARGB_8888).also {
                    bitmap?.recycle()
                    bitmap = it
                }
            val buffer = plane.buffer
            buffer.rewind()
            full.copyPixelsFromBuffer(buffer)
            val bmp = if (paddedWidth != w) Bitmap.createBitmap(full, 0, 0, w, h) else full
            jpegOut.reset()
            bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, jpegOut)
            Frames.publish(jpegOut.toByteArray(), lastEncode)
            if (bmp !== full) bmp.recycle()
        } catch (e: IllegalStateException) {
            // 회전·종료 중에 화면 버퍼가 닫힌 경우. 이 화면만 건너뛴다.
        } finally {
            runCatching { image.close() }
        }
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "화면 캡처", NotificationManager.IMPORTANCE_LOW),
        )
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("PhoneMirror 테스트")
            .setContentText("화면을 노트북으로 보내는 중")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "중지", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        Probe.capturing = false
        Probe.serverUrl = null
        server?.stop()
        server = null
        displayListener?.let { getSystemService(DisplayManager::class.java).unregisterDisplayListener(it) }
        displayListener = null

        // 화면 객체 정리는 capture 스레드에서, 진행 중인 인코딩이 끝난 뒤에 한다
        val teardown = Runnable {
            handler?.removeCallbacks(encodeTask)
            reader?.setOnImageAvailableListener(null, null)
            held?.close()
            held = null
            display?.release()
            display = null
            reader?.close()
            reader = null
            projectionCallback?.let { cb -> projection?.unregisterCallback(cb) }
            projection?.stop()
            projection = null
            bitmap?.recycle()
            bitmap = null
            Frames.clear()
        }
        val h = handler
        if (h != null && worker?.isAlive == true) h.post(teardown) else teardown.run()
        worker?.quitSafely()
        Probe.log("캡처 종료")
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "dev.phonemirror.probe.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val PORT = 8765
        private const val MAX_LONG_SIDE = 1280f
        private const val MIN_FRAME_INTERVAL_MS = 30L
        private const val JPEG_QUALITY = 60
        private const val CHANNEL = "capture"
        private const val NOTIFICATION_ID = 1
    }
}
