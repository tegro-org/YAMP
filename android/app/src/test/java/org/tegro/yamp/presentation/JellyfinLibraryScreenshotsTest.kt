package org.tegro.yamp.presentation

import android.app.Application
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.tegro.yamp.domain.account.AccountId
import org.tegro.yamp.domain.media.Album
import org.tegro.yamp.domain.media.AlbumBrief
import org.tegro.yamp.domain.media.Artist
import org.tegro.yamp.domain.media.ArtistBrief
import org.tegro.yamp.domain.media.Genre
import org.tegro.yamp.domain.media.Track
import org.tegro.yamp.domain.provider.BrowseCounts
import org.tegro.yamp.domain.queue.MediaRef
import org.tegro.yamp.domain.queue.TrackRef
import org.tegro.yamp.presentation.catalog.BrowseItem
import org.tegro.yamp.presentation.catalog.BrowseKind
import org.tegro.yamp.presentation.catalog.BrowseList
import org.tegro.yamp.presentation.catalog.CatalogActions
import org.tegro.yamp.presentation.catalog.CatalogPage
import org.tegro.yamp.presentation.catalog.CatalogPageScreen
import org.tegro.yamp.presentation.catalog.CatalogPageState
import org.tegro.yamp.presentation.catalog.JellyfinLibraryRow
import org.tegro.yamp.presentation.hifi.ArtworkTint
import org.tegro.yamp.presentation.hifi.HiFiBackdrop
import org.tegro.yamp.presentation.hifi.LocalArtworkTint
import org.tegro.yamp.presentation.main.LibraryScreen
import org.tegro.yamp.ui.theme.YandexMusicTheme

/** Phase 4 step 22: the Jellyfin library — Library rows, browse lists, an album page and the "not reachable" banner. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = RobolectricDeviceQualifiers.Pixel7, application = Application::class)
class JellyfinLibraryScreenshotsTest {

    @get:Rule
    val compose = createComposeRule()

    private val tint = ArtworkTint(Color(0xFF5A3E8C), Color(0xFF2B1B4E))

    private val options = RoborazziOptions(
        recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5),
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.005f)
    )

    private fun show(content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            YandexMusicTheme {
                CompositionLocalProvider(LocalArtworkTint provides tint) {
                    Box(Modifier.fillMaxSize()) {
                        HiFiBackdrop(tint = tint)
                        content()
                    }
                }
            }
        }
        repeat(10) {
            compose.mainClock.advanceTimeBy(100)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun snap(name: String) = captureScreenRoboImage("src/test/screenshots/$name.png", options)

    private val account = AccountId.jellyfin("srv1", "u1")
    private val server = "jellyfin@srv1"
    private val lumen = ArtistBrief(MediaRef(server, "artist", "ar1"), "Lumen Lane")
    private val actions = CatalogActions(
        onBack = {}, onRetry = {}, onOpenArtist = { _, _ -> }, onOpenAlbum = { _, _ -> }, onPlay = { _, _, _ -> }, onToggleLike = {}
    )

    private fun album(id: String, title: String, artist: String, year: Int) =
        Album(MediaRef(server, "album", id), title, listOf(ArtistBrief(null, artist)), cover = null, year = year, trackCount = 6)

    private val albums = listOf(
        album("a1", "Night Drive", "Lumen Lane", 2021), album("a2", "Paper Lanterns", "The Quiet Coast", 2019),
        album("a3", "Tidewater", "Harbor Lights", 2020), album("a4", "Small Hours", "Lumen Lane", 2018),
        album("a5", "Glasshouse", "North Fields", 2022), album("a6", "Long Way Round", "The Quiet Coast", 2017)
    )

    private fun browse(kind: BrowseKind, title: String, items: List<BrowseItem>, offline: Boolean = false) = CatalogPageState(
        page = CatalogPage.Browse(kind, title, account), browse = BrowseList(items, next = null),
        isLoading = false, offline = offline, serverName = "Home Media"
    )

    @Test
    fun libraryRows() {
        show {
            LibraryScreen(
                tracks = emptyList(), isLoading = false, currentTrackId = null, isPlaying = false, likedTrackIds = emptySet(),
                downloadedTrackIds = emptySet(), downloadProgress = null, onTrackClick = {}, onPlayAllClick = {}, onShuffleClick = {},
                onDownloadAllClick = {}, onCancelDownload = {}, onToggleLike = {},
                jellyfinLibraries = listOf(JellyfinLibraryRow(account, "yamp-dev · Home Media", BrowseCounts(486, 212, 31, 9)))
            )
        }
        snap("jellyfin_library_rows")
    }

    @Test
    fun browseAlbums() {
        show { CatalogPageScreen(browse(BrowseKind.ALBUMS, "Albums", albums.map(BrowseItem::OfAlbum)), null, false, emptySet(), 0.dp, actions) }
        snap("jellyfin_browse_albums")
    }

    @Test
    fun browseArtistsAndGenres() {
        val artists = listOf("Harbor Lights", "Lumen Lane", "North Fields", "The Quiet Coast")
            .mapIndexed { i, name -> BrowseItem.OfArtist(Artist(MediaRef(server, "artist", "ar$i"), name, cover = null)) }
        show { CatalogPageScreen(browse(BrowseKind.ARTISTS, "Artists", artists), null, false, emptySet(), 0.dp, actions) }
        snap("jellyfin_browse_artists")
    }

    @Test
    fun browseGenres() {
        val genres = listOf("Ambient", "Celtic", "Electronica", "Folk", "Jazz")
            .mapIndexed { i, name -> BrowseItem.OfGenre(Genre(MediaRef(server, "genre", "g$i"), name, cover = null)) }
        show { CatalogPageScreen(browse(BrowseKind.GENRES, "Genres", genres), null, false, emptySet(), 0.dp, actions) }
        snap("jellyfin_browse_genres")
    }

    private val nightDrive = albums[0].copy(
        artists = listOf(lumen),
        tracks = listOf("Coastal Road", "Night Drive", "Sodium Lights", "Overpass", "Last Exit", "Home Before Dawn").mapIndexed { i, title ->
            Track(TrackRef.of(server, "t$i"), title, listOf(lumen), AlbumBrief(albums[0].ref, "Night Drive"), 241_000L + i * 13_000L, null, false)
        }
    )

    @Test
    fun albumPage() {
        show {
            CatalogPageScreen(
                CatalogPageState(CatalogPage.Album("a1", "Night Drive", account), album = nightDrive, isLoading = false, serverName = "Home Media"),
                null, false, emptySet(), 0.dp, actions
            )
        }
        snap("jellyfin_album_page")
    }

    @Test
    fun albumPageWhileTheServerIsAway() {
        show {
            CatalogPageScreen(
                CatalogPageState(CatalogPage.Album("a1", "Night Drive", account), album = nightDrive, isLoading = false, offline = true, serverName = "Home Media"),
                null, false, emptySet(), 0.dp, actions
            )
        }
        snap("jellyfin_page_offline")
    }
}
