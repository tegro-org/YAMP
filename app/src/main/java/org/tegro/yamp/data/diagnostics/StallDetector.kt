package org.tegro.yamp.data.diagnostics

/**
 * Decides when a stalled main thread is worth a journal line, from timestamps alone (ms, any steady clock).
 * [update] is called about once a second with the current time and the last time the main thread answered a
 * tick. A stall of [thresholdMs] is reported once as [Event.Blocked]; while it goes on, [Event.StillBlocked]
 * follows every [repeatMs]; the first answer after a reported stall gives [Event.Recovered]. Stalls shorter
 * than [thresholdMs] produce nothing. Pure, unit-tested; not thread-safe (one watcher thread calls it).
 */
class StallDetector(
    private val thresholdMs: Long = THRESHOLD_MS,
    private val repeatMs: Long = REPEAT_MS
) {
    sealed interface Event {
        val seconds: Int

        data class Blocked(override val seconds: Int) : Event
        data class StillBlocked(override val seconds: Int) : Event
        data class Recovered(override val seconds: Int) : Event
    }

    /** The answer time the reported stall started from; null while nothing is reported. */
    private var stalledFrom: Long? = null
    private var lastReportAt = 0L

    fun update(now: Long, lastAnswer: Long): Event? {
        val from = stalledFrom
        if (from != null && lastAnswer != from) {
            stalledFrom = null
            return Event.Recovered(seconds(lastAnswer - from))
        }
        val stall = now - lastAnswer
        if (stall < thresholdMs) return null
        if (from == null) {
            stalledFrom = lastAnswer
            lastReportAt = now
            return Event.Blocked(seconds(stall))
        }
        if (now - lastReportAt < repeatMs) return null
        lastReportAt = now
        return Event.StillBlocked(seconds(stall))
    }

    private fun seconds(ms: Long) = (ms / 1000).toInt()

    companion object {
        /** The system's ANR limit for input is 5 s; the line is written a second before it. */
        const val THRESHOLD_MS = 4_000L
        const val REPEAT_MS = 5_000L
    }
}
