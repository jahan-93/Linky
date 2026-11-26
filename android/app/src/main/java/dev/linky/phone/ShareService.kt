package dev.linky.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PointF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import dev.linky.protocol.Linky
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 화면 공유 포그라운드 서비스.
 * MediaProjection → VirtualDisplay(한 번만 만듦) → 세션이 있을 때만 H.264 인코더에 연결한다.
 * 공유하는 동안 TLS 서버와 mDNS 알림을 켜 두고, 인증된 세션은 하나만 유지한다.
 *
 * 화면·인코더·세션 전환은 모두 "capture" 스레드에서 한다 (설계서 6장).
 */
class ShareService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    private var captureThread: HandlerThread? = null
    private var capture: Handler? = null
    private var server: Server? = null
    private var advertiser: Advertiser? = null
    @Volatile private var stopping = false

    /** 시간 제한 같은 작은 지연 작업용. 여기서는 소켓에 쓰지 않는다 (쓰기는 연결마다 전용 스레드) */
    private val timer = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "linky-timer").apply { isDaemon = true } }.apply {
        executeExistingDelayedTasksAfterShutdownPolicy = false
        removeOnCancelPolicy = true
    }

    lateinit var trust: TrustStore
        private set
    lateinit var deviceName: String
        private set

    // ---- capture 스레드 전용 ----
    private var display: VirtualDisplay? = null
    // 화면 크기는 조작 좌표 계산에도 쓰므로 다른 스레드에서 읽는다
    @Volatile private var screenW = 0
    @Volatile private var screenH = 0
    private var dpi = 0
    @Volatile private var encoder: VideoEncoder? = null
    @Volatile private var active: Connection? = null
    /** 이 인코더가 처리하지 못한 크기를 피하는 긴 변 한도 (공유하는 동안 기억) */
    private var encoderCap = Int.MAX_VALUE

    // 페어링은 한 번에 하나
    private val pairLock = Any()
    private var pairingWith: Connection? = null
    /** 확인 창 띄우기와 지우기가 엇갈리지 않게 */
    private val promptLock = Any()

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        trust = TrustStore(this)
        deviceName = AppState.deviceName(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_SHARE, "화면 공유", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_PAIR, "페어링 요청", NotificationManager.IMPORTANCE_HIGH))
        current = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAIR_DECIDE -> {
                val id = intent.getIntExtra(EXTRA_ID, -1)
                AppState.pairing?.takeIf { it.id == id }?.connection?.decide(intent.getBooleanExtra(EXTRA_OK, false))
                if (projection == null) stopSelf() // 공유 중이 아닐 때 남은 알림을 누른 경우
                return START_NOT_STICKY
            }
        }
        if (projection != null) {
            // 이미 공유 중인데 시작 요청이 또 온 경우: startForegroundService 약속만 지킨다
            runCatching { startForeground(NOTIFY_SHARE, shareNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) }
            return START_NOT_STICKY
        }

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA)
        }
        if (data == null) {
            AppState.log("화면 공유 허락 정보가 없습니다")
            stopSelf()
            return START_NOT_STICKY
        }

        // 안드로이드 14부터: MediaProjection을 만들기 전에 포그라운드 서비스가 먼저 시작돼야 한다
        val mp = try {
            startForeground(NOTIFY_SHARE, shareNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
        } catch (e: Exception) {
            AppState.log("화면 공유를 시작하지 못했습니다: ${e.message ?: e.javaClass.simpleName}")
            null
        }
        if (mp == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        projection = mp
        startCapture(mp)
        AppState.sharing = true
        AppState.log("화면 공유 시작")
        AppState.changed()
        thread(name = "linky-accept", isDaemon = true) { runServer() }
        return START_NOT_STICKY
    }

    private fun startCapture(mp: MediaProjection) {
        val thread = HandlerThread("capture").apply { start() }
        captureThread = thread
        val h = Handler(thread.looper)
        capture = h
        h.post {
            try {
                // 안드로이드 14부터: 콜백을 먼저 등록해야 화면을 만들 수 있다
                val cb = object : MediaProjection.Callback() {
                    override fun onStop() {
                        AppState.log("화면 공유가 중지되었습니다")
                        main.post { stopSelf() }
                    }

                    // 안드로이드 14+: 회전 등으로 캡처 대상 크기가 바뀜
                    override fun onCapturedContentResize(width: Int, height: Int) = safely { checkScreenSize() }
                }
                projectionCallback = cb
                mp.registerCallback(cb, h)

                val (w, hh) = realSize()
                screenW = w
                screenH = hh
                dpi = resources.displayMetrics.densityDpi
                val (vw, vh) = VideoEncoder.fit(w, hh, Connection.DEFAULT_MAX_SIZE)
                // 한 번만 만든다. 보는 노트북이 없을 때는 surface 없이 둔다
                display = mp.createVirtualDisplay(
                    "linky", vw, vh, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, null, null, h,
                )

                // 안드로이드 13 이하용 회전 감지 (14+에서도 크기가 같으면 아무 일도 안 한다)
                val listener = object : DisplayManager.DisplayListener {
                    override fun onDisplayChanged(displayId: Int) {
                        if (displayId == Display.DEFAULT_DISPLAY) safely { checkScreenSize() }
                    }
                    override fun onDisplayAdded(displayId: Int) {}
                    override fun onDisplayRemoved(displayId: Int) {}
                }
                displayListener = listener
                getSystemService(DisplayManager::class.java).registerDisplayListener(listener, h)
                AppState.log("화면 ${w}x$hh 캡처 준비 완료")
            } catch (e: Exception) {
                AppState.log("화면 캡처를 준비하지 못했습니다: ${e.message ?: e.javaClass.simpleName}")
                main.post { stopSelf() }
            }
        }
    }

    /** 접속 받기 스레드: 인증서 준비 → 포트 열기 → mDNS 알림 → 접속 받기 */
    private fun runServer() {
        val srv = try {
            Server(this, AppState.identity(this))
        } catch (e: Exception) {
            AppState.log("노트북 연결을 준비하지 못했습니다 (포트 ${Linky.PORT}): ${e.message ?: e.javaClass.simpleName}")
            main.post { stopSelf() }
            return
        }
        synchronized(this) {
            if (stopping) {
                srv.close(null)
                return
            }
            server = srv
            advertiser = Advertiser(this).also { it.register(deviceName, srv.fingerprint) }
        }
        AppState.log("노트북 연결을 기다립니다 (포트 ${Linky.PORT})")
        srv.acceptLoop()
    }

    override fun onDestroy() {
        current = null
        val wasSharing = AppState.sharing
        val srv: Server?
        val adv: Advertiser?
        synchronized(this) {
            stopping = true
            srv = server
            adv = advertiser
            server = null
            advertiser = null
        }
        adv?.unregister()
        AppState.sharing = false
        AppState.pairing = null
        getSystemService(NotificationManager::class.java).apply {
            cancel(NOTIFY_PAIR)
            cancel(NOTIFY_PAIR_INTERRUPTED)
        }
        timer.shutdown()
        // 모든 연결에 안내를 보내고 잠시 뒤 닫는다 (쓰기와 닫기는 각 연결의 스레드가 하므로 막히지 않는다)
        srv?.close(Connection.NOTICE_STOPPED)

        // 화면·인코더 정리는 등록했던 capture 스레드에서 한다
        val teardown = Runnable {
            safely {
                displayListener?.let { getSystemService(DisplayManager::class.java).unregisterDisplayListener(it) }
                displayListener = null
                stopVideo()
                active = null
                display?.release()
                display = null
                projectionCallback?.let { cb -> projection?.unregisterCallback(cb) }
                projection?.stop()
                projection = null
            }
        }
        val h = capture
        if (h != null && captureThread?.isAlive == true) h.post(teardown) else teardown.run()
        captureThread?.quitSafely()
        LinkyAccessibilityService.instance?.streamer?.releaseAll()
        AppState.desktopName = null
        AppState.videoSize = null
        if (wasSharing) AppState.log("화면 공유 종료")
        AppState.changed()
        super.onDestroy()
    }

    // ---------- 세션 (capture 스레드) ----------

    /** 인증된 세션 시작. 이전 세션은 안내 후 닫는다 (설계서 1.6). */
    fun onAuthenticated(c: Connection) {
        val posted = onCapture {
            if (!c.isSession) return@onCapture
            when {
                stopping -> c.finish(Connection.NOTICE_STOPPED)
                // 연결한 뒤 페어링이 삭제됐을 수 있으므로 한 번 더 확인한다
                c.peerHex?.let(trust::contains) != true -> {
                    AppState.log("${c.peerName}: 페어링이 삭제된 노트북이라 끊습니다")
                    c.finish(Connection.NOTICE_UNPAIRED)
                }
                else -> startSession(c)
            }
        }
        if (!posted) c.finish(Connection.NOTICE_STOPPED)
    }

    private fun startSession(c: Connection) {
        val old = active
        active = c
        if (old != null) {
            AppState.log("이전 노트북(${old.peerName}) 연결을 끊습니다")
            old.finish(Connection.NOTICE_REPLACED)
        }
        stopVideo()
        LinkyAccessibilityService.instance?.streamer?.releaseAll()
        AppState.desktopName = c.peerName
        AppState.log("노트북 연결됨: ${c.peerName}")
        c.sendScreen(screenW, screenH)
        startVideo(c)
        updateNotification()
        AppState.changed()
    }

    fun onSessionEnded(c: Connection) {
        onCapture {
            if (active !== c) return@onCapture
            active = null
            stopVideo()
            LinkyAccessibilityService.instance?.streamer?.releaseAll()
            AppState.desktopName = null
            AppState.videoSize = null
            AppState.log("노트북 연결 끊김: ${c.peerName}")
            updateNotification()
            AppState.changed()
        }
    }

    fun onVideoSettings(c: Connection) {
        onCapture {
            if (active !== c) return@onCapture
            stopVideo()
            startVideo(c)
        }
    }

    fun requestKeyframe(c: Connection) {
        if (active === c) encoder?.requestKeyframe()
    }

    /** 조작을 받아도 되는 연결인지: 지금 활성 세션만 (설계서 1.6). */
    fun isActive(c: Connection): Boolean = !stopping && active === c

    /** 노트북이 보낸 영상 기준 좌표를 화면 기준 좌표로 (지금 영상 크기와 지금 화면 크기로 계산). */
    fun videoToScreen(nx: Float, ny: Float): PointF =
        encoder?.toScreen(nx, ny, screenW, screenH) ?: PointF(nx.coerceIn(0f, 1f), ny.coerceIn(0f, 1f))

    /** 페어링이 삭제된 노트북의 연결을 모두 끊는다. 진행 중인 것도 포함 (메인 스레드에서 불림). */
    fun onTrustRemoved(fp: String) {
        synchronized(this) { server }?.finishPeer(fp, Connection.NOTICE_UNPAIRED)
    }

    private fun checkScreenSize() {
        val (w, h) = realSize()
        if (w <= 0 || h <= 0 || (w == screenW && h == screenH)) return
        screenW = w
        screenH = h
        AppState.log("화면 크기 변경: ${w}x$h")
        val c = active ?: return
        c.sendScreen(w, h)
        stopVideo()
        startVideo(c)
    }

    private fun startVideo(c: Connection) {
        val d = display ?: return
        val limit = minOf(VideoEncoder.limitFor(screenW, screenH, c.maxSize), encoderCap)
        val enc = VideoEncoder.create(screenW, screenH, limit, c.bitrate, c) { e, early -> onEncoderFailed(c, e, early) }
        if (enc == null) {
            AppState.log("영상 인코더를 만들지 못했습니다")
            c.finish(Connection.NOTICE_ENCODER)
            return
        }
        // 더 큰 크기가 실패했으면 이번 공유 동안은 다시 시도하지 않는다
        if (enc.limit < limit) encoderCap = enc.limit
        try {
            d.resize(enc.width, enc.height, dpi)
            d.surface = enc.surface
        } catch (e: Exception) {
            AppState.log("화면을 인코더에 연결하지 못했습니다: ${e.message ?: e.javaClass.simpleName}")
            enc.stop()
            c.finish(Connection.NOTICE_ENCODER)
            return
        }
        encoder = enc
        enc.start()
        AppState.videoSize = "${enc.width}x${enc.height}"
        AppState.log("영상 시작: ${enc.width}x${enc.height}, ${c.bitrate / 1_000_000}Mbps")
    }

    /** 인코더 오류. 영상을 한 장도 내기 전에 실패했으면 더 작은 크기로 다시 시작한다. */
    private fun onEncoderFailed(c: Connection, enc: VideoEncoder, early: Boolean) {
        onCapture {
            if (encoder !== enc || active !== c) return@onCapture
            stopVideo()
            val smaller = if (early) VideoEncoder.smallerLimit(enc.limit) else null
            if (smaller != null) {
                encoderCap = smaller
                AppState.log("영상 크기를 줄여 다시 시작합니다 (긴 변 ${smaller}px)")
                startVideo(c)
            } else {
                c.finish(Connection.NOTICE_ENCODER)
            }
        }
    }

    /** 화면을 인코더에서 떼고(보는 사람이 없으면 인코딩하지 않음) 인코더를 멈춘다. */
    private fun stopVideo() {
        val enc = encoder ?: return
        encoder = null
        runCatching { display?.surface = null }
        enc.stop()
    }

    /** capture 스레드에서 한다. 스레드가 이미 끝났으면 false. */
    private fun onCapture(block: () -> Unit): Boolean = capture?.post { safely(block) } == true

    /** capture 스레드에서 오류가 나도 앱이 죽지 않게 한다 */
    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            AppState.log("화면 처리 오류: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** 실제 화면 크기(회전 반영). */
    private fun realSize(): Pair<Int, Int> {
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(m)
        return m.widthPixels to m.heightPixels
    }

    // ---------- 페어링 ----------

    fun beginPairing(c: Connection): Boolean = synchronized(pairLock) {
        if (pairingWith != null && pairingWith !== c) return false
        pairingWith = c
        true
    }

    fun endPairing(c: Connection) {
        synchronized(pairLock) { if (pairingWith === c) pairingWith = null }
        hidePrompt(c)
    }

    /** 앱 화면(보이면 대화상자)과 알림으로 숫자를 보여준다. 노트북 이름과 함께 노트북 ID도 보여준다. */
    fun showPrompt(c: Connection, sas: String) {
        val p = PairingPrompt(c.id, sas, c.peerName, c.peerId, c)
        val n = Notification.Builder(this, CHANNEL_PAIR)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("확인 숫자 ${p.code}")
            .setContentText("'${p.desktopName}' (ID ${p.desktopId}) 연결 요청 · 노트북에도 같은 숫자가 보이면 [일치]")
            .setStyle(Notification.BigTextStyle().bigText(
                "'${p.desktopName}'에서 연결을 요청했습니다.\n노트북 ID ${p.desktopId}\n" +
                    "노트북 화면에도 같은 숫자가 보이면 [일치]를 누르세요.",
            ))
            .setContentIntent(openApp())
            .setOngoing(true)
            .setTimeoutAfter(Connection.PAIRING_TIMEOUT_MS)
            .addAction(decideAction(c.id, true, "일치"))
            .addAction(decideAction(c.id, false, "다름"))
            .build()
        synchronized(promptLock) {
            AppState.pairing = p
            val nm = getSystemService(NotificationManager::class.java)
            nm.cancel(NOTIFY_PAIR_INTERRUPTED) // 이전 시도의 "중단" 알림은 지운다
            nm.notify(NOTIFY_PAIR, n)
        }
        AppState.changed()
    }

    fun hidePrompt(c: Connection) {
        synchronized(promptLock) {
            if (AppState.pairing?.connection !== c) return
            AppState.pairing = null
            getSystemService(NotificationManager::class.java).cancel(NOTIFY_PAIR)
        }
        AppState.changed()
    }

    /** 숫자를 보낸 뒤 페어링이 성공하지 못하고 끝났다. 창은 이미 닫혔으므로 다시 열라고 알린다. */
    fun onPairingInterrupted(desktopName: String) {
        if (stopping) return
        AppState.log("$desktopName: $PAIRING_INTERRUPTED")
        val n = Notification.Builder(this, CHANNEL_PAIR)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("페어링 중단")
            .setContentText(PAIRING_INTERRUPTED)
            .setStyle(Notification.BigTextStyle().bigText(PAIRING_INTERRUPTED))
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFY_PAIR_INTERRUPTED, n)
    }

    /** 확인 창마다 다른 요청 번호를 써서 앞 창의 버튼 정보가 섞이지 않게 한다. */
    private fun decideAction(id: Int, ok: Boolean, label: String): Notification.Action {
        val intent = Intent(this, ShareService::class.java)
            .setAction(ACTION_PAIR_DECIDE)
            .putExtra(EXTRA_ID, id)
            .putExtra(EXTRA_OK, ok)
        val pi = PendingIntent.getService(
            this, REQ_PAIR_BASE + id * 2 + (if (ok) 1 else 0), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(null, label, pi).apply {
            // 잠긴 화면에서는 누를 수 없다: 먼저 잠금을 풀어야 한다
            if (Build.VERSION.SDK_INT >= 31) setAuthenticationRequired(true)
        }.build()
    }

    // ---------- 알림 ----------

    private fun shareNotification(): Notification {
        val stop = PendingIntent.getService(
            this, REQ_STOP, Intent(this, ShareService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val desktop = AppState.desktopName
        return Notification.Builder(this, CHANNEL_SHARE)
            .setContentTitle("Linky 화면 공유 중")
            .setContentText(if (desktop != null) "연결된 노트북: $desktop" else "노트북 연결을 기다리는 중")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setContentIntent(openApp())
            .addAction(Notification.Action.Builder(null, "중지", stop).build())
            .build()
    }

    private fun updateNotification() {
        if (!stopping) getSystemService(NotificationManager::class.java).notify(NOTIFY_SHARE, shareNotification())
    }

    private fun openApp(): PendingIntent =
        PendingIntent.getActivity(this, REQ_OPEN, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    /** 백그라운드에서 실행한다 (시간 제한, 사용자 확인 처리). 소켓에 쓰는 일은 넣지 않는다. 멈추는 중이면 무시한다. */
    fun runLater(delayMs: Long = 0, task: () -> Unit): ScheduledFuture<*>? = try {
        timer.schedule(Runnable {
            try {
                task()
            } catch (e: Exception) {
                AppState.log("작업 오류: ${e.message ?: e.javaClass.simpleName}")
            }
        }, delayMs, TimeUnit.MILLISECONDS)
    } catch (e: RejectedExecutionException) {
        null
    }

    companion object {
        const val ACTION_STOP = "dev.linky.phone.STOP"
        const val ACTION_PAIR_DECIDE = "dev.linky.phone.PAIR_DECIDE"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_ID = "id"
        const val EXTRA_OK = "ok"
        private const val CHANNEL_SHARE = "share"
        private const val CHANNEL_PAIR = "pair"
        private const val NOTIFY_SHARE = 1
        private const val NOTIFY_PAIR = 2
        private const val NOTIFY_PAIR_INTERRUPTED = 3
        private const val REQ_STOP = 1
        private const val REQ_OPEN = 2
        private const val REQ_PAIR_BASE = 100
        private const val PAIRING_INTERRUPTED = "페어링이 중간에 끊겼습니다. 다시 하려면 [새 노트북 연결]을 누르세요"

        /** 지금 실행 중인 서비스 (앱 화면에서 페어링 삭제를 알릴 때) */
        @Volatile var current: ShareService? = null
            private set
    }
}
