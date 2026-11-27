package org.tegro.yamp.data.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** D67: only time without new data counts; every byte that arrives starts the clock again. */
class LoadProgressTest {

    @Test
    fun aSlowLoadThatStillBringsDataNeverRunsOut() {
        val load = LoadProgress(startAtMs = 0L, startBufferedMs = 0L)
        var buffered = 0L
        for (second in 1..60) {
            buffered += 100 // a trickle: 0.1 s of audio per second
            assertEquals("at $second s", 0L, load.stalledFor(second * 1_000L, buffered))
        }
    }

    @Test
    fun noNewDataCountsFromTheLastArrival() {
        val load = LoadProgress(startAtMs = 0L, startBufferedMs = 0L)
        assertEquals(0L, load.stalledFor(5_000L, 2_000L))
        assertEquals(15_000L, load.stalledFor(20_000L, 2_000L))
        assertEquals(20_000L, load.stalledFor(25_000L, 2_000L))
    }
}
