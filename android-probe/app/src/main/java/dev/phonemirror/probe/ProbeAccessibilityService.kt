package dev.phonemirror.probe

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.RectF
import android.view.WindowInsets
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** 접근성 서비스: 제스처(탭·스와이프), 시스템 버튼, 글자 입력을 실행한다. */
class ProbeAccessibilityService : AccessibilityService() {

    private var inputThread: HandlerThread? = null

    /** 노트북의 실시간 드래그를 처리하는 엔진. 서비스가 연결돼 있는 동안만 존재한다. */
    @Volatile var streamer: GestureStreamer? = null
        private set

    override fun onServiceConnected() {
        val thread = HandlerThread("gesture-input").apply { start() }
        inputThread = thread
        streamer = GestureStreamer(this, Handler(thread.looper), { screenSize() }, { safeArea() })
        instance = this
        Probe.log("접근성 서비스 연결됨")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        Probe.log("접근성 서비스 해제됨")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        if (instance === this) instance = null
        streamer = null
        inputThread?.quitSafely()
        inputThread = null
    }

    /** 실제 화면 크기(픽셀). 회전하면 가로세로가 바뀐다. */
    fun screenSize(): Pair<Int, Int> {
        val wm = getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(m)
            m.widthPixels to m.heightPixels
        }
    }

    /**
     * 합성 스와이프를 시작해도 되는 영역(0~1 비율).
     * 상태바, 내비게이션바, 뒤로가기·홈 제스처 영역에서 시작하면 앱 대신 시스템이 반응하므로 피한다.
     */
    fun safeArea(): RectF {
        val (w, h) = screenSize()
        if (Build.VERSION.SDK_INT >= 30 && w > 0 && h > 0) {
            val margin = 16 * resources.displayMetrics.density
            val insets = getSystemService(WindowManager::class.java).maximumWindowMetrics.windowInsets
                .getInsets(WindowInsets.Type.systemGestures() or WindowInsets.Type.systemBars())
            return RectF(
                ((insets.left + margin) / w).coerceIn(0f, 0.3f),
                ((insets.top + margin) / h).coerceIn(0f, 0.3f),
                (1 - (insets.right + margin) / w).coerceIn(0.7f, 1f),
                (1 - (insets.bottom + margin) / h).coerceIn(0.7f, 1f),
            )
        }
        return RectF(0.08f, 0.08f, 0.92f, 0.90f)
    }

    fun tap(x: Float, y: Float, durationMs: Long = 50): Boolean =
        stroke(Path().apply { moveTo(x, y) }, durationMs, "탭")

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean =
        stroke(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, durationMs.coerceIn(50, 3000), "스와이프")

    /** 0~1 비율 좌표로 탭 (노트북 화면 크기와 상관없이 같은 위치). */
    fun tapRatio(nx: Float, ny: Float): Boolean {
        val (w, h) = screenSize()
        return tap(nx * w, ny * h)
    }

    fun swipeRatio(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val (w, h) = screenSize()
        return swipe(x1 * w, y1 * h, x2 * w, y2 * h, durationMs)
    }

    private fun stroke(path: Path, durationMs: Long, label: String): Boolean {
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) {
                Probe.gestureOk++
                Probe.log("$label 완료")
            }

            override fun onCancelled(d: GestureDescription?) {
                Probe.gestureCancelled++
                Probe.log("$label 취소됨")
            }
        }, null)
    }

    fun globalKey(name: String): Boolean {
        val action = when (name) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            else -> return false
        }
        return performGlobalAction(action)
    }

    /** 지금 커서가 있는 입력란 끝에 글자를 붙인다. */
    fun inputText(text: String): String {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return "입력란을 찾지 못했습니다 (폰에서 먼저 입력란을 탭하세요)"
        if (!node.isEditable) return "커서가 있는 곳이 입력란이 아닙니다"
        val showingHint = Build.VERSION.SDK_INT >= 26 && node.isShowingHintText
        val current = if (showingHint) "" else node.text?.toString().orEmpty()
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, current + text)
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        Probe.log(if (ok) "글자 입력: $text" else "글자 입력 실패")
        return if (ok) "입력됨" else "입력 실패"
    }

    companion object {
        @Volatile var instance: ProbeAccessibilityService? = null
    }
}
