package org.tegro.yamp.data.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FadeCurveTest {

    @Test
    fun endPointsAreExact() {
        assertEquals(0f, FadeCurve.level(0f, 1f, 0.0), 0f)
        assertEquals(1f, FadeCurve.level(0f, 1f, 1.0), 0f)
        assertEquals(1f, FadeCurve.level(1f, 0f, 0.0), 0f)
        assertEquals(0f, FadeCurve.level(1f, 0f, 1.0), 0f)
        assertEquals(0.4f, FadeCurve.level(0.4f, 0f, 0.0), 0f)
    }

    @Test
    fun halfTimeIsMinus30Db() {
        val up = FadeCurve.level(0f, 1f, 0.5)
        val down = FadeCurve.level(1f, 0f, 0.5)
        assertEquals(-30.0, FadeCurve.toDb(up), 0.01)
        assertEquals(-30.0, FadeCurve.toDb(down), 0.01)
    }

    @Test
    fun fadesAreMonotonic() {
        var lastUp = -1f
        var lastDown = 2f
        for (i in 0..100) {
            val part = i / 100.0
            val up = FadeCurve.level(0f, 1f, part)
            val down = FadeCurve.level(1f, 0f, part)
            assertTrue(up >= lastUp)
            assertTrue(down <= lastDown)
            lastUp = up
            lastDown = down
        }
    }

    @Test
    fun aFadeContinuesFromTheCurrentLevel() {
        val mid = 0.25f // about -12 dB
        val start = FadeCurve.level(mid, 0f, 0.001)
        assertEquals(mid, start, 0.01f)
        // Half way from -12 dB to the -60 dB floor is -36 dB
        assertEquals(-36.04, FadeCurve.toDb(FadeCurve.level(mid, 0f, 0.5)), 0.05)
        assertEquals(1f, FadeCurve.level(mid, 1f, 1.0), 0f)
    }

    @Test
    fun silenceFloor() {
        assertEquals(FadeCurve.FLOOR_DB, FadeCurve.toDb(0f), 0.0)
        assertEquals(FadeCurve.FLOOR_DB, FadeCurve.toDb(0.0001f), 0.0)
        assertEquals(0f, FadeCurve.fromDb(FadeCurve.FLOOR_DB), 0f)
    }
}
