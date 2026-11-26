package dev.linky.desktop.input

import androidx.compose.ui.geometry.Offset
import dev.linky.protocol.Scroll
import dev.linky.protocol.Touch
import javax.swing.Timer

/** 조작 메시지를 받는 곳 (세션이 아니면 버린다). */
interface InputSink {
    fun touch(t: Touch)
    fun scroll(s: Scroll)
    fun key(code: Int)
}

/**
 * 마우스 → 폰 조작 (설계서 3장). 좌표는 영상 기준 0~1 비율. UI 스레드에서만 쓴다.
 * - 왼쪽 버튼 드래그 → TOUCH DOWN/MOVE/UP, 시각은 단조 증가 ms
 * - 휠은 60ms씩 모아 SCROLL, 드래그 중에는 보내지 않음
 * - 드래그 중에는 다른 버튼을 무시
 */
class MouseInput(private val sink: InputSink) {
    var dragging = false
        private set
    private var last = Offset.Zero
    private var lastTime = 0.0
    private var wheelSum = 0f
    private var wheelAt = Offset.Zero
    private val wheelTimer = Timer(WHEEL_MS) { flushWheel() }.apply { isRepeats = false }

    fun down(p: Offset) {
        if (dragging) return
        dragging = true
        wheelSum = 0f
        touch(Touch.DOWN, p, nowMs())
    }

    /** points: (비율 좌표, 시각 ms). historical 점까지 순서대로. */
    fun move(points: List<Pair<Offset, Double>>) {
        if (!dragging) return
        for ((p, t) in points) if (p != last) touch(Touch.MOVE, p, t)
    }

    fun up(p: Offset) {
        if (!dragging) return
        dragging = false
        touch(Touch.UP, p, nowMs())
    }

    /** 창 포커스를 잃거나 세션이 끝나면: 누르고 있던 손가락을 뗀다. */
    fun cancel() = up(last)

    fun key(code: Int) {
        if (!dragging) sink.key(code)
    }

    /** delta: 휠 칸 수 (+ = 아래로) */
    fun wheel(p: Offset, delta: Float) {
        if (dragging || delta == 0f) return
        wheelSum += delta
        wheelAt = p
        if (!wheelTimer.isRunning) wheelTimer.start()
    }

    private fun flushWheel() {
        val notches = wheelSum
        wheelSum = 0f
        if (notches != 0f && !dragging) sink.scroll(Scroll(wheelAt.x, wheelAt.y, notches))
    }

    private fun touch(action: Int, p: Offset, t: Double) {
        lastTime = maxOf(t, lastTime)
        last = p
        sink.touch(Touch(action, p.x, p.y, lastTime))
    }

    companion object {
        private const val WHEEL_MS = 60

        /** 단조 증가 시각 (ms) */
        fun nowMs(): Double = System.nanoTime() / 1_000_000.0
    }
}
