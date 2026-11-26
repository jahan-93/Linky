package dev.phonemirror.probe

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 최신 화면 JPEG 한 장을 보관한다.
 * 새 화면이 나오면 기다리던 스트림 전송 스레드들을 깨운다.
 * 전송이 느린 노트북은 중간 화면을 건너뛰고 항상 최신 화면을 받는다 (지연이 쌓이지 않음).
 */
object Frames {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private var seq = 0L
    private var jpeg: ByteArray? = null
    private val publishTimes = ArrayDeque<Long>()

    fun publish(data: ByteArray, nowMs: Long) = lock.withLock {
        jpeg = data
        seq++
        publishTimes.addLast(nowMs)
        while (publishTimes.isNotEmpty() && nowMs - publishTimes.first() > 1000) publishTimes.removeFirst()
        changed.signalAll()
    }

    fun latest(): ByteArray? = lock.withLock { jpeg }

    /** lastSeq보다 새 화면이 나올 때까지 최대 timeoutMs 기다린다. 없으면 null. */
    fun awaitNewer(lastSeq: Long, timeoutMs: Long): Pair<Long, ByteArray>? = lock.withLock {
        var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        // 화면이 아직 없을 때(jpeg == null)도 기다린다 (바로 돌아가면 호출한 쪽이 헛돌게 된다)
        while ((seq <= lastSeq || jpeg == null) && remaining > 0) remaining = changed.awaitNanos(remaining)
        val current = jpeg
        if (seq > lastSeq && current != null) seq to current else null
    }

    /** 최근 1초 동안 만든 화면 수. */
    fun fps(nowMs: Long): Int = lock.withLock { publishTimes.count { nowMs - it <= 1000 } }

    fun clear() = lock.withLock {
        jpeg = null
        publishTimes.clear()
        changed.signalAll()
    }
}
