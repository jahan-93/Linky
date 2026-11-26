package dev.linky.phone

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import dev.linky.protocol.Identity
import dev.linky.protocol.Pairing
import java.io.File
import java.net.Inet4Address
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * 폰 사용자에게 받을 페어링 확인 (앱 화면의 대화상자와 알림이 함께 본다).
 * desktopId: 노트북 인증서 지문 앞 16글자. 노트북 앱에 보이는 ID와 비교한다.
 */
class PairingPrompt(
    val id: Int,
    val sas: String,
    val desktopName: String,
    val desktopId: String,
    val connection: Connection,
) {
    /** 읽기 쉽게 "123 456" */
    val code: String get() = "${sas.take(3)} ${sas.drop(3)}"
}

/** 앱 전체가 함께 보는 상태와 로그. */
object AppState {
    @Volatile var sharing = false
    @Volatile var desktopName: String? = null
    @Volatile var videoSize: String? = null
    /** 보낸 영상 장 수 (화면에서 fps 계산) */
    val framesSent = AtomicLong()
    @Volatile var pairing: PairingPrompt? = null

    // ---- 새 노트북 연결 창 ----
    private const val PAIRING_WINDOW_MS = 2 * 60 * 1000L
    @Volatile private var pairingUntil = 0L

    fun openPairingWindow() {
        pairingUntil = SystemClock.elapsedRealtime() + PAIRING_WINDOW_MS
        log("새 노트북 연결 창을 2분 동안 엽니다")
    }

    fun closePairingWindow() {
        pairingUntil = 0
        changed()
    }

    fun pairingRemainingMs(): Long = (pairingUntil - SystemClock.elapsedRealtime()).coerceAtLeast(0)
    val pairingOpen: Boolean get() = pairingRemainingMs() > 0

    // ---- 실시간 드래그 통계 (GestureStreamer) ----
    @Volatile var dragSegments = 0
    @Volatile var dragCancels = 0
    /** 구간 하나를 보낸 뒤 끝났다는 응답이 오기까지 걸린 추가 시간(ms)의 평균. */
    @Volatile var segmentOverheadMs = 0.0

    fun recordSegment(overheadMs: Long) {
        dragSegments++
        val o = overheadMs.coerceAtLeast(0).toDouble()
        segmentOverheadMs = if (dragSegments == 1) o else segmentOverheadMs * 0.9 + o * 0.1
    }

    // ---- 화면 갱신 알림 (보이는 화면이 등록) ----
    private val main = Handler(Looper.getMainLooper())
    @Volatile var onChange: (() -> Unit)? = null

    fun changed() {
        main.post { onChange?.invoke() }
    }

    // ---- 로그 (최근 40줄, 한 줄 300자까지) ----
    private const val MAX_LOG_CHARS = 300
    private val logs = ArrayDeque<String>()
    private val time = SimpleDateFormat("HH:mm:ss", Locale.KOREA)

    fun log(msg: String) {
        // 상대가 보낸 값이 섞여도 메모리를 많이 잡지 않게 자른다
        val line = msg.take(MAX_LOG_CHARS)
        Log.i("Linky", line)
        synchronized(logs) {
            logs.addLast("${time.format(Date())}  $line")
            while (logs.size > 40) logs.removeFirst()
        }
    }

    fun recentLogs(): List<String> = synchronized(logs) { logs.toList() }

    // ---- 기기 정보 ----

    /** 사용자가 보는 기기 이름 (예: "Galaxy A16"). 없으면 모델명. */
    fun deviceName(context: Context): String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL

    @Volatile private var identity: Identity? = null

    /** 저장된 신원 파일을 읽지 못해 새로 만들었다면 사용자에게 보여줄 경고. 정상이면 null. */
    @Volatile var identityWarning: String? = null
        private set

    /** 이 폰의 ID (인증서 지문 앞 16글자). 노트북 화면의 ID와 비교한다. 신원을 아직 읽지 않았으면 null. */
    val phoneId: String? get() = identity?.let { Pairing.shortId(it.fingerprint) }

    /** 이 폰의 인증서 (처음이면 만든다. 시간이 걸릴 수 있어 메인 스레드에서 부르지 않는다). */
    fun identity(context: Context): Identity = identity ?: synchronized(this) {
        identity ?: Identity.loadOrCreate(File(context.filesDir, "identity.p12"), deviceName(context))
            .also { id ->
                id.recoveredFrom?.let { reason ->
                    val warning = "저장된 신원 파일을 읽지 못해 새로 만들었습니다. 기존 페어링을 다시 해야 합니다: $reason"
                    identityWarning = warning
                    log(warning)
                }
                identity = id
                changed()
            }
    }

    /**
     * Wi-Fi 네트워크의 IPv4 주소 (노트북에서 IP를 직접 입력할 때 쓴다).
     * 기본 네트워크가 아니라 Wi-Fi를 고른다: VPN을 켰거나 인터넷 없는 Wi-Fi라 모바일 데이터가 기본이어도 맞는 주소가 나온다.
     */
    fun wifiIp(context: Context): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        val networks = cm.allNetworks
        return networks
            .filter { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
            .flatMap { cm.getLinkProperties(it)?.linkAddresses.orEmpty() }
            .map { it.address }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    }
}
