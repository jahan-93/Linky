package dev.linky.phone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.os.Handler
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 노트북에서 실시간으로 들어오는 누름/이동/뗌을 접근성 제스처로 이어 붙인다.
 *
 * - 짧은 구간(stroke, 최대 50ms)을 continueStroke로 연결해서 마우스를 따라 손가락이 움직이게 한다.
 * - 한 번에 한 구간만 보낸다. 끝나기 전에 새 제스처를 보내면 안드로이드가 앞의 것을 취소하기 때문이다.
 *   그동안 쌓인 이동은 다음 구간으로 보낸다.
 * - 구간을 주고받는 데 드는 추가 시간만큼 재생을 살짝 당겨서 지연이 쌓이지 않게 한다.
 * - 구간 사이에도 손가락은 눌린 상태로 유지된다. 마우스를 멈췄다가 놓으면 100ms 멈춘 뒤 떼서
 *   관성(플링)이 생기지 않는다. 빠르게 튕기면서 놓으면 진짜 손가락처럼 관성이 생긴다.
 *
 * 모든 상태는 handler 스레드에서만 다룬다.
 */
class GestureStreamer(
    private val service: AccessibilityService,
    private val handler: Handler,
    private val screenSize: () -> Pair<Int, Int>,
    /** 합성 스와이프(휠 스크롤)가 상태바·내비게이션바·제스처 영역에서 시작하지 않도록 하는 0~1 영역. */
    private val safeArea: () -> RectF,
) {
    private sealed interface Ev
    private class Down(val nx: Float, val ny: Float, val ms: Double) : Ev
    private class Move(val nx: Float, val ny: Float, val ms: Double) : Ev
    private class Up(val nx: Float, val ny: Float, val ms: Double) : Ev
    private class Scroll(var nx: Float, var ny: Float, var notches: Float) : Ev

    /** 다음 구간에 넣을 점. gapMs는 앞 점에서 이 점까지 걸린 시간. */
    private class Pt(val p: PointF, val gapMs: Double)

    private val queue = ArrayDeque<Ev>()

    private var stroke: StrokeDescription? = null
    private var inFlight = false
    private var last = PointF()
    private var lastMs = 0.0
    private var lastMoveMs = 0.0
    private var moved = false
    private var carryGapMs = 0.0
    private val pending = ArrayList<Pt>()
    private var upRequested = false
    private var upHoldMs = MIN_SEGMENT_MS
    private var width = 1
    private var height = 1
    private var dispatchedAt = 0L
    private var dispatchedDuration = 0L
    private var lastOverheadMs = 0L

    fun down(nx: Float, ny: Float, ms: Double) = post(Down(nx, ny, ms))
    fun move(nx: Float, ny: Float, ms: Double) = post(Move(nx, ny, ms))
    fun up(nx: Float, ny: Float, ms: Double) = post(Up(nx, ny, ms))

    /** 마우스 휠. 아직 시작하지 않은 스크롤이 있으면 합쳐서 한 번에 처리한다. */
    fun scroll(nx: Float, ny: Float, notches: Float) {
        if (notches == 0f || notches.isNaN()) return
        handler.post {
            val tail = queue.lastOrNull()
            if (tail is Scroll) {
                // 휠을 세게 돌려도 몇 초씩 밀리지 않게 쌓이는 양을 제한한다
                tail.notches = (tail.notches + notches).coerceIn(-MAX_QUEUED_NOTCHES, MAX_QUEUED_NOTCHES)
                tail.nx = nx
                tail.ny = ny
            } else {
                queue.addLast(Scroll(nx, ny, notches))
            }
            pump()
        }
    }

    /** 노트북 연결이 끊기거나 새로 연결되면, 누르고 있던 손가락을 멈춘 뒤 뗀다. */
    fun releaseAll() = handler.post {
        queue.clear()
        if (stroke != null && !upRequested) {
            upRequested = true
            upHoldMs = STOP_HOLD_MS
            flush()
        }
    }

    private fun post(ev: Ev) {
        handler.post {
            // 새로 누르면 아직 시작 안 한 휠 스크롤은 취소한다 (손가락으로 짚으면 스크롤이 멈추는 것처럼)
            if (ev is Down) queue.removeAll { it is Scroll }
            queue.addLast(ev)
            pump()
        }
    }

    /** 큐의 이벤트를 처리할 수 있는 만큼 처리하고, 쌓인 이동을 보낸다. */
    private fun pump() {
        loop@ while (queue.isNotEmpty()) {
            when (val ev = queue.first()) {
                is Down -> {
                    if (stroke != null) {
                        // 앞 드래그의 뗌이 빠졌다면(두 번째 클릭, 연결 꼬임) 먼저 뗀다. 교착 방지.
                        if (!upRequested) {
                            upRequested = true
                            upHoldMs = MIN_SEGMENT_MS
                        }
                        break@loop
                    }
                    queue.removeFirst()
                    start(ev.nx, ev.ny, ev.ms)
                }
                is Scroll -> {
                    if (stroke != null && !upRequested) {
                        // 드래그하는 도중의 휠은 버린다 (기다리면 드래그의 뗌이 뒤에 막혀 교착된다)
                        queue.removeFirst()
                        continue@loop
                    }
                    if (stroke != null) break@loop // 앞 스크롤/뗌이 끝나길 기다림
                    queue.removeFirst()
                    startScroll(ev)
                }
                is Move -> {
                    queue.removeFirst()
                    if (stroke != null && !upRequested) addPoint(ev.nx, ev.ny, ev.ms, capGap = true)
                }
                is Up -> {
                    queue.removeFirst()
                    if (stroke != null && !upRequested) {
                        val t = if (ev.ms.isNaN()) lastMs else ev.ms
                        addPoint(ev.nx, ev.ny, ev.ms, capGap = true)
                        val idle = t - lastMoveMs
                        upRequested = true
                        // 움직이다가 멈춘 뒤 놓았다면 잠깐 멈춘 상태로 떼서 관성이 생기지 않게 한다.
                        // 움직이면서 바로 놓았다면(튕기기) 짧게 떼서 진짜 손가락처럼 관성이 생긴다.
                        upHoldMs = if (moved && idle >= STOP_IDLE_MS) STOP_HOLD_MS else MIN_SEGMENT_MS
                    }
                }
            }
        }
        flush()
    }

    private fun start(nx: Float, ny: Float, ms: Double) {
        val (w, h) = screenSize()
        width = maxOf(1, w)
        height = maxOf(1, h)
        last = toPx(nx, ny)
        lastMs = if (ms.isNaN()) 0.0 else ms
        lastMoveMs = lastMs
        moved = false
        carryGapMs = 0.0
        pending.clear()
        upRequested = false
        upHoldMs = MIN_SEGMENT_MS
        val path = Path().apply { moveTo(last.x, last.y) }
        dispatch(StrokeDescription(path, 0, DOWN_MS, true), final = false)
    }

    /** 휠 한 칸 = 화면 높이의 8%. 안전 영역 안에서 움직인 뒤 잠깐 멈췄다 떼서 관성이 생기지 않게 한다. */
    private fun startScroll(ev: Scroll) {
        val n = ev.notches.coerceIn(-MAX_SCROLL_NOTCHES, MAX_SCROLL_NOTCHES)
        val rest = (ev.notches - n).coerceIn(-MAX_QUEUED_NOTCHES, MAX_QUEUED_NOTCHES)
        if (abs(rest) >= 0.2f) queue.addFirst(Scroll(ev.nx, ev.ny, rest)) // 많이 돌린 나머지는 다음 번에

        val safe = safeArea()
        val x = ev.nx.coerceIn(safe.left, safe.right)
        // 휠을 아래로(+) 돌리면 내용이 위로 올라가야 하므로 손가락은 위로 움직인다
        var startY = ev.ny.coerceIn(safe.top, safe.bottom)
        var endY = startY - n * SCROLL_STEP
        if (endY < safe.top) { startY += safe.top - endY; endY = safe.top }
        if (endY > safe.bottom) { startY -= endY - safe.bottom; endY = safe.bottom }
        startY = startY.coerceIn(safe.top, safe.bottom)

        val moveMs = SCROLL_BASE_MS + SCROLL_PER_NOTCH_MS * abs(n)
        start(x, startY, 0.0)
        // 중간 점을 여러 개 넣어서 구간(최대 50ms)으로 자연스럽게 나뉘게 한다
        val steps = maxOf(1, (moveMs / SCROLL_POINT_MS).roundToInt())
        for (i in 1..steps) {
            val f = i.toFloat() / steps
            addPoint(x, startY + (endY - startY) * f, moveMs * f, capGap = false)
        }
        upRequested = true
        upHoldMs = SCROLL_HOLD_MS
    }

    /** 정수 픽셀로 맞춘다. 이어지는 구간의 시작점이 앞 구간의 끝점과 정확히 같아야 하기 때문. */
    private fun toPx(nx: Float, ny: Float) = PointF(
        (nx.coerceIn(0f, 1f) * (width - 1)).roundToInt().toFloat(),
        (ny.coerceIn(0f, 1f) * (height - 1)).roundToInt().toFloat(),
    )

    /**
     * 이동 좌표를 다음 구간에 쌓는다.
     * capGap: 마우스를 멈췄다가 다시 움직이면 그 사이 시간이 길게 잡히는데, 멈춘 시간은 이미
     * 실제로 지나갔으므로(손가락은 눌린 채 멈춰 있었음) 다음 구간을 느리게 만들지 않도록 제한한다.
     */
    private fun addPoint(nx: Float, ny: Float, ms: Double, capGap: Boolean) {
        val p = toPx(nx, ny)
        val ref = pending.lastOrNull()?.p ?: last
        val t = if (ms.isNaN()) lastMs else ms
        var gap = (t - lastMs).coerceAtLeast(0.0) + carryGapMs
        if (capGap) gap = gap.coerceAtMost(MAX_POINT_GAP_MS)
        lastMs = maxOf(lastMs, t)
        if (p.x == ref.x && p.y == ref.y) {
            carryGapMs = gap // 같은 픽셀이면 점은 버리되 시간은 다음 점에 넘긴다
            return
        }
        carryGapMs = 0.0
        pending.add(Pt(p, gap))
        lastMoveMs = t
        moved = true
    }

    /** 보내던 구간이 없으면, 쌓인 이동(최대 50ms 분량) 또는 뗌을 다음 구간으로 보낸다. */
    private fun flush() {
        val s = stroke ?: return
        if (inFlight) return
        if (pending.isNotEmpty()) {
            var span = 0.0
            var count = 0
            while (count < pending.size) {
                val g = pending[count].gapMs
                if (count > 0 && span + g > MAX_SEGMENT_MS) break
                span += g
                count++
            }
            val chunk = pending.subList(0, count)
            val from = last
            val path = Path().apply {
                moveTo(from.x, from.y)
                for (pt in chunk) lineTo(pt.p.x, pt.p.y)
            }
            // 구간을 주고받는 추가 시간만큼 재생을 당겨서, 드래그가 길어져도 지연이 쌓이지 않게 한다
            val compensation = minOf(lastOverheadMs.toDouble(), span * 0.25)
            val duration = (span - compensation).toLong().coerceIn(MIN_SEGMENT_MS, MAX_SEGMENT_MS.toLong())
            last = chunk.last().p
            chunk.clear() // pending 앞부분 제거
            dispatch(s.continueStroke(path, 0, duration, true), final = false)
        } else if (upRequested) {
            val path = Path().apply { moveTo(last.x, last.y) }
            dispatch(s.continueStroke(path, 0, upHoldMs, false), final = true)
        }
    }

    private fun dispatch(next: StrokeDescription, final: Boolean) {
        stroke = next
        inFlight = true
        dispatchedAt = SystemClock.uptimeMillis()
        dispatchedDuration = next.duration
        val gesture = GestureDescription.Builder().addStroke(next).build()
        val sent = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                val overhead = SystemClock.uptimeMillis() - dispatchedAt - dispatchedDuration
                lastOverheadMs = overhead.coerceIn(0, 50)
                AppState.recordSegment(overhead)
                inFlight = false
                if (final) finish() else flush()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                AppState.dragCancels++
                AppState.log("드래그 구간 취소됨")
                finish()
            }
        }, handler)
        if (!sent) {
            AppState.log("제스처 전송 실패")
            finish()
        }
    }

    /** 한 드래그가 끝났다. 기다리던 다음 이벤트를 처리한다. */
    private fun finish() {
        stroke = null
        inFlight = false
        pending.clear()
        carryGapMs = 0.0
        upRequested = false
        pump()
    }

    private companion object {
        const val DOWN_MS = 8L
        const val MIN_SEGMENT_MS = 8L
        const val MAX_SEGMENT_MS = 50.0
        const val MAX_POINT_GAP_MS = 40.0
        /** 마지막 이동 후 이 시간 이상 지나서 놓으면 "멈춘 뒤 놓기"로 본다. */
        const val STOP_IDLE_MS = 30.0
        /** 멈춘 뒤 놓을 때 떼기 전에 멈춰 있는 시간. 안드로이드는 40ms, 크롬 웹뷰는 80ms 이상 멈추면 관성을 0으로 본다. */
        const val STOP_HOLD_MS = 100L
        const val SCROLL_STEP = 0.08f
        const val SCROLL_BASE_MS = 90.0
        const val SCROLL_PER_NOTCH_MS = 25.0
        const val SCROLL_POINT_MS = 16.0
        const val SCROLL_HOLD_MS = 120L
        const val MAX_SCROLL_NOTCHES = 6f
        const val MAX_QUEUED_NOTCHES = 12f
    }
}
