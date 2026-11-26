package dev.linky.phone

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.linky.protocol.Linky
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/** Linky 폰 앱 화면 (설계서 5장). 1초마다 상태를 새로 그린다. */
class MainActivity : Activity() {
    private lateinit var trust: TrustStore
    private lateinit var status: TextView
    private lateinit var warning: TextView
    private lateinit var shareButton: Button
    private lateinit var pairInfo: TextView
    private lateinit var trustedBox: LinearLayout
    private lateinit var logView: TextView
    private var shownTrusted: Map<String, String>? = null
    private var pairDialog: AlertDialog? = null
    private var pairDialogId = -1
    private var visible = false

    // 보내는 fps
    private var fps = 0
    private var lastFrames = 0L
    private var lastTick = 0L

    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            measureFps()
            refresh()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        trust = TrustStore(this)

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

        root.addView(text("Linky", 24f, bold = true))
        root.addView(text("노트북에서 이 폰 화면을 보고 조작합니다. 노트북과 같은 Wi-Fi에 연결하세요.", 14f, muted = true))
        warning = text("", 14f, bold = true).apply {
            setTextColor(Color.rgb(255, 110, 110))
            setPadding(0, dp(12), 0, 0)
            visibility = View.GONE
        }
        root.addView(warning)
        status = text("", 15f).apply { setPadding(0, dp(12), 0, dp(4)) }
        root.addView(status)

        root.addView(section("1. 조작 권한"))
        root.addView(button("접근성 설정 열기") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(text("설치된 앱 → \"Linky (조작)\"을 켜세요. 회색으로 막혀 있으면 아래 버튼으로 앱 정보에 들어가 ⋮ → \"제한된 설정 허용\"을 누른 뒤 다시 켜세요.", 13f, muted = true))
        root.addView(button("앱 정보 열기 (제한된 설정 허용)") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })

        root.addView(section("2. 화면 공유"))
        shareButton = button("화면 공유 시작") {
            if (AppState.sharing) {
                startService(Intent(this, ShareService::class.java).setAction(ShareService.ACTION_STOP))
            } else {
                requestShare()
            }
        }
        root.addView(shareButton)
        root.addView(text("공유하는 동안 노트북의 Linky 앱이 이 폰을 찾아 연결할 수 있습니다.", 13f, muted = true))

        root.addView(section("3. 노트북 연결"))
        root.addView(button("새 노트북 연결") {
            AppState.openPairingWindow()
            if (!AppState.sharing) toast("화면 공유를 시작해야 노트북이 연결할 수 있습니다.")
            refresh()
        })
        pairInfo = text("", 14f).apply { setPadding(0, dp(4), 0, dp(8)) }
        root.addView(pairInfo)
        root.addView(text("페어링된 노트북", 15f, bold = true))
        trustedBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(trustedBox)

        root.addView(section("로그"))
        logView = text("", 12f, muted = true).apply { typeface = Typeface.MONOSPACE }
        root.addView(logView)

        // 화면에 폰 ID를 보여주려고 신원을 미리 읽는다 (파일을 읽고 키를 만들 수 있어 메인 스레드 밖에서)
        val app = applicationContext
        thread(name = "linky-identity", isDaemon = true) {
            try {
                AppState.identity(app)
            } catch (e: Exception) {
                AppState.log("이 폰의 신원(인증서)을 준비하지 못했습니다: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        visible = true
        AppState.onChange = { if (visible) refresh() }
        lastTick = 0L
        ui.post(tick)
    }

    override fun onPause() {
        visible = false
        AppState.onChange = null
        ui.removeCallbacks(tick)
        dismissPairDialog()
        super.onPause()
    }

    private fun measureFps() {
        val now = SystemClock.elapsedRealtime()
        val frames = AppState.framesSent.get()
        if (lastTick > 0 && now > lastTick) fps = ((frames - lastFrames) * 1000 / (now - lastTick)).toInt()
        lastTick = now
        lastFrames = frames
    }

    private fun refresh() {
        val a11y = LinkyAccessibilityService.instance != null
        val sharing = AppState.sharing
        val desktop = AppState.desktopName
        val ip = AppState.wifiIp(this)
        status.text = listOf(
            "${mark(a11y)} 접근성(조작): ${if (a11y) "켜짐" else "꺼짐 (1번에서 켜세요)"}",
            "${mark(sharing)} 화면 공유: ${if (sharing) "공유 중" else "꺼짐"}",
            "${mark(desktop != null)} 연결된 노트북: ${desktop ?: "없음"}",
            "   보내는 화면: ${if (desktop != null) "$fps fps · ${AppState.videoSize ?: "-"}" else "-"}",
            "   폰 IP: ${if (ip != null) "$ip (포트 ${Linky.PORT})" else "Wi-Fi 연결 없음"}",
            "   폰 ID: ${AppState.phoneId ?: "준비 중"} (노트북 앱에 보이는 ID와 같아야 합니다)",
            "   드래그: 구간 ${AppState.dragSegments} · 취소 ${AppState.dragCancels} · 추가 지연 ${AppState.segmentOverheadMs.roundToInt()}ms",
        ).joinToString("\n")
        shareButton.text = if (sharing) "화면 공유 중지" else "화면 공유 시작"
        val problem = AppState.identityWarning
        warning.text = problem?.let { "⚠ $it" }.orEmpty()
        warning.visibility = if (problem != null) View.VISIBLE else View.GONE

        val left = AppState.pairingRemainingMs()
        pairInfo.text = if (left > 0) {
            val s = (left + 999) / 1000
            "페어링 창 열림 · 남은 시간 ${s / 60}:${"%02d".format(s % 60)}\n노트북 앱에서 이 폰을 골라 연결하세요."
        } else {
            "페어링 창 닫힘. 처음 연결하는 노트북이면 [새 노트북 연결]을 누르세요."
        }
        syncTrusted()
        syncPairDialog()
        logView.text = AppState.recentLogs().asReversed().joinToString("\n")
    }

    // ---------- 페어링된 노트북 목록 ----------

    private fun syncTrusted() {
        val now = trust.all()
        if (now == shownTrusted) return
        shownTrusted = now
        trustedBox.removeAllViews()
        if (now.isEmpty()) trustedBox.addView(text("아직 없습니다.", 13f, muted = true))
        for ((fp, name) in now.entries.sortedBy { it.value }) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(
                text("$name\nID ${fp.take(16)}", 14f),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            row.addView(button("삭제") { confirmRemove(fp, name) })
            trustedBox.addView(row)
        }
    }

    private fun confirmRemove(fp: String, name: String) {
        AlertDialog.Builder(this)
            .setTitle("페어링 삭제")
            .setMessage("'$name' 노트북의 페어링을 삭제할까요?\n다시 연결하려면 [새 노트북 연결]부터 해야 합니다.")
            .setPositiveButton("삭제") { _, _ ->
                trust.remove(fp)
                ShareService.current?.onTrustRemoved(fp)
                AppState.log("페어링 삭제: $name")
                refresh()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ---------- 페어링 확인 대화상자 ----------

    private fun syncPairDialog() {
        val p = AppState.pairing
        if (p == null || !visible) {
            dismissPairDialog()
            return
        }
        if (p.id == pairDialogId && pairDialog?.isShowing == true) return
        dismissPairDialog()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        body.addView(text("'${p.desktopName}'에서 연결을 요청했습니다.\n노트북 ID ${p.desktopId}", 15f))
        body.addView(text(p.code, 44f).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(16))
        })
        body.addView(text("노트북 화면에도 같은 숫자와 노트북 ID가 보이면 [일치]를 누르세요.", 13f, muted = true))
        pairDialogId = p.id
        pairDialog = AlertDialog.Builder(this)
            .setTitle("노트북 연결 확인")
            .setView(body)
            .setCancelable(false)
            .setPositiveButton("일치") { _, _ -> p.connection.decide(true) }
            .setNegativeButton("다름") { _, _ -> p.connection.decide(false) }
            .show()
    }

    private fun dismissPairDialog() {
        pairDialog?.dismiss()
        pairDialog = null
        pairDialogId = -1
    }

    // ---------- 화면 공유 시작 ----------

    private fun requestShare() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
            return
        }
        launchConsent()
    }

    /**
     * 화면 공유 허락 창. 안드로이드 14+에서는 "앱 하나만 공유"를 고를 수 없게 전체 화면으로 고정한다
     * (앱 하나만 공유하면 화면 좌표가 맞지 않는다).
     */
    private fun launchConsent() {
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
        // 알림을 거절해도 공유는 할 수 있다 (페어링 숫자는 앱 화면에서 확인)
        if (requestCode == REQ_NOTIFY) launchConsent()
    }

    @Deprecated("Activity 기본 API 사용")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            AppState.log("화면 공유를 허락하지 않았습니다")
            return
        }
        startForegroundService(
            Intent(this, ShareService::class.java)
                .putExtra(ShareService.EXTRA_CODE, resultCode)
                .putExtra(ShareService.EXTRA_DATA, data),
        )
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
        setOnClickListener(onClick)
    }

    companion object {
        private const val REQ_CAPTURE = 1
        private const val REQ_NOTIFY = 2
    }
}
