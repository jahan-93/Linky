package dev.phonemirror.probe

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import java.net.Inet4Address
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** 테스트 앱 전체가 함께 보는 상태. */
object Probe {
    // 예전 방식(한 번에 보내는 탭·스와이프) 통계
    @Volatile var gestureOk = 0
    @Volatile var gestureCancelled = 0

    // 화면 캡처
    @Volatile var frames = 0
    @Volatile var capturing = false
    @Volatile var captureSession = 0
    @Volatile var serverUrl: String? = null
    val streamClients = AtomicInteger()

    // 실시간 드래그 통계
    @Volatile var dragSegments = 0
    @Volatile var dragCancels = 0
    /** 구간 하나를 보낸 뒤 끝났다는 응답이 오기까지 걸린 추가 시간(ms)의 평균. */
    @Volatile var segmentOverheadMs = 0.0

    fun recordSegment(overheadMs: Long) {
        dragSegments++
        val o = overheadMs.coerceAtLeast(0).toDouble()
        segmentOverheadMs = if (dragSegments == 1) o else segmentOverheadMs * 0.9 + o * 0.1
    }

    /** 노트북 브라우저 접속용 비밀값. 앱을 켤 때마다 새로 만든다. */
    val token: String = ByteArray(4).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }

    private val logs = ArrayDeque<String>()
    private val time = SimpleDateFormat("HH:mm:ss", Locale.KOREA)

    fun log(msg: String) {
        Log.i("PhoneMirrorProbe", msg)
        synchronized(logs) {
            logs.addLast("${time.format(Date())}  $msg")
            while (logs.size > 40) logs.removeFirst()
        }
    }

    fun recentLogs(): List<String> = synchronized(logs) { logs.toList() }

    /** 현재 Wi-Fi의 IPv4 주소. */
    fun wifiIp(context: Context): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val props = cm.getLinkProperties(cm.activeNetwork) ?: return null
        return props.linkAddresses.map { it.address }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    }
}
