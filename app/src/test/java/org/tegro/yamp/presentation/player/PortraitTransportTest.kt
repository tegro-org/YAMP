package org.tegro.yamp.presentation.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The portrait Prev · Play · Next row: a gap that grows with the width, never glued, never past the base sizes. */
class PortraitTransportTest {

    @Test
    fun aPhoneRowKeepsTheBaseKnobsWithAClearGap() {
        // A 360 dp phone minus the player's 20 dp sides
        val t = portraitTransport(320f)
        assertEquals(68f, t.skip, 0.01f)
        assertEquals(96f, t.play, 0.01f)
        assertTrue("the knobs are not glued together", t.gap >= 16f)
        assertTrue(t.total <= 320f)
    }

    @Test
    fun theGapGrowsWithTheWidthUpToItsCap() {
        val narrow = portraitTransport(320f).gap
        val wide = portraitTransport(412f).gap
        assertTrue(wide > narrow)
        // 6 % of the row: about 31 dp on the Fold's inner screen; capped at 40 dp
        assertEquals(31.2f, portraitTransport(520f).gap, 0.01f)
        assertEquals(40f, portraitTransport(900f).gap, 0.01f)
    }

    @Test
    fun aWideRowNeverGrowsTheKnobs() {
        // The Fold's inner screen in portrait (the column is capped at 560 dp)
        val t = portraitTransport(520f)
        assertEquals(68f, t.skip, 0.01f)
        assertEquals(96f, t.play, 0.01f)
    }

    @Test
    fun theFold8InnerScreenStandingGetsTheCappedColumnsRow() {
        // A wider foldable's inner portrait ≈ 731×969 dp: wider than the 560 dp column, so the row is the column's 520 dp, as the other foldable
        val row = minOf(731f, 560f) - 2 * 20f
        assertEquals(520f, row, 0f)
        val t = portraitTransport(row)
        assertEquals(68f, t.skip, 0.01f)
        assertEquals(96f, t.play, 0.01f)
        assertEquals(31.2f, t.gap, 0.01f)
        // A wider foldable's cover screen (≈ 466 dp) is narrower than the cap: its own width minus the sides, base knobs too
        val cover = portraitTransport(466f - 2 * 20f)
        assertEquals(68f, cover.skip, 0.01f)
        assertTrue(cover.total <= 426f)
    }

    @Test
    fun aVeryNarrowRowScalesTheWholeRowDownButNotBelowSixtyPercent() {
        val t = portraitTransport(220f)
        assertTrue(t.total <= 220f + 0.5f)
        assertTrue(t.skip < 68f)
        val tiny = portraitTransport(100f)
        assertEquals(68f * 0.6f, tiny.skip, 0.01f)
    }
}
