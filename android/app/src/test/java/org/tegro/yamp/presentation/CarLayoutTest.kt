package org.tegro.yamp.presentation

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import org.tegro.yamp.presentation.car.carScaleOf
import org.tegro.yamp.presentation.car.carSwipeStep
import org.tegro.yamp.presentation.player.LikeGesture
import org.tegro.yamp.presentation.player.PlayerGesture
import org.tegro.yamp.presentation.player.QueueDrag
import org.tegro.yamp.presentation.player.WideSpec
import org.tegro.yamp.presentation.player.coverFitOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The wide player (phone on its side and the Car layout): its scale, the cover's place, the gestures. */
class CarLayoutTest {

    @Test
    fun theDesignSizesScaleToOne() {
        assertEquals(1f, WideSpec.Car.scaleFor(1920f, 720f), 0.0001f)
        assertEquals(1f, WideSpec.Phone.scaleFor(915f, 412f), 0.0001f)
        // The user's unit: 1760 px wide at the auto scale (1173 × 480 dp) — the width runs out first
        assertEquals(1173f / 1920f, WideSpec.Car.scaleFor(1173f, 480f), 0.0001f)
    }

    @Test
    fun theCarPickersKeepTheirScale() {
        assertEquals(1f, carScaleOf(1280.dp, 720.dp, 720.dp, isLandscape = true), 0.0001f)
        assertEquals(1208f / 1200f, carScaleOf(960.dp, 1280.dp, 1208.dp, isLandscape = false), 0.0001f)
    }

    @Test
    fun theCoverStaysClearOfTheClock() {
        // Wide enough: the cover sits beside the clock, as large as the height leaves (plc-unit-1760)
        val wide = coverFitOf(widthDp = 1000f, heightDp = 668f, nextDp = 220f, gapDp = 22f, maxDp = 420f, clockDp = 230f, belowClockDp = 96f)
        assertEquals(0f, wide.top, 0f)
        assertTrue(wide.size <= 1000f - 2 * 230f)
        // Too narrow beside the clock: it starts under it instead
        val narrow = coverFitOf(widthDp = 600f, heightDp = 668f, nextDp = 220f, gapDp = 22f, maxDp = 420f, clockDp = 230f, belowClockDp = 96f)
        assertEquals(96f, narrow.top, 0f)
        assertTrue(narrow.size + narrow.top + 220f + 22f <= 668f)
        // The 1760×720 unit: beside the clock the cover would shrink; under it it gets the phone-landscape share
        val unit = coverFitOf(widthDp = 794f, heightDp = 668f, nextDp = 108f, gapDp = 22f, maxDp = 458f, clockDp = 230f, belowClockDp = 96f)
        assertEquals(96f, unit.top, 0f)
        assertTrue(unit.size > 794f - 2 * 230f)
        // No clock (the phone): only the width, the height and the maximum count
        assertEquals(262f, coverFitOf(400f, 400f, 63f, 22f, 262f, 0f, 0f).size, 0f)
    }

    @Test
    fun aSwipeUpOpensTheQueueOnlyFromTheEdgeOrTheNextTracks() {
        val next = Rect(20f, 800f, 400f, 870f)
        assertTrue("the bottom edge", PlayerGesture.inQueueZone(200f, 900f, heightPx = 915f, edgePx = 56f, next = next))
        assertTrue("over the next track", PlayerGesture.inQueueZone(100f, 820f, 915f, 56f, next))
        assertFalse("the cover is not a zone", PlayerGesture.inQueueZone(200f, 300f, 915f, 56f, next))
        assertTrue("far enough", PlayerGesture.shouldOpenQueue(upPx = 60f, distancePx = 56f, velocity = 0f))
        assertTrue("a fling up", PlayerGesture.shouldOpenQueue(10f, 56f, velocity = -1500f))
        assertFalse("a short nudge", PlayerGesture.shouldOpenQueue(20f, 56f, velocity = -300f))
    }

    @Test
    fun theQueueSheetFollowsTheFingerAndSettlesByPlaceOrFling() {
        // 300 px up of a 600 px sheet = half open; down moves it back; it never goes past hidden or fully up
        assertEquals(0.5f, QueueDrag.dragged(0f, -300f, 600f), 0.001f)
        assertEquals(0.25f, QueueDrag.dragged(0.5f, 150f, 600f), 0.001f)
        assertEquals(0f, QueueDrag.dragged(0.1f, 500f, 600f), 0f)
        assertEquals(1f, QueueDrag.dragged(0.9f, -500f, 600f), 0f)
        assertEquals("no height yet: no move", 0.3f, QueueDrag.dragged(0.3f, -100f, 0f), 0f)
        assertTrue("more than half up", QueueDrag.settlesOpen(0.6f, velocity = 0f))
        assertFalse("less than half up", QueueDrag.settlesOpen(0.4f, velocity = 0f))
        assertTrue("a fling up opens from low", QueueDrag.settlesOpen(0.1f, velocity = -1500f))
        assertFalse("a fling down closes from high", QueueDrag.settlesOpen(0.9f, velocity = 1500f))
        assertTrue("up from the zone lifts the sheet", PlayerGesture.sheetTakesDrag(true, 0f, delta = -10f, sheetFraction = 0f))
        assertTrue("down again while it is up sinks it", PlayerGesture.sheetTakesDrag(true, 0f, delta = 10f, sheetFraction = 0.3f))
        assertFalse("down with the sheet hidden pulls the player", PlayerGesture.sheetTakesDrag(true, 0f, delta = 10f, sheetFraction = 0f))
        assertFalse("not from the zone", PlayerGesture.sheetTakesDrag(false, 0f, delta = -10f, sheetFraction = 0f))
    }

    @Test
    fun aDoubleTapLikesButNeverUnlikes() {
        assertTrue(LikeGesture.doubleTapLikes(isLiked = false))
        assertFalse(LikeGesture.doubleTapLikes(isLiked = true))
    }

    @Test
    fun oneTrackPerSwipeHoweverManyMovesItArrivesIn() {
        val threshold = 72f
        assertEquals("a short drag does nothing", 0, carSwipeStep(40f, threshold))
        assertEquals("to the right: the previous track", 1, carSwipeStep(72f, threshold))
        assertEquals("to the left: the next one", -1, carSwipeStep(-300f, threshold))
        // A quick swipe arrives as several moves; only their sum decides, once
        val moves = listOf(-30f, -45f, -60f, -25f)
        assertEquals(-1, carSwipeStep(moves.sum(), threshold))
    }

    @Test
    fun theCarTabIsNowThePlayer() {
        // The tab that was "В авто" opens the player; it is not a page of its own
        assertEquals("player", org.tegro.yamp.presentation.main.components.MainTab.PLAYER.route)
        assertEquals(org.tegro.yamp.R.string.tab_player, org.tegro.yamp.presentation.main.components.MainTab.PLAYER.title)
        assertEquals(5, org.tegro.yamp.presentation.main.components.MainTab.entries.size)
    }

    @Test
    fun theSystemFontSizeDoesNotGrowTheCarLayout() {
        assertEquals(1f, org.tegro.yamp.presentation.car.CAR_MAX_FONT_SCALE, 0f)
    }

    @Test
    fun theMoreMenuOffersLikesOnlyWithGesturesAndAServiceThatHasThem() {
        val base = listOf(
            org.tegro.yamp.presentation.player.WideMenuItem.SLEEP,
            org.tegro.yamp.presentation.player.WideMenuItem.ADD_TO_PLAYLIST,
            org.tegro.yamp.presentation.player.WideMenuItem.QUALITY
        )
        org.junit.Assert.assertEquals(base, org.tegro.yamp.presentation.player.wideMenuItems(likeGestures = false, supportsLikes = true, isRadio = false))
        org.junit.Assert.assertEquals(
            base + listOf(org.tegro.yamp.presentation.player.WideMenuItem.LIKE, org.tegro.yamp.presentation.player.WideMenuItem.DISLIKE),
            org.tegro.yamp.presentation.player.wideMenuItems(likeGestures = true, supportsLikes = true, isRadio = false)
        )
        // Jellyfin has no likes yet: gestures on, still no Like / Dislike rows
        org.junit.Assert.assertEquals(base, org.tegro.yamp.presentation.player.wideMenuItems(likeGestures = true, supportsLikes = false, isRadio = false))
        // FM radio: only the sleep timer
        org.junit.Assert.assertEquals(
            listOf(org.tegro.yamp.presentation.player.WideMenuItem.SLEEP),
            org.tegro.yamp.presentation.player.wideMenuItems(likeGestures = true, supportsLikes = true, isRadio = true)
        )
    }
}
