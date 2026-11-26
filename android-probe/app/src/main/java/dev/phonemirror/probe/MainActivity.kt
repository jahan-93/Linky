package dev.phonemirror.probe

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** 0단계 점검 화면. 버튼 순서대로 눌러가며 확인한다. */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var preview: ImageView
    private lateinit var logView: TextView
    private lateinit var tapTarget: Button
    private var targetClicks = 0
    private var autoTapResult = "아직 안 함"
    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProbeServer.loadPage(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(24))
        }
        val scroll = ScrollView(this).apply { addView(root) }
        // 화면 위아래 상태바·내비게이션바에 내용이 가리지 않게
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(0, bars.top, 0, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            }
            insets
        }
        setContentView(scroll)

        root.addView(text("PhoneMirror 0단계 테스트", 22f, bold = true))
        root.addView(text("adb 없이 일반 앱 권한만으로 화면 보기와 조작이 되는지 확인합니다.", 14f, muted = true))

        status = text("", 15f).apply { typeface = Typeface.MONOSPACE; setPadding(0, dp(12), 0, dp(12)) }
        root.addView(status)

        root.addView(section("1. 접근성 서비스 (조작)"))
        root.addView(button("접근성 설정 열기") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(text("설치된 앱 → \"PhoneMirror 테스트 (조작)\"을 켜세요. 회색으로 막혀 있으면 아래 버튼으로 앱 정보에 들어가 ⋮ → \"제한된 설정 허용\"을 누른 뒤 다시 시도하세요.", 13f, muted = true))
        root.addView(button("앱 정보 열기 (제한된 설정 허용)") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })

        root.addView(section("2. 자동 탭 테스트"))
        root.addView(text("아래 [자동 탭 시작]을 누르면 1초 뒤 접근성 서비스가 [테스트 대상] 버튼을 스스로 누릅니다.", 13f, muted = true))
        root.addView(button("자동 탭 시작") { runAutoTap() })
        tapTarget = button("테스트 대상 (눌린 횟수: 0)") {
            targetClicks++
            tapTarget.text = "테스트 대상 (눌린 횟수: $targetClicks)"
        }.apply { setBackgroundColor(Color.rgb(60, 80, 140)); setTextColor(Color.WHITE) }
        root.addView(tapTarget)

        root.addView(section("3. 화면 캡처 + 노트북 브라우저"))
        root.addView(button("화면 캡처 시작") {
            if (Probe.capturing) toast("이미 캡처 중입니다. 다시 시작하려면 먼저 [캡처 중지]를 누르세요.")
            else requestCapture()
        })
        root.addView(button("캡처 중지") {
            startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
        })
        root.addView(text("캡처를 시작하면 위 상태에 노트북 브라우저 주소가 나옵니다. 같은 Wi-Fi의 노트북 크롬/엣지에서 그 주소를 여세요.", 13f, muted = true))
        preview = ImageView(this).apply {
            adjustViewBounds = true
            maxHeight = dp(320)
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(preview)

        root.addView(section("로그"))
        logView = text("", 12f, muted = true).apply { typeface = Typeface.MONOSPACE }
        root.addView(logView)
    }

    override fun onResume() {
        super.onResume()
        ui.post(tick)
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        super.onPause()
    }

    private fun refresh() {
        val a11y = ProbeAccessibilityService.instance != null
        val lines = listOf(
            "기기: ${Build.MANUFACTURER} ${Build.MODEL}",
            "안드로이드 ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "",
            "${mark(a11y)} 접근성 서비스: ${if (a11y) "켜짐" else "꺼짐"}",
            "   제스처 성공 ${Probe.gestureOk} / 취소 ${Probe.gestureCancelled}",
            "   자동 탭 테스트: $autoTapResult",
            "${mark(Probe.capturing)} 화면 캡처: ${if (Probe.capturing) "실행 중 (프레임 ${Probe.frames})" else "꺼짐"}",
            "   화면 전송: ${Frames.fps(SystemClock.elapsedRealtime())}장/초 · 보는 노트북 ${Probe.streamClients.get()}",
            "   실시간 드래그: 구간 ${Probe.dragSegments} · 취소 ${Probe.dragCancels} · 추가 지연 ${Math.round(Probe.segmentOverheadMs)}ms",
            "   노트북 주소: ${Probe.serverUrl ?: "(캡처를 시작하면 표시)"}",
        )
        status.text = lines.joinToString("\n")
        logView.text = Probe.recentLogs().asReversed().joinToString("\n")
        if (Probe.capturing) {
            // 미리보기는 작으니 절반 크기로 디코딩해서 화면 멈칫거림을 줄인다
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            Frames.latest()?.let { preview.setImageBitmap(BitmapFactory.decodeByteArray(it, 0, it.size, opts)) }
        } else {
            preview.setImageDrawable(null)
        }
    }

    private fun runAutoTap() {
        val service = ProbeAccessibilityService.instance
        if (service == null) {
            toast("접근성 서비스가 꺼져 있습니다. 1번부터 해주세요.")
            return
        }
        val before = targetClicks
        autoTapResult = "진행 중..."
        ui.postDelayed({
            val loc = IntArray(2)
            tapTarget.getLocationOnScreen(loc)
            val x = loc[0] + tapTarget.width / 2f
            val y = loc[1] + tapTarget.height / 2f
            val sent = service.tap(x, y)
            Probe.log("자동 탭 요청 (${x.toInt()}, ${y.toInt()}) 전송=${sent}")
            ui.postDelayed({
                autoTapResult = when {
                    targetClicks > before -> "✅ 성공 (버튼이 눌림)"
                    sent -> "❌ 제스처는 보냈지만 버튼이 안 눌림"
                    else -> "❌ 제스처 전송 실패"
                }
                Probe.log("자동 탭 결과: $autoTapResult")
            }, 1000)
        }, 1000)
    }

    private fun requestCapture() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
            return
        }
        launchCaptureConsent()
    }

    /**
     * 화면 공유 허락 창을 띄운다.
     * 안드로이드 14+에서는 "앱 하나만 공유"를 고를 수 없게 전체 화면으로 고정한다
     * (앱 하나만 공유하면 화면 좌표가 맞지 않고, 다른 앱으로 가면 화면이 안 보인다).
     */
    private fun launchCaptureConsent() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_CAPTURE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // 알림 권한을 거절해도 캡처는 할 수 있으므로 그대로 진행
        if (requestCode == REQ_NOTIFY) launchCaptureConsent()
    }

    @Deprecated("Activity 기본 API 사용")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            Probe.log("화면 캡처를 허락하지 않음")
            return
        }
        val intent = Intent(this, CaptureService::class.java)
            .putExtra(CaptureService.EXTRA_CODE, resultCode)
            .putExtra(CaptureService.EXTRA_DATA, data)
        startForegroundService(intent)
    }

    // ---------- 화면 조각 ----------

    private fun mark(ok: Boolean) = if (ok) "✅" else "❌"

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun text(s: String, size: Float, bold: Boolean = false, muted: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        if (bold) typeface = Typeface.DEFAULT_BOLD
        if (muted) setTextColor(Color.rgb(150, 156, 172))
    }

    private fun section(title: String) = text(title, 17f, bold = true).apply {
        setPadding(0, dp(20), 0, dp(6))
        setTextColor(Color.rgb(140, 170, 255))
    }

    private fun button(label: String, onClick: (View) -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        gravity = Gravity.CENTER
        setOnClickListener(onClick)
    }

    companion object {
        private const val REQ_CAPTURE = 1
        private const val REQ_NOTIFY = 2
    }
}
