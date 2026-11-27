package org.tegro.yamp.widget

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tegro.yamp.data.player.PlaylistSource
import org.tegro.yamp.data.player.SessionButtons
import org.tegro.yamp.domain.media.ArtistBrief
import org.tegro.yamp.domain.media.Track
import org.tegro.yamp.domain.queue.MediaRef
import org.tegro.yamp.domain.queue.TrackRef
import org.tegro.yamp.presentation.main.components.MainTab
import org.tegro.yamp.provider.jellyfin.JellyfinRefs

class WidgetNowTest {

    private fun track(ref: TrackRef, durationMs: Long? = 200_000L) =
        Track(ref, "Song", listOf(ArtistBrief(null, "Band")), null, durationMs, null, explicit = false)

    private val yandex = track(TrackRef.yandex("1"))
    private val station = track(TrackRef.of(MediaRef.PROVIDER_RADIO, "radio_7"), durationMs = null)
    private val jellyfin = track(JellyfinRefs("srv1").track("j1"))
    private val local = track(TrackRef.of("local", "f1"))

    private fun now(
        track: Track?,
        source: PlaylistSource = PlaylistSource.LIBRARY,
        liked: Set<String> = emptySet(),
        stations: Set<String> = emptySet(),
        position: Long = 50_000L,
        canWave: Boolean = true
    ) = widgetNowOf(track, true, source, liked, stations, position, "Live", canWave)

    @Test
    fun `a Yandex track gets both thumbs, a progress and a wave label only on the wave`() {
        val plain = now(yandex)
        assertTrue(plain.canLike && plain.canDislike)
        assertFalse(plain.isLiked)
        assertEquals(0.25f, plain.progress!!, 0.001f)
        assertNull(plain.source)
        assertEquals(WidgetSource.WAVE, now(yandex, source = PlaylistSource.VIBE).source)
        assertEquals("Band", plain.artist)
    }

    @Test
    fun `liked is read from the liked ids for a track and from the favourites for a station`() {
        assertTrue(now(yandex, liked = setOf("1")).isLiked)
        assertFalse(now(yandex, stations = setOf("7")).isLiked)
        assertTrue(now(station, stations = setOf("7")).isLiked)
        assertFalse(now(station, liked = setOf("radio_7")).isLiked)
    }

    @Test
    fun `a station has only the heart, no progress, the live line and the radio label`() {
        val fm = now(station)
        assertTrue(fm.canLike)
        assertFalse(fm.canDislike)
        assertNull(fm.progress)
        assertEquals("Live", fm.artist)
        assertEquals(WidgetSource.RADIO, fm.source)
    }

    @Test
    fun `Jellyfin and local tracks offer no reaction at all`() {
        for (other in listOf(jellyfin, local)) {
            val shown = now(other, liked = setOf(other.ref.id))
            assertFalse(shown.canLike)
            assertFalse(shown.canDislike)
            assertFalse(shown.isLiked)
        }
    }

    @Test
    fun `progress is clamped and absent without a duration`() {
        assertEquals(1f, now(yandex, position = 900_000L).progress!!, 0f)
        assertEquals(0f, now(yandex, position = -5L).progress!!, 0f)
        assertNull(now(track(TrackRef.yandex("2"), durationMs = 0L)).progress)
        assertNull(now(track(TrackRef.yandex("3"), durationMs = null)).progress)
    }

    @Test
    fun `nothing playing is an empty state that keeps whether the wave can be offered`() {
        val withYandex = now(null)
        assertFalse(withYandex.hasTrack)
        assertTrue(withYandex.canWave)
        assertFalse(now(null, canWave = false).canWave)
    }

    @Test
    fun `the clock reads minutes and seconds and never goes negative`() {
        assertEquals("1:22", widgetClock(82_000))
        assertEquals("0:00", widgetClock(0))
        assertEquals("0:00", widgetClock(-3_000))
        assertEquals("65:05", widgetClock(3_905_000))
    }

    @Test
    fun `a tick is due only while playing with a widget placed and the screen on`() {
        assertTrue(widgetTickDue(playing = true, widgetCount = 1, screenOn = true))
        assertFalse(widgetTickDue(playing = false, widgetCount = 1, screenOn = true))
        assertFalse(widgetTickDue(playing = true, widgetCount = 0, screenOn = true))
        assertFalse(widgetTickDue(playing = true, widgetCount = 2, screenOn = false))
    }

    @Test
    fun `a redraw is due only with a widget placed and the screen on, a tick also needs music`() {
        assertTrue(widgetRedrawDue(widgetCount = 1, screenOn = true))
        assertFalse(widgetRedrawDue(widgetCount = 0, screenOn = true))
        assertFalse(widgetRedrawDue(widgetCount = 1, screenOn = false))
    }

    @Test
    fun `a widget press reacts only as far as the track allows`() {
        val yandexKinds = listOf(SessionButtons.Kind.DISLIKE, SessionButtons.Kind.LIKE)
        val stationKinds = listOf(SessionButtons.Kind.UNLIKE)
        assertEquals(WidgetReaction.TOGGLE_LIKE, widgetReactionOf(WidgetActionReceiver.ACTION_LIKE, yandexKinds))
        assertEquals(WidgetReaction.DISLIKE, widgetReactionOf(WidgetActionReceiver.ACTION_DISLIKE, yandexKinds))
        assertEquals(WidgetReaction.TOGGLE_LIKE, widgetReactionOf(WidgetActionReceiver.ACTION_LIKE, stationKinds))
        assertNull(widgetReactionOf(WidgetActionReceiver.ACTION_DISLIKE, stationKinds))
        assertNull(widgetReactionOf(WidgetActionReceiver.ACTION_LIKE, emptyList()))
        assertNull(widgetReactionOf("something else", yandexKinds))
        assertNull(widgetReactionOf(null, yandexKinds))
    }

    @Test
    fun `the Player drops a thumb before it drops any transport button`() {
        assertEquals(PlayerControls(dislike = true, like = true), playerControlsFor(288.dp, canDislike = true, canLike = true))
        assertEquals(PlayerControls(dislike = false, like = true), playerControlsFor(178.dp, canDislike = true, canLike = true))
        assertEquals(PlayerControls(dislike = false, like = false), playerControlsFor(140.dp, canDislike = true, canLike = true))
        assertEquals(PlayerControls(dislike = false, like = false), playerControlsFor(288.dp, canDislike = false, canLike = false))
    }

    @Test
    fun `a widget opens a known tab and ignores an unknown one`() {
        assertEquals(MainTab.LIBRARY, MainTab.ofRoute("library"))
        assertEquals(MainTab.RADIO, MainTab.ofRoute("radio"))
        assertEquals(MainTab.SEARCH, MainTab.ofRoute("search"))
        assertNull(MainTab.ofRoute("car"))
        assertNull(MainTab.ofRoute(null))
    }
}
