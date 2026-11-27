package org.tegro.yamp.presentation.main.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** D101: seven taps on the version open the Developer section; the update check waits for the taps to stop. */
class DeveloperTapsTest {
    private val taps = DeveloperTaps()

    @Test
    fun aSingleTapChecksForUpdatesLaterWithoutAToast() {
        val tap = taps.onTap(0, sectionShown = false)
        assertTrue(tap.checkLater)
        assertNull(tap.remaining)
        assertFalse(tap.unlocked)
    }

    @Test
    fun theToastCountsDownFromTheFourthTapAndTheSeventhUnlocks() {
        val results = (0 until 7).map { taps.onTap(it * 200L, sectionShown = false) }
        assertEquals(listOf(null, null, null, 3, 2, 1, null), results.map { it.remaining })
        assertEquals(List(6) { false } + true, results.map { it.unlocked })
        // The seventh tap does not check for updates: it was a gesture, not a check
        assertFalse(results.last().checkLater)
    }

    @Test
    fun theCountStartsOverAfterAPauseOfThreeSeconds() {
        repeat(3) { taps.onTap(it * 100L, sectionShown = false) }
        // 3 taps, a pause, then more: the run starts over, no toast until its fourth
        assertNull(taps.onTap(10_000, sectionShown = false).remaining)
        assertNull(taps.onTap(10_100, sectionShown = false).remaining)
        assertNull(taps.onTap(10_200, sectionShown = false).remaining)
        assertEquals(3, taps.onTap(10_300, sectionShown = false).remaining)
    }

    @Test
    fun withTheSectionShownATapOnlyChecksAtOnce() {
        val tap = taps.onTap(0, sectionShown = true)
        assertTrue(tap.checkNow)
        assertFalse(tap.checkLater)
        assertFalse(tap.unlocked)
    }
}
