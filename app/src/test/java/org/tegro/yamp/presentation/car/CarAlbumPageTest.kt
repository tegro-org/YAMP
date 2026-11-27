package org.tegro.yamp.presentation.car

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.tegro.yamp.domain.account.AccountId
import org.tegro.yamp.domain.media.Album
import org.tegro.yamp.domain.media.ArtistBrief
import org.tegro.yamp.domain.media.Track
import org.tegro.yamp.domain.queue.MediaRef
import org.tegro.yamp.domain.queue.TrackRef
import org.tegro.yamp.presentation.account.AccountTag
import org.tegro.yamp.presentation.media.MediaMenuRow
import org.tegro.yamp.ui.theme.YandexMusicTheme

/** The car album page (canvas `ca-album-*`): a tile tap opens it, a hold the menu; a track row plays from its index. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1760dp-h720dp-land-mdpi", application = Application::class)
class CarAlbumPageTest {

    @get:Rule
    val compose = createComposeRule()

    private val account = AccountId.yandex("100")
    private val tag = AccountTag(account.value, "Anna", "A", Color(0xFFE0A050))
    private val album = Album(MediaRef("yandex", "album", "1"), "Дикие травы", listOf(ArtistBrief(null, "Мельница")), null, 2009, 3)
    private val tracks = listOf("Дороги", "Ведьма", "Травушка").mapIndexed { i, t ->
        Track(TrackRef.yandex("t$i"), t, album.artists, null, 240_000L, null, explicit = false)
    }

    private var tapped: CarTile? = null
    private var held: CarTile? = null
    private var track: Int? = null
    private val buttons = mutableListOf<MediaMenuRow>()

    private val callbacks = CarLibraryCallbacks(
        {}, {}, {}, {}, { tapped = it }, { held = it }, { _, _ -> }, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
        onAlbumButton = { buttons += it }, onAlbumTrack = { track = it }
    )

    private fun show(state: CarLibraryState) = compose.setContent {
        YandexMusicTheme {
            CarLibraryScreen(state, controlsOnLeft = true, account = tag, accounts = listOf(tag), nowPlaying = null, panel = null, queues = emptyList(), callbacks = callbacks)
        }
    }

    @Test
    fun aTileTapIsATapAndAHoldIsAHold() {
        show(CarLibraryState(account, CarTab.ALBUMS, listOf(CarTile.OfAlbum(album)), loading = false))
        compose.onNodeWithText("Дикие травы").performClick()
        assertEquals(CarTap.OPEN_ALBUM, tapped?.let(::carTap))
        compose.onNodeWithText("Дикие травы").performTouchInput { longClick() }
        assertEquals(album, (held as? CarTile.OfAlbum)?.album)
    }

    @Test
    fun aTrackRowPlaysFromItsIndexAndTheButtonsReport() {
        show(CarLibraryState(account, CarTab.ALBUMS, listOf(CarTile.OfAlbum(album)), loading = false, album = CarAlbumPage(album, tracks, loading = false)))
        compose.onNodeWithText("Травушка").performClick()
        assertEquals(2, track)
        compose.onNodeWithText("Start radio").performClick()
        assertEquals(listOf(MediaMenuRow.START_RADIO), buttons)
    }

    @Test
    fun aJellyfinAlbumPageHasNoRadio() {
        val jf = AccountId.jellyfin("srv", "u")
        val jfAlbum = album.copy(ref = MediaRef("jellyfin@srv", "album", "1"))
        show(CarLibraryState(jf, CarTab.ALBUMS, listOf(CarTile.OfAlbum(jfAlbum)), loading = false, album = CarAlbumPage(jfAlbum, tracks, loading = false)))
        assertEquals(0, compose.onAllNodes(androidx.compose.ui.test.hasText("Start radio")).fetchSemanticsNodes().size)
        compose.onNodeWithText("Shuffle").performClick()
        assertEquals(listOf(MediaMenuRow.SHUFFLE), buttons)
    }
}
