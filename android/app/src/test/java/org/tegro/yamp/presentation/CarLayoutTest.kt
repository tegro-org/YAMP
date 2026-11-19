package org.tegro.yamp.presentation

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import org.tegro.yamp.presentation.car.carScaleOf
import org.tegro.yamp.presentation.car.carSwipeStep
import org.tegro.yamp.presentation.player.LikeGesture
import org.tegro.yamp.presentation.player.PlayerGesture
import org.tegro.yamp.presentation.player.QueueDrag
import org.tegro.yamp.presentation.player.WideSpec
import org.tegro.yamp.presentation.player.CLOCK_INSET
import org.tegro.yamp.presentation.player.PlayerMenuItem
import org.tegro.yamp.presentation.player.playerMenuItems
import org.tegro.yamp.presentation.player.wideGeometryOf
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

    /** The block fills the full height between the same top and bottom padding (canvas `plWideLayout`). */
    private fun assertFullHeight(spec: WideSpec, w: Float, h: Float, three: Boolean) {
        val g = wideGeometryOf(spec, w, h, three, isRadio = false)
        val next = if (three) org.tegro.yamp.presentation.player.NEXT_BLOCK_GAP + org.tegro.yamp.presentation.player.nextCardsExtra(spec) + g.card
        else org.tegro.yamp.presentation.player.NEXT_BLOCK_GAP + spec.oneHeight
        assertEquals("top = the top row's padding", spec.top, g.coverTop, 0f)
        assertEquals("bottom = the same padding", h - spec.top, g.coverTop + g.cover + next, 0.5f)
        assertTrue("never under the minimum far padding", g.coverX + g.cover <= w - spec.pad + 0.01f)
    }

    @Test
    fun theCoverBlockSpansTheFullHeightOnEveryScreen() {
        // Layout units: the screen in dp divided by the spec's scale
        for ((w, h) in listOf(1760f to 720f, 1920f to 720f, 1280f to 800f, 2400f to 720f)) {
            val s = WideSpec.Car.scaleFor(w, h)
            assertFullHeight(WideSpec.Car, w / s, h / s, three = true)
            assertFullHeight(WideSpec.Car, w / s, h / s, three = false)
        }
        // A 2340×1080 phone on its side at ~2.6×: about 900×415 dp
        val s = WideSpec.Phone.scaleFor(900f, 415f)
        assertFullHeight(WideSpec.Phone, 900f / s, 415f / s, three = true)
        assertFullHeight(WideSpec.Phone, 900f / s, 415f / s, three = false)
    }

    @Test
    fun aFoldsScreensKeepTheBlockInsideTheScreen() {
        // Galaxy Z Fold 5: the inner screen on its side ≈ 830×690 dp (near-square), the cover screen ≈ 360 dp wide
        // (portrait only). On the inner screen the width stops the cover first: the block ends at the far padding and
        // sits centred in the height, the controls keep their minimum
        val spec = WideSpec.Phone
        val s = spec.scaleFor(830f, 690f)
        val w = 830f / s
        val h = 690f / s
        val g = wideGeometryOf(spec, w, h, nextThree = true, isRadio = false)
        assertEquals(w - spec.pad, g.coverX + g.cover, 0.5f)
        assertTrue(g.controlsW >= spec.driverW * 0.68f - 0.5f)
        assertTrue(g.coverTop >= spec.top)
        val blockBottom = g.coverTop + g.cover + org.tegro.yamp.presentation.player.NEXT_BLOCK_GAP + org.tegro.yamp.presentation.player.nextCardsExtra(spec) + g.card
        assertTrue(blockBottom <= h - spec.top + 0.5f)
        assertEquals("centred in the height", g.coverTop, h - blockBottom, 1f)
    }

    @Test
    fun aTallerScreenGivesTheControlsTheRestAndAWideOneCentresTheBlock() {
        // A 4:3 unit (1024×768): no spare width — the block ends at the minimum far padding, the controls take the rest.
        val tall = WideSpec.Car.let { spec -> val s = spec.scaleFor(1024f, 768f); wideGeometryOf(spec, 1024f / s, 768f / s, nextThree = true, isRadio = false) to 1024f / s }
        assertEquals(tall.second - WideSpec.Car.pad, tall.first.coverX + tall.first.cover, 0.5f)
        assertTrue(tall.first.controlsW < WideSpec.Car.driverW / WideSpec.Car.baseW * tall.second)
        // 2400×720: the controls stop at their share; the block is centred in the space beside them
        val spec = WideSpec.Car
        val s = spec.scaleFor(2400f, 720f)
        val w = 2400f / s
        val wide = wideGeometryOf(spec, w, 720f / s, nextThree = true, isRadio = false)
        assertEquals(spec.driverW / spec.baseW * w, wide.controlsW, 0.5f)
        val areaStart = spec.pad + wide.controlsW
        val areaEnd = w - spec.pad
        assertEquals((areaStart + areaEnd) / 2f, wide.coverX + wide.cover / 2f, 0.5f)
    }

    /** Layout units of a [w]×[h] dp screen, and its geometry. */
    private fun geometry(spec: WideSpec, w: Float, h: Float, three: Boolean = true): Pair<Float, org.tegro.yamp.presentation.player.WideGeometry> {
        val s = spec.scaleFor(w, h)
        return (w / s) to wideGeometryOf(spec, w / s, h / s, nextThree = three, isRadio = false)
    }

    private fun isTaller(spec: WideSpec, w: Float, h: Float, three: Boolean = true): Boolean {
        val (wu, g) = geometry(spec, w, h, three)
        return kotlin.math.abs(wu - spec.pad - (g.coverX + g.cover)) < 0.5f
    }

    @Test
    fun belowTheTallerRatioTheBlockEndsAtTheFarPadding() {
        val car = WideSpec.Car
        val phone = WideSpec.Phone
        // 16:10 and 4:3 are taller (w/h < 1.66): the block ends at the minimum far padding, the controls take the rest
        for ((w, h) in listOf(1280f to 800f, 1920f to 1200f, 1024f to 768f)) {
            assertTrue("taller $w×$h", isTaller(car, w, h, three = true))
            assertTrue("taller $w×$h, one next", isTaller(car, w, h, three = false))
        }
        // Foldables on their side: Fold 5 inner ≈ 830×690 dp, Fold 8 inner 2448×1848 px @404 ppi ≈ 969×731 dp
        assertTrue("Fold 5 inner", isTaller(phone, 830f, 690f))
        assertTrue("Fold 8 inner", isTaller(phone, 969f, 731f))
        assertTrue("Fold 8 inner, one next", isTaller(phone, 969f, 731f, three = false))
        // The head unit (1760×720 at the auto scale ≈ 1173×480 dp) and wider: centred beside the capped controls
        for ((w, h) in listOf(1173f to 480f, 1760f to 720f, 2400f to 720f)) {
            val (wu, g) = geometry(car, w, h)
            assertEquals("capped $w×$h", car.driverW / car.baseW * wu, g.controlsW, 0.5f)
            assertEquals("centred $w×$h", (car.pad + g.controlsW + wu - car.pad) / 2f, g.coverX + g.cover / 2f, 0.5f)
        }
        // Every result keeps the full height and the minimum far padding
        for ((w, h) in listOf(1280f to 800f, 1920f to 1200f, 969f to 731f, 466f to 737f, 2340f / 2.625f to 1080f / 2.625f)) {
            val spec = if (w > 1000f) car else phone
            val (wu, g) = geometry(spec, w, h)
            assertTrue("inside $w×$h", g.coverX + g.cover <= wu - spec.pad + 0.01f && g.coverTop >= spec.top)
        }
    }

    @Test
    fun theClockHasOneFixedPlaceInsideTheCoverOnATallerScreen() {
        val spec = WideSpec.Car
        for ((w, h) in listOf(1760f to 720f, 1280f to 800f, 1024f to 768f, 2400f to 720f)) {
            val s = spec.scaleFor(w, h)
            val g = wideGeometryOf(spec, w / s, h / s, nextThree = true, isRadio = false)
            assertEquals(spec.pad + CLOCK_INSET, g.clockEnd, 0f)
            assertEquals(spec.top + CLOCK_INSET, g.clockTop, 0f)
        }
        // On a taller screen (4:3) the cover reaches the minimum padding: the clock's corner lies inside the cover
        val s = spec.scaleFor(1024f, 768f)
        val w = 1024f / s
        val g = wideGeometryOf(spec, w, 768f / s, nextThree = true, isRadio = false)
        assertTrue(w - g.clockEnd <= g.coverX + g.cover && g.clockTop >= g.coverTop)
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
    fun theMoreMenuFollowsTheQueueTheServiceAndTheGestures() {
        fun items(gestures: Boolean = false, likes: Boolean = true, radio: Boolean = false, wave: Boolean = false, canRadio: Boolean = true, trackWave: Boolean = false) =
            playerMenuItems(gestures, likes, radio, wave, canRadio, trackWave)
        val tail = listOf(PlayerMenuItem.SEARCH, PlayerMenuItem.SLEEP, PlayerMenuItem.ADD_TO_PLAYLIST, PlayerMenuItem.QUALITY)
        // My Wave: "Tune the wave" first (and "Back to My Wave" on a wave by a track)
        assertEquals(listOf(PlayerMenuItem.TUNE) + tail, items(wave = true))
        assertEquals(listOf(PlayerMenuItem.TUNE, PlayerMenuItem.BACK_TO_WAVE) + tail, items(wave = true, trackWave = true))
        // Another Yandex queue: "Start radio" by the current track
        assertEquals(listOf(PlayerMenuItem.START_RADIO) + tail, items())
        // Jellyfin: no radio, no likes even with gestures
        assertEquals(tail, items(gestures = true, likes = false, canRadio = false))
        // Likes as a fallback only with gestures on
        assertEquals(listOf(PlayerMenuItem.START_RADIO) + tail + listOf(PlayerMenuItem.LIKE, PlayerMenuItem.DISLIKE), items(gestures = true))
        // FM radio: search and the sleep timer only
        assertEquals(listOf(PlayerMenuItem.SEARCH, PlayerMenuItem.SLEEP), items(gestures = true, radio = true))
    }

    @Test
    fun musicIsTheLastItemOfTheCarMenuOnly() {
        val car = playerMenuItems(likeGestures = true, supportsLikes = true, isRadio = false, waveActive = false, canStartRadio = true, trackWave = false, music = true)
        assertEquals(PlayerMenuItem.MUSIC, car.last())
        assertEquals(PlayerMenuItem.DISLIKE, car[car.size - 2])
        // On FM radio too: the Library is a way back to music
        assertEquals(
            listOf(PlayerMenuItem.SEARCH, PlayerMenuItem.SLEEP, PlayerMenuItem.MUSIC),
            playerMenuItems(likeGestures = false, supportsLikes = false, isRadio = true, waveActive = false, canStartRadio = false, trackWave = false, music = true)
        )
        assertFalse(PlayerMenuItem.MUSIC in playerMenuItems(false, true, false, false, true, false))
    }
}
