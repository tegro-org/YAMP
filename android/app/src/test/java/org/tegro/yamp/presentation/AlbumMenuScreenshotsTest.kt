package org.tegro.yamp.presentation

import android.app.Application
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.tegro.yamp.domain.media.Album
import org.tegro.yamp.domain.media.AlbumKind
import org.tegro.yamp.domain.media.Artist
import org.tegro.yamp.domain.media.Track
import org.tegro.yamp.domain.provider.ArtistPage
import org.tegro.yamp.domain.queue.MediaRef
import org.tegro.yamp.domain.queue.TrackRef
import org.tegro.yamp.presentation.hifi.ArtworkTint
import org.tegro.yamp.presentation.hifi.HiFiBackdrop
import org.tegro.yamp.presentation.hifi.HiFiDialogDefaults
import org.tegro.yamp.presentation.hifi.LocalArtworkTint
import org.tegro.yamp.presentation.media.ArtistAddDialog
import org.tegro.yamp.presentation.media.ArtistDialogState
import org.tegro.yamp.presentation.media.MediaAction
import org.tegro.yamp.presentation.media.MediaMenuContent
import org.tegro.yamp.presentation.media.MediaTarget
import org.tegro.yamp.presentation.media.mediaMenuRows
import org.tegro.yamp.ui.theme.YandexMusicTheme

/** Album / artist actions (canvas `am-*`): the menu for each provider and the artist dialog. */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = RobolectricDeviceQualifiers.Pixel7, application = Application::class)
class AlbumMenuScreenshotsTest {

    @get:Rule
    val compose = createComposeRule()

    private val tint = ArtworkTint(Color(0xFF2E5A3A), Color(0xFF1B3A2A))

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

    /** The sheet as the menu shows it, without the modal window. */
    @Composable
    private fun Sheet(target: MediaTarget, fits: Boolean = true) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.BottomCenter) {
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(HiFiDialogDefaults.Container).padding(top = 20.dp)
            ) { MediaMenuContent(target, mediaMenuRows(target, fits)) {} }
        }
    }

    private val yandexAlbum = MediaTarget(MediaRef(MediaRef.PROVIDER_YANDEX, "album", "1"), "Дикие травы", "Альбом · Мельница · 2009", null, isArtist = false)
    private val jellyfinAlbum = MediaTarget(MediaRef("jellyfin@srv", "album", "a1"), "YAMP Test FLAC", "Альбом · Kevin MacLeod", null, isArtist = false)
    private val artist = MediaTarget(MediaRef(MediaRef.PROVIDER_YANDEX, "artist", "9"), "Мельница", "Исполнитель", null, isArtist = true)

    private fun album(id: String, year: Int, tracks: Int, kind: AlbumKind = AlbumKind.ALBUM) =
        Album(MediaRef(MediaRef.PROVIDER_YANDEX, "album", id), "A$id", emptyList(), null, year, tracks, kind = kind)

    private val page = ArtistPage(
        artist = Artist(artist.ref, "Мельница", null),
        popularTracks = List(20) { Track(TrackRef.yandex("p$it"), "p$it", emptyList(), null, null, null, explicit = false) },
        albums = listOf(album("1", 2005, 12), album("2", 2006, 14), album("3", 2009, 12), album("4", 2012, 13), album("s", 2010, 1, AlbumKind.SINGLE)),
        alsoAlbums = listOf(album("c", 2014, 20, AlbumKind.COMPILATION))
    )

    @Test
    fun albumMenuYandex() {
        show { Sheet(yandexAlbum) }
        snap("media_menu_album")
    }

    @Test
    fun albumMenuJellyfinNoRadio() {
        show { Sheet(jellyfinAlbum) }
        snap("media_menu_album_jellyfin")
    }

    @Test
    fun albumMenuAnotherAccountsQueue() {
        show { Sheet(jellyfinAlbum, fits = false) }
        snap("media_menu_album_other_queue")
    }

    @Test
    fun artistMenu() {
        show { Sheet(artist) }
        snap("media_menu_artist")
    }

    @Test
    fun artistAddDialog() {
        show { ArtistAddDialog(ArtistDialogState(artist, MediaAction.ADD_TO_END, page), { _, _ -> }, {}) }
        snap("media_artist_add")
    }

    @Test
    fun artistPlayDialog() {
        show { ArtistAddDialog(ArtistDialogState(artist, MediaAction.PLAY_NOW, page), { _, _ -> }, {}) }
        snap("media_artist_play")
    }

    /** "Add to playlist…" from an album (canvas `am-add-to-playlist`): the account's playlists, "New playlist" on top. */
    @Test
    fun addToPlaylistPicker() {
        val lists = listOf("В дорогу" to 48, "Вечер" to 23, "Русский рок" to 112, "Спокойное" to 37).mapIndexed { i, (name, count) ->
            org.tegro.yamp.domain.media.Playlist(MediaRef(MediaRef.PROVIDER_YANDEX, "playlist", "u:$i"), name, count, null, isPublic = false)
        }
        show {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.BottomCenter) {
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                        .background(HiFiDialogDefaults.Container).padding(top = 20.dp)
                ) { org.tegro.yamp.presentation.media.MediaPlaylistPickerContent("Дикие травы", lists, {}, {}) }
            }
        }
        snap("media_add_to_playlist")
    }
}
