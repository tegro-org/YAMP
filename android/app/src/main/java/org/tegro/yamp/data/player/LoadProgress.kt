package org.tegro.yamp.data.player

/**
 * How long a load has brought no new data (D67). Slowness never gives a track up: the clock only runs while the
 * buffered position stands still, and every byte that arrives starts it again. Pure; unit-tested.
 */
class LoadProgress(startAtMs: Long, startBufferedMs: Long) {
    private var lastProgressAt = startAtMs
    private var lastBuffered = startBufferedMs

    /** Milliseconds since the buffered position last moved, given the time [nowMs] and the position [bufferedMs]. */
    fun stalledFor(nowMs: Long, bufferedMs: Long): Long {
        if (bufferedMs > lastBuffered) {
            lastBuffered = bufferedMs
            lastProgressAt = nowMs
        }
        return nowMs - lastProgressAt
    }
}
