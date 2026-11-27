package org.tegro.yamp.data.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Upstream 1.14.8: with Bluetooth headphones the music fell silent in every track while the timer counted on and jumped
 * back 3–5 s, and the journal was empty. The watch is fed only with where the track stands — a position that moves is
 * the output taking sound — and tells both.
 */
class OutputWatchTest {
    private val watch = OutputWatch()
    private var clock = 10_000L
    private var position = 0L

    /** Plays [ms] in ticks; [moving] — the position goes on (the output takes the sound). */
    private fun play(ms: Long, moving: Boolean = true): List<OutputWatch.Finding> {
        val found = mutableListOf<OutputWatch.Finding>()
        var left = ms
        while (left > 0) {
            clock += OutputWatch.TICK_MS
            left -= OutputWatch.TICK_MS
            if (moving) position += OutputWatch.TICK_MS
            watch.tick(clock, playing = true, positionMs = position)?.let(found::add)
        }
        return found
    }

    @Test
    fun steadyMusicIsQuiet() {
        assertEquals(emptyList<OutputWatch.Finding>(), play(60_000))
    }

    @Test
    fun anOutputThatStopsTakingSoundIsToldWithHowLong() {
        play(5_000)
        val silentFrom = clock
        assertEquals(emptyList<OutputWatch.Finding>(), play(4_000, moving = false))
        val found = play(1_000)
        assertEquals(1, found.size)
        val stall = found.single() as OutputWatch.Finding.OutputStall
        assertEquals(silentFrom, stall.startedAtMs)
        assertEquals(4_000.0, stall.durationMs.toDouble(), 300.0)
    }

    @Test
    fun aShortBreathOfTheSinkIsNotAStall() {
        play(5_000)
        play(OutputWatch.STALL_MS - OutputWatch.TICK_MS, moving = false)
        assertEquals(emptyList<OutputWatch.Finding>(), play(2_000))
    }

    /** A start that has not sounded yet is the sink preparing, not a stall: nothing was ever taken. */
    @Test
    fun aStartThatHasNotSoundedIsNotAStall() {
        assertEquals(emptyList<OutputWatch.Finding>(), play(5_000, moving = false))
        assertEquals(emptyList<OutputWatch.Finding>(), play(2_000))
    }

    @Test
    fun theTimerJumpingBackIsTold() {
        play(10_000)
        position -= 4_000
        val found = play(OutputWatch.TICK_MS)
        assertEquals(1, found.size)
        assertEquals(
            (4_000 - OutputWatch.TICK_MS).toDouble(),
            (found.single() as OutputWatch.Finding.PositionBack).byMs.toDouble(),
            1.0
        )
    }

    @Test
    fun aSeekBackIsNotAJump() {
        play(10_000)
        watch.reset()
        position -= 8_000
        assertEquals(emptyList<OutputWatch.Finding>(), play(2_000))
    }

    @Test
    fun aPauseIsNotAStall() {
        play(5_000)
        clock += 20_000
        assertNull(watch.tick(clock, playing = false, positionMs = position))
        assertEquals(emptyList<OutputWatch.Finding>(), play(3_000))
    }
}
