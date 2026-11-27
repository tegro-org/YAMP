package org.tegro.yamp.presentation

import org.tegro.yamp.data.provider.yandex.toArtist
import org.tegro.yamp.data.provider.yandex.toAlbum
import org.tegro.yamp.presentation.catalog.toArtistPage
import org.tegro.yamp.data.provider.yandex.toArtistParts
import org.tegro.yamp.data.provider.yandex.toPlaylist
import org.tegro.yamp.data.provider.yandex.toTrack
import org.tegro.yamp.presentation.main.components.DockScrim
import androidx.compose.ui.Alignment
import org.tegro.yamp.presentation.main.components.FloatingGlassDock
import org.tegro.yamp.presentation.main.components.DockClearance
import org.tegro.yamp.presentation.main.components.DockBottomGap
import org.tegro.yamp.presentation.hifi.LocalLinkState
import org.tegro.yamp.data.player.LinkState
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.geometry.Offset
import android.app.Application
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.tegro.yamp.data.dto.AlbumDto
import org.tegro.yamp.data.dto.ArtistBriefDto
import org.tegro.yamp.data.dto.ArtistDto
import org.tegro.yamp.data.dto.TrackDetailsDto
import org.tegro.yamp.data.dto.UserPlaylistDto
import org.tegro.yamp.data.repository.RadioRepository
import org.tegro.yamp.domain.model.RadioCategory
import org.tegro.yamp.presentation.catalog.CatalogActions
import org.tegro.yamp.presentation.catalog.CatalogPage
import org.tegro.yamp.presentation.catalog.CatalogPageScreen
import org.tegro.yamp.presentation.catalog.CatalogPageState
import org.tegro.yamp.presentation.main.SearchScreen
import org.tegro.yamp.presentation.main.LibraryScreen
import org.tegro.yamp.presentation.main.OpenedPlaylist
import org.tegro.yamp.presentation.main.PlaylistScreen
import org.tegro.yamp.presentation.main.HomeScreen
import org.tegro.yamp.presentation.main.waveShelfOf
import org.tegro.yamp.presentation.hifi.ArtworkTint
import org.tegro.yamp.presentation.hifi.HiFiBackdrop
import org.tegro.yamp.presentation.hifi.LocalArtworkTint
import org.tegro.yamp.presentation.main.components.FloatingGlassBottomBar
import org.tegro.yamp.presentation.main.components.LocalTrackMenu
import org.tegro.yamp.presentation.main.components.MiniPlayer
import org.tegro.yamp.presentation.main.components.TrackContextMenu
import org.tegro.yamp.presentation.main.components.TrackItem
import org.tegro.yamp.presentation.main.components.TrackMenuLauncher
import org.tegro.yamp.presentation.player.FullScreenPlayer
import org.tegro.yamp.presentation.radio.RadioScreen
import org.tegro.yamp.ui.theme.YandexMusicTheme
import kotlinx.serialization.json.JsonPrimitive
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import coil3.request.crossfade
import org.tegro.yamp.presentation.car.LocalCarClock
import kotlinx.coroutines.Dispatchers
import org.junit.Before
import org.junit.Rule
import java.util.TimeZone
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Reference screenshots of the main screens and components (Roborazzi on Robolectric). They catch
 * visual regressions the old source-text checks could not, like the "empty square" radio cards.
 * Images: app/src/test/screenshots. `./gradlew recordRoborazziDebug` re-records them after an intended
 * change, `./gradlew verifyRoborazziDebug` fails on any difference. A plain test run only renders.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = RobolectricDeviceQualifiers.Pixel7, application = Application::class)
class ScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun setUp() {
        // Images (bundled radio logos) load synchronously and without the crossfade, which runs on real
        // time and would leave them transparent in the frozen frames
        val context = ApplicationProvider.getApplicationContext<Application>()
        SingletonImageLoader.setUnsafe(
            ImageLoader.Builder(context)
                .coroutineContext(Dispatchers.Unconfined)
                .components {
                    add(Interceptor { chain -> chain.withRequest(chain.request.newBuilder().crossfade(false).build()).proceed() })
                }
                .build()
        )
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Moscow"))
    }

    private fun track(id: String, title: String, artist: String = "Исполнитель") =
        TrackDetailsDto(id = JsonPrimitive(id), title = title, artists = listOf(ArtistDto(name = artist)), durationMs = 215_000)

    /** The fixture tracks as screens show them (phase 3 step 12–15). */
    private val shown get() = tracks.map { it.toTrack() }

    private val tracks = listOf(
        track("1", "Первая песня", "Группа"),
        track("2", "Очень длинное название трека, которое не помещается в одну строку", "Исполнитель с длинным именем"),
        track("3", "Третья песня")
    )

    private val playlists = listOf(
        UserPlaylistDto(kind = JsonPrimitive(1003), title = "В дорогу", trackCount = 42, revision = 7),
        UserPlaylistDto(kind = JsonPrimitive(1004), title = "Спокойное", trackCount = 1, revision = 2)
    ).mapNotNull { it.toPlaylist() }

    /** A fixed blue cover tint, so the living background is the same on every machine. */
    private val tint = ArtworkTint(Color(0xFF3F6FC4), Color(0xFF1B2A6B))

    /** Renders [content] on the app's living background, with infinite animations frozen. */
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
        // Frames in steps: an image loaded meanwhile is laid out and drawn (crossfade included)
        repeat(20) {
            compose.mainClock.advanceTimeBy(100)
            Shadows.shadowOf(Looper.getMainLooper()).idle() // Coil delivers results on the main looper
        }
    }

    /**
     * Half scale keeps the references small in git and still shows layout and image regressions. A 0.5 %
     * pixel tolerance absorbs font antialiasing differences between Windows (recorded) and Linux (CI).
     */
    private val options = RoborazziOptions(
        recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5),
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.005f)
    )

    private fun snap(name: String) = compose.onRoot().captureRoboImage("src/test/screenshots/$name.png", options)

    @OptIn(ExperimentalRoborazziApi::class)
    private fun snapScreen(name: String) = captureScreenRoboImage("src/test/screenshots/$name.png", options)

    @Test
    fun trackItems() {
        show {
            Column(Modifier.padding(8.dp)) {
                TrackItem(track = shown[0], isPlaying = false, onClick = {})
                TrackItem(track = shown[1], isPlaying = true, isLiked = true, isDownloaded = true, onClick = {})
                // With a track menu, every row gets the "⋮" button
                CompositionLocalProvider(LocalTrackMenu provides TrackMenuLauncher { _, _ -> }) {
                    TrackItem(track = shown[2], isPlaying = false, isLiked = true, onClick = {})
                }
            }
        }
        snap("track_items")
    }

    @Test
    fun libraryWithPlaylists() {
        show {
            LibraryScreen(
                tracks = shown, isLoading = false, currentTrackId = "1", isPlaying = true, likedTrackIds = setOf("1", "2", "3"), downloadedTrackIds = setOf("2"),
                downloadProgress = null, onTrackClick = {}, onPlayAllClick = {}, onShuffleClick = {},
                onDownloadAllClick = {}, onCancelDownload = {}, onToggleLike = {},
                playlists = playlists, historyCount = 23
            )
        }
        snap("library")
    }

    /** Left-hand wheel side: the settings gear at the left edge, the title aligned to the right. */
    @Composable
    private fun LibraryLeftHand() {
        LibraryScreen(
            tracks = shown, isLoading = false, currentTrackId = "1", isPlaying = true, likedTrackIds = setOf("1", "2", "3"), downloadedTrackIds = setOf("2"),
            downloadProgress = null, onTrackClick = {}, onPlayAllClick = {}, onShuffleClick = {},
            onDownloadAllClick = {}, onCancelDownload = {}, onToggleLike = {},
            playlists = playlists, historyCount = 23, controlsOnLeft = true
        )
    }

    @Test
    fun libraryLeftHand() {
        show { LibraryLeftHand() }
        snap("library_left_hand")
    }

    @Test
    @Config(qualifiers = HEAD_UNIT)
    fun libraryLeftHandHeadUnit() {
        show { LibraryLeftHand() }
        snap("head_library_left_hand")
    }

    /** Right-hand (the default) on the head unit: the title at the left, the gear at the right. */
    @Test
    @Config(qualifiers = HEAD_UNIT)
    fun libraryRightHandHeadUnit() {
        show {
            LibraryScreen(
                tracks = shown, isLoading = false, currentTrackId = "1", isPlaying = true, likedTrackIds = setOf("1", "2", "3"), downloadedTrackIds = setOf("2"),
                downloadProgress = null, onTrackClick = {}, onPlayAllClick = {}, onShuffleClick = {},
                onDownloadAllClick = {}, onCancelDownload = {}, onToggleLike = {},
                playlists = playlists, historyCount = 23
            )
        }
        snap("head_library_right_hand")
    }

    @Test
    fun wavePlaying() {
        show {
            HomeScreen(
                waveTrack = shown[0], isPlaying = true, isLoading = false, isLiked = true, progress = { 70_000L },
                shelf = waveShelfOf(true, shown.drop(1), emptyList()), shimmer = false,
                onPlayPause = {}, onLike = {}, onDislike = {}, onOpenPlayer = {}, onShelfClick = {}
            )
        }
        snap("wave_playing")
    }

    @Test
    fun wavePaused() {
        show {
            HomeScreen(
                waveTrack = shown[0], isPlaying = false, isLoading = false, isLiked = false, progress = { 70_000L },
                shelf = waveShelfOf(true, shown.drop(1), emptyList()), shimmer = false,
                onPlayPause = {}, onLike = {}, onDislike = {}, onOpenPlayer = {}, onShelfClick = {}
            )
        }
        snap("wave_paused")
    }

    @Test
    fun waveIdle() {
        show {
            HomeScreen(
                waveTrack = null, isPlaying = false, isLoading = false, isLiked = false, progress = { 0L },
                shelf = waveShelfOf(false, emptyList(), shown), shimmer = false,
                onPlayPause = {}, onLike = {}, onDislike = {}, onOpenPlayer = {}, onShelfClick = {}
            )
        }
        snap("wave_idle")
    }

    /** First launch: nothing saved yet — the shelf keeps its place with placeholders. */
    @Test
    fun waveLoading() {
        show {
            HomeScreen(
                waveTrack = null, isPlaying = false, isLoading = true, isLiked = false, progress = { 0L },
                shelf = waveShelfOf(false, emptyList(), emptyList()), shimmer = false,
                onPlayPause = {}, onLike = {}, onDislike = {}, onOpenPlayer = {}, onShelfClick = {}
            )
        }
        snap("wave_loading")
    }

    @Test
    fun openedPlaylist() {
        show {
            PlaylistScreen(
                opened = OpenedPlaylist(playlists[0], shown, isLoading = false),
                currentTrackId = null, isPlaying = false, likedTrackIds = setOf("1"), downloadedTrackIds = emptySet(),
                contentBottom = 0.dp, onBack = {}, onPlay = {}, onTrackClick = {}, onToggleLike = {}, onRetry = {},
                onRename = {}, onDelete = {}
            )
        }
        snap("playlist")
    }

    @Test
    fun trackMenu() {
        show {
            TrackContextMenu(
                track = shown[0], inPlaylist = playlists[0], playlists = playlists, isLiked = false,
                startWithPlaylists = false, onDismiss = {}, onPlayNext = {}, onToggleLike = {}, onAddTo = {},
                onCreatePlaylist = {}, onRemoveFromPlaylist = {}
            )
        }
        snapScreen("track_menu")
    }

    @Test
    fun playlistPicker() {
        show {
            TrackContextMenu(
                track = shown[0], inPlaylist = null, playlists = playlists, isLiked = true,
                startWithPlaylists = true, onDismiss = {}, onPlayNext = {}, onToggleLike = {}, onAddTo = {},
                onCreatePlaylist = {}, onRemoveFromPlaylist = {}
            )
        }
        snapScreen("playlist_picker")
    }

    @Test
    fun accountsDialog() {
        val me = org.tegro.yamp.domain.model.UserAccount(uid = "1", login = "me", displayName = "Тестовый Аккаунт", token = "t")
        val other = org.tegro.yamp.domain.model.UserAccount(uid = "2", login = "car", displayName = "Машина", token = "t")
        show {
            org.tegro.yamp.presentation.account.AccountManagementDialog(
                accounts = listOf(me, other), defaultAccountUid = "1",
                onRemoveAccount = {}, onAddNewAccount = {}, onLogoutCurrent = {}, onLogoutAll = {}, onDismiss = {}
            )
        }
        snapScreen("dialog_accounts")
    }

    @Test
    fun updateDialog() {
        show {
            org.tegro.yamp.presentation.update.UpdateDialog(
                updateInfo = org.tegro.yamp.data.updater.UpdateCheckResult.UpdateAvailable(
                    versionName = "1.9.3", title = "YAMP 1.9.3", changelog = "• Лайк и дизлайк в шторке\n• Пропуск версии\n• Обложки быстрее",
                    apkDownloadUrl = "", apkSizeBytes = 8_000_000
                ),
                downloadProgress = -1f, onStartDownload = {}, onInstall = {}, onDismiss = {}, onSkip = {}
            )
        }
        snapScreen("dialog_update")
    }

    @Test
    fun deletePlaylistDialog() {
        show {
            org.tegro.yamp.presentation.main.components.DeletePlaylistDialog(playlistTitle = "В дорогу", onConfirm = {}, onDismiss = {})
        }
        snapScreen("dialog_delete_playlist")
    }

    private val settingsState = org.tegro.yamp.presentation.main.components.SettingsUiState(
        accountName = "Тестовый Аккаунт", hasPlus = true, crashReports = 0,
        crossfadeMs = 3000, reserveMb = 100, reserveUsedBytes = 64L * 1024 * 1024, cacheSizeBytes = 1_288_000_000L,
        equalizerAvailable = true,
        storage = org.tegro.yamp.data.download.StorageStatus(cardAvailable = true, onCard = false, cardFreeBytes = 21_400_000_000L)
    )

    private fun settings(name: String, page: org.tegro.yamp.presentation.main.components.SettingsPage? = null, state: org.tegro.yamp.presentation.main.components.SettingsUiState = settingsState) {
        show {
            // The real version comes from git and changes with every commit; the goldens need a fixed one.
            androidx.compose.runtime.CompositionLocalProvider(
                org.tegro.yamp.presentation.main.components.LocalAppVersion provides "1.12.2"
            ) {
                org.tegro.yamp.presentation.main.components.SettingsSheet(
                    state = state,
                    actions = org.tegro.yamp.presentation.main.components.SettingsActions(),
                    onDismiss = {},
                    initialPage = page
                )
            }
        }
        snapScreen(name)
    }

    @Test
    fun settingsSheet() = settings("settings")

    @Test
    @Config(qualifiers = "w411dp-h1100dp")
    fun settingsAccount() = settings("settings_account", org.tegro.yamp.presentation.main.components.SettingsPage.ACCOUNT)

    /** Settings → Accounts with two accounts: the "Group" switch shows (D64). */
    @Test
    @Config(qualifiers = "w411dp-h1100dp")
    fun settingsAccountMulti() = settings(
        "accounts_settings_account",
        org.tegro.yamp.presentation.main.components.SettingsPage.ACCOUNT,
        settingsState.copy(multiAccount = true)
    )

    // Tall windows: the whole page in one picture, so nothing below the fold goes unchecked
    @Test
    @Config(qualifiers = "w411dp-h2400dp")
    fun settingsPlayback() = settings("settings_playback", org.tegro.yamp.presentation.main.components.SettingsPage.PLAYBACK)

    /** A foldable cover screen (~360 dp), Russian: segmented controls wrap or stack, never cut a label. */
    @Test
    @Config(qualifiers = "ru-rRU-w360dp-h2400dp-port-xhdpi")
    fun settingsPlaybackFoldCoverRu() = settings("settings_playback_foldable_cover_ru", org.tegro.yamp.presentation.main.components.SettingsPage.PLAYBACK)

    /** A foldable inner screen on its side, Russian: «Переход» shows «Затухание и нарастание» in full. */
    @Test
    @Config(qualifiers = "ru-rRU-w829dp-h690dp-land-420dpi")
    fun settingsPlaybackFoldInnerRu() = settings("settings_playback_foldable_inner_ru", org.tegro.yamp.presentation.main.components.SettingsPage.PLAYBACK)

    /** Settings → Player (canvas `pl-settings`): the layout, the next tracks, likes by gestures. */
    @Test
    fun settingsPlayer() = settings(
        "settings_player",
        org.tegro.yamp.presentation.main.components.SettingsPage.PLAYER,
        settingsState.copy(playerCarLayout = true, playerNextThree = true)
    )

    /** Poweramp Equalizer installed: the hint for its per-app settings shows under the crossfade. */
    @Test
    @Config(qualifiers = "w411dp-h2400dp")
    fun settingsPlaybackPoweramp() = settings(
        "settings_playback_poweramp",
        org.tegro.yamp.presentation.main.components.SettingsPage.PLAYBACK,
        settingsState.copy(powerampEqualizer = true)
    )

    @Test
    @Config(qualifiers = "w411dp-h1800dp")
    fun settingsInterface() = settings("settings_interface", org.tegro.yamp.presentation.main.components.SettingsPage.INTERFACE)

    @Test
    @Config(qualifiers = "w411dp-h1100dp")
    fun settingsAbout() = settings("settings_about", org.tegro.yamp.presentation.main.components.SettingsPage.ABOUT)

    /** The Developer section (D101, canvas `px-settings-dev`, `px-dev-off`, `px-dev-on`, `px-dev-car`). */
    @Test
    fun settingsWithDeveloperSection() = settings(
        "settings_developer_section", state = settingsState.copy(developerSection = true)
    )

    @Test
    @Config(qualifiers = "w411dp-h1100dp")
    fun settingsDeveloperOff() = settings(
        "settings_developer_off", org.tegro.yamp.presentation.main.components.SettingsPage.DEVELOPER, settingsState.copy(developerSection = true)
    )

    @Test
    @Config(qualifiers = "ru-rRU-w411dp-h1100dp")
    fun settingsDeveloperOnRu() = settings(
        "settings_developer_on_ru", org.tegro.yamp.presentation.main.components.SettingsPage.DEVELOPER,
        settingsState.copy(developerSection = true, allowYandexWithoutPlus = true)
    )

    @Test
    @Config(qualifiers = HEAD_UNIT)
    fun settingsDeveloperHeadUnit() = settings(
        "head_settings_developer", org.tegro.yamp.presentation.main.components.SettingsPage.DEVELOPER,
        settingsState.copy(developerSection = true, allowYandexWithoutPlus = true, plusMissing = true, hasPlus = false)
    )

    @Test
    @Config(qualifiers = "w360dp-h2400dp")
    fun settingsPlaybackNarrow() = settings(
        "settings_playback_narrow",
        org.tegro.yamp.presentation.main.components.SettingsPage.PLAYBACK,
        settingsState.copy(storage = settingsState.storage.copy(onCard = true, moving = 37 to 214))
    )

    @Test
    @Config(qualifiers = "+land")
    fun settingsSheetLandscape() = settings("settings_landscape")

    @Test
    @Config(qualifiers = HEAD_UNIT)
    fun settingsHeadUnit() = settings("head_settings", org.tegro.yamp.presentation.main.components.SettingsPage.INTERFACE)

    @Test
    @Config(qualifiers = PHONE_ON_SIDE)
    fun settingsPhoneOnItsSide() = settings("phone_side_settings", org.tegro.yamp.presentation.main.components.SettingsPage.PLAYBACK)

    @Test
    fun miniPlayerAndBottomBar() {
        show {
            Column(Modifier.padding(top = 24.dp)) {
                MiniPlayer(
                    track = tracks[0].toTrack(), isPlaying = true, progress = { 60_000L },
                    onPlayPauseClick = {}, onNextClick = {}, onClick = {}, onDismiss = {}
                )
                FloatingGlassBottomBar(currentRoute = "library", onNavigate = {})
            }
        }
        snap("mini_player_bottom_bar")
    }

    @Composable
    private fun Player(
        track: TrackDetailsDto = tracks[0],
        isPlaying: Boolean = true,
        quality: String? = "MP3 · 320 кбит/с",
        queueOpen: Boolean = false,
        fetchingMore: Boolean = false,
        source: String = "Моя волна"
    ) {
        CompositionLocalProvider(org.tegro.yamp.utils.LocalStreamQuality provides quality) {
            FullScreenPlayer(
                track = track.toTrack(), isPlaying = isPlaying, isLiked = true, progress = { 82_000L }, queue = tracks.map { it.toTrack() },
                onPlayPauseClick = {}, onToggleLike = {}, onNextClick = {}, onPrevClick = {}, onSeekTo = {},
                onTrackSelect = {}, onRemoveFromQueue = {}, onCollapse = {}, onCloseAndClear = {}, onDislikeClick = {},
                sourceLabel = source, isFetchingMoreQueue = fetchingMore, initialQueueOpen = queueOpen
            )
        }
    }

    @Test
    fun fullPlayer() {
        show { Player() }
        snap("full_player")
    }

    @Test
    fun fullPlayerPaused() {
        show { Player(isPlaying = false, quality = null) }
        snap("full_player_paused")
    }

    /** Radio: the station card with "В ЭФИРЕ", the live line instead of the timeline, no "Далее". */
    @Test
    fun fullPlayerRadio() {
        show { Player(track = tracks[0].copy(id = JsonPrimitive("radio_europa"), title = "Европа Плюс", isRadio = true, artists = emptyList()), source = "Радио") }
        snap("full_player_radio")
    }

    @Test
    @Config(qualifiers = "+land")
    fun fullPlayerLandscape() {
        show { Player() }
        snap("full_player_land")
    }

    @Test
    fun playerQueue() {
        show { Player(queueOpen = true, fetchingMore = true) }
        snap("player_queue")
    }

    /** Bundled station logos on their cards: the 1.4.x "empty square" regression shows up here. */
    @Test
    fun radioStations() {
        val stations = RadioRepository(ApplicationProvider.getApplicationContext()).getBuiltinStations()
        show {
            RadioScreen(
                stations = stations, allStations = stations, selectedCategory = RadioCategory.ALL,
                onCategorySelect = {}, currentStationId = stations.first().id, isPlaying = true,
                favoriteStationIds = setOf(stations[1].id, stations[2].id), onStationClick = {}, onToggleFavorite = {}, onPlayPause = {}
            )
        }
        snap("radio")
    }

    /** The list carried up under the header, as a finger does it. */
    private fun scrollBy(px: Float) {
        compose.onRoot().performTouchInput {
            down(center)
            repeat(12) { moveBy(Offset(0f, -px / 12), delayMillis = 16) }
            advanceEventTime(200)
            up()
        }
        repeat(10) {
            compose.mainClock.advanceTimeBy(100)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun searchScrolled() {
        show {
            SearchScreen(
                query = "", onQueryChange = {}, results = emptyList(), weeklyHits = shown, trendTracks = shown,
                isSearching = false, currentTrackId = null, isPlaying = false, likedTrackIds = emptySet(),
                onTrackClick = { _, _ -> }, onToggleLike = {}, history = listOf("кино")
            )
        }
        scrollBy(500f)
        snap("search_scrolled")
    }

    @Test
    fun libraryScrolled() {
        show {
            LibraryScreen(
                tracks = shown, isLoading = false, currentTrackId = "1", isPlaying = true, likedTrackIds = setOf("1", "2", "3"), downloadedTrackIds = setOf("2"),
                downloadProgress = null, onTrackClick = {}, onPlayAllClick = {}, onShuffleClick = {},
                onDownloadAllClick = {}, onCancelDownload = {}, onToggleLike = {},
                playlists = playlists
            )
        }
        scrollBy(500f)
        snap("library_scrolled")
    }

    /** Down in the list: the station on air and its knob in the header, the genres pinned under it. */
    @Test
    fun radioScrolled() {
        val stations = RadioRepository(ApplicationProvider.getApplicationContext()).getBuiltinStations()
        show {
            RadioScreen(
                stations = stations, allStations = stations, selectedCategory = RadioCategory.ALL,
                onCategorySelect = {}, currentStationId = stations.first().id, isPlaying = true,
                favoriteStationIds = setOf(stations[1].id, stations[2].id), onStationClick = {}, onToggleFavorite = {}, onPlayPause = {}
            )
        }
        scrollBy(1100f)
        snap("radio_scrolled")
    }

    @Test
    fun waveWeakLink() {
        show {
            CompositionLocalProvider(LocalLinkState provides LinkState.WEAK) {
                HomeScreen(
                    waveTrack = shown[0], isPlaying = true, isLoading = false, isLiked = true, progress = { 70_000L },
                    shelf = waveShelfOf(true, shown.drop(1), emptyList()), shimmer = false,
                    onPlayPause = {}, onLike = {}, onDislike = {}, onOpenPlayer = {}, onShelfClick = {}
                )
            }
        }
        snap("wave_weak_link")
    }

    @Test
    fun playerNoConnection() {
        show { CompositionLocalProvider(LocalLinkState provides LinkState.NONE) { Player() } }
        snap("player_no_connection")
    }

    @Test
    @Config(qualifiers = "+land")
    fun carWeakLink() {
        show { CompositionLocalProvider(LocalLinkState provides LinkState.WEAK) { Car(isLandscape = true) } }
        snap("car_weak_link")
    }

    private val album = AlbumDto(
        id = JsonPrimitive(2), title = "Группа крови", year = 1988, trackCount = 3,
        artists = listOf(ArtistDto(id = JsonPrimitive(7), name = "Кино")), volumes = listOf(tracks)
    )

    private val longList = (1..12).map { track("$it", "Песня номер $it", "Кино") }

    /** A page on a side, as the app lays it out: tabs and mini-player along the bottom. */
    @Composable
    private fun Docked(route: String, mini: Boolean = true, page: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize()) {
            page()
            DockScrim(Modifier.align(Alignment.BottomCenter))
            FloatingGlassDock(
                currentRoute = route,
                onNavigate = {},
                modifier = Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = DockBottomGap),
                player = {
                    if (mini) MiniPlayer(
                        track = tracks[0].toTrack(), isPlaying = true, progress = { 60_000L },
                        onPlayPauseClick = {}, onNextClick = {}, onClick = {}, onDismiss = {}
                    )
                }
            )
        }
    }

    private fun dockedArtist(name: String) {
        show {
            Docked("library") {
                CatalogPageScreen(
                    state = CatalogPageState(
                        CatalogPage.Artist("7", "Кино"),
                        artist = ArtistBriefDto(ArtistDto(id = JsonPrimitive(7), name = "Кино"), longList, listOf(album)).toArtistParts().toArtistPage(),
                        isLoading = false
                    ),
                    currentTrackId = "1", isPlaying = true, likedTrackIds = setOf("1"), contentBottom = DockClearance, actions = catalogActions
                )
            }
        }
        snap(name)
    }

    @Test
    @Config(qualifiers = HEAD_UNIT)
    fun headUnitArtist() = dockedArtist("head_artist")

    @Test
    @Config(qualifiers = OLD_HEAD_UNIT)
    fun oldHeadUnitArtist() = dockedArtist("head_old_artist")

    @Test
    @Config(qualifiers = PHONE_ON_SIDE)
    fun phoneOnItsSideArtist() = dockedArtist("phone_side_artist")

    private fun dockedPlaylist(name: String, opened: OpenedPlaylist) {
        show {
            Docked("library") {
                PlaylistScreen(
                    opened = opened,
                    currentTrackId = null, isPlaying = false, likedTrackIds = setOf("1"), downloadedTrackIds = emptySet(),
                    contentBottom = DockClearance, onBack = {}, onPlay = {}, onTrackClick = {}, onToggleLike = {}, onRetry = {},
                    onRename = {}, onDelete = {}
                )
            }
        }
        snap(name)
    }

    @Test
    @Config(qualifiers = HEAD_UNIT)
    fun headUnitPlaylist() = dockedPlaylist("head_playlist", OpenedPlaylist(playlists[0], longList.map { it.toTrack() }, isLoading = false))

    @Test
    @Config(qualifiers = HEAD_UNIT)
    fun headUnitPlaylistFailed() = dockedPlaylist(
        "head_playlist_failed",
        OpenedPlaylist(playlists[0], isLoading = false, failed = true, failReason = "HTTP 400 playlistIdBindingError")
    )

    private fun dockedWave(name: String) {
        show {
            Docked("home", mini = false) {
                HomeScreen(
                    waveTrack = shown[0], isPlaying = true, isLoading = false, isLiked = true, progress = { 70_000L },
                    shelf = waveShelfOf(true, shown.drop(1), emptyList()), shimmer = false,
                    onPlayPause = {}, onLike = {}, onDislike = {}, onOpenPlayer = {}, onShelfClick = {},
                    contentBottom = DockClearance
                )
            }
        }
        snap(name)
    }

    @Test
    @Config(qualifiers = HEAD_UNIT)
    fun headUnitWave() = dockedWave("head_wave")

    @Test
    @Config(qualifiers = PHONE_ON_SIDE)
    fun phoneOnItsSideWave() = dockedWave("phone_side_wave")

    @Test
    @Config(qualifiers = HEAD_UNIT)
    fun headUnitLibrary() {
        show {
            Docked("library") {
                LibraryScreen(
                    tracks = longList.map { it.toTrack() }, isLoading = false, currentTrackId = "1", isPlaying = true, likedTrackIds = setOf("1"), downloadedTrackIds = emptySet(),
                    downloadProgress = null, onTrackClick = {}, onPlayAllClick = {}, onShuffleClick = {},
                    onDownloadAllClick = {}, onCancelDownload = {}, onToggleLike = {}
                )
            }
        }
        snap("head_library")
    }

    @Test
    @Config(qualifiers = HEAD_UNIT)
    fun headUnitSearch() {
        show {
            Docked("search") {
                SearchScreen(
                    query = "", onQueryChange = {}, results = emptyList(), weeklyHits = longList.map { it.toTrack() }, trendTracks = longList.map { it.toTrack() },
                    isSearching = false, currentTrackId = null, isPlaying = false, likedTrackIds = emptySet(),
                    onTrackClick = { _, _ -> }, onToggleLike = {}, history = listOf("кино")
                )
            }
        }
        snap("head_search")
    }

    @Test
    @Config(qualifiers = HEAD_UNIT)
    fun headUnitRadio() {
        val stations = RadioRepository(ApplicationProvider.getApplicationContext()).getBuiltinStations()
        show {
            Docked("radio") {
                RadioScreen(
                    stations = stations, allStations = stations, selectedCategory = RadioCategory.ALL,
                    onCategorySelect = {}, currentStationId = stations.first().id, isPlaying = true,
                    favoriteStationIds = setOf(stations[1].id, stations[2].id), onStationClick = {}, onToggleFavorite = {}, onPlayPause = {}
                )
            }
        }
        snap("head_radio")
    }

    @Test
    fun artistPage() {
        show {
            CatalogPageScreen(
                state = CatalogPageState(
                    CatalogPage.Artist("7", "Кино"),
                    artist = ArtistBriefDto(ArtistDto(id = JsonPrimitive(7), name = "Кино"), tracks, listOf(album, album.copy(id = JsonPrimitive(3), title = "Звезда по имени Солнце", year = 1989))).toArtistParts().toArtistPage(),
                    isLoading = false
                ),
                currentTrackId = "1", isPlaying = true, likedTrackIds = setOf("1"), contentBottom = 0.dp, actions = catalogActions
            )
        }
        snap("artist")
    }

    @Test
    fun albumPage() {
        show {
            CatalogPageScreen(
                state = CatalogPageState(CatalogPage.Album("2", "Группа крови"), album = album.toAlbum(), isLoading = false),
                currentTrackId = null, isPlaying = false, likedTrackIds = emptySet(), contentBottom = 0.dp, actions = catalogActions
            )
        }
        snap("album")
    }

    @Test
    fun searchWithArtistsAndAlbums() {
        show {
            SearchScreen(
                query = "кино", onQueryChange = {}, results = shown, weeklyHits = emptyList(), trendTracks = emptyList(),
                isSearching = false, currentTrackId = null, isPlaying = false, likedTrackIds = emptySet(),
                onTrackClick = { _, _ -> }, onToggleLike = {},
                artists = listOf(ArtistDto(id = JsonPrimitive(7), name = "Кино"), ArtistDto(id = JsonPrimitive(8), name = "Кино и немцы")).mapNotNull { it.toArtist() },
                albums = listOf(album).mapNotNull { it.toAlbum() }
            )
        }
        snap("search_results")
    }

    @Test
    fun searchHistory() {
        show {
            SearchScreen(
                query = "", onQueryChange = {}, results = emptyList(), weeklyHits = shown, trendTracks = shown,
                isSearching = false, currentTrackId = null, isPlaying = false, likedTrackIds = emptySet(),
                onTrackClick = { _, _ -> }, onToggleLike = {}, history = listOf("кино", "ария", "radio record")
            )
        }
        snap("search_history")
    }

    @Test
    fun searchRecentPanelOpen() {
        show {
            SearchScreen(
                query = "", onQueryChange = {}, results = emptyList(), weeklyHits = shown, trendTracks = shown,
                isSearching = false, currentTrackId = null, isPlaying = false, likedTrackIds = emptySet(),
                onTrackClick = { _, _ -> }, onToggleLike = {}, history = listOf("кино", "ария", "radio record"),
                initiallyShowHistory = true
            )
        }
        snap("search_recent_open")
    }

    // --- Phase 3b (D81): the Next states and the buffered part (canvas: Design/yamp/buffering.jsx) ---

    @Composable
    private fun WithNext(look: org.tegro.yamp.presentation.player.NextLook, bufferedMs: Long, content: @Composable () -> Unit) =
        CompositionLocalProvider(
            org.tegro.yamp.presentation.player.LocalPlaybackAux provides
                org.tegro.yamp.presentation.player.PlaybackAux(next = { look }, buffered = { bufferedMs }),
            content = content
        )

    @Test
    fun fullPlayerNextLoading() {
        show { WithNext(org.tegro.yamp.presentation.player.NextLook.LOADING, 150_000L) { Player() } }
        snap("next_full_player_loading")
    }

    @Test
    fun fullPlayerNextFailed() {
        show { WithNext(org.tegro.yamp.presentation.player.NextLook.FAILED, 150_000L) { Player() } }
        snap("next_full_player_failed")
    }

    /** Ready, and the whole file is on disk: the bar's loaded part runs to the end. */
    @Test
    fun fullPlayerNextReadyOnDisk() {
        show { WithNext(org.tegro.yamp.presentation.player.NextLook.READY, Long.MAX_VALUE) { Player() } }
        snap("next_full_player_ready_disk")
    }

    @Test
    fun miniPlayerNextStates() {
        show {
            Column(Modifier.padding(top = 24.dp)) {
                listOf(
                    org.tegro.yamp.presentation.player.NextLook.READY to 170_000L,
                    org.tegro.yamp.presentation.player.NextLook.LOADING to 120_000L,
                    org.tegro.yamp.presentation.player.NextLook.FAILED to 120_000L
                ).forEach { (look, buffered) ->
                    WithNext(look, buffered) {
                        MiniPlayer(
                            track = tracks[0].toTrack(), isPlaying = true, progress = { 60_000L },
                            onPlayPauseClick = {}, onNextClick = {}, onClick = {}, onDismiss = {}
                        )
                    }
                }
            }
        }
        snap("next_mini_player_states")
    }

    @Test
    @Config(qualifiers = WIDE_HEAD_UNIT)
    fun carWideNextLoading() {
        show { WithNext(org.tegro.yamp.presentation.player.NextLook.LOADING, 140_000L) { Car(isLandscape = true) } }
        snap("next_car_wide_loading")
    }

    @Test
    @Config(qualifiers = WIDE_HEAD_UNIT)
    fun carWideNextFailed() {
        show { WithNext(org.tegro.yamp.presentation.player.NextLook.FAILED, 140_000L) { Car(isLandscape = true) } }
        snap("next_car_wide_failed")
    }

    // --- Canvas 5e: seeking a converted stream before its disk copy is complete (Design/yamp/seek.jsx) ---

    /** The hollow ring instead of the bead, the copy's share (63%) as the loaded part, the played part dimmed. */
    @Composable
    private fun SeekLocked(content: @Composable () -> Unit) =
        CompositionLocalProvider(
            org.tegro.yamp.presentation.player.LocalPlaybackAux provides org.tegro.yamp.presentation.player.PlaybackAux(
                next = { org.tegro.yamp.presentation.player.NextLook.READY }, buffered = { 100_000L }, seekLock = { 0.63f }
            ),
            content = content
        )

    @Test
    fun seekLockedPortrait() {
        show { SeekLocked { Player() } }
        snap("seek_locked_portrait")
    }

    @Test
    @Config(qualifiers = "+land")
    fun seekLockedLandscape() {
        show { SeekLocked { Player() } }
        snap("seek_locked_land")
    }

    @Test
    @Config(qualifiers = WIDE_HEAD_UNIT)
    fun seekLockedCarLeftHand() {
        show { SeekLocked { Car(isLandscape = true) } }
        snap("seek_locked_car_left")
    }

    @Test
    @Config(qualifiers = WIDE_HEAD_UNIT)
    fun seekLockedCarRightHand() {
        show { SeekLocked { Car(isLandscape = true, steeringWheelOnLeft = false) } }
        snap("seek_locked_car_right")
    }

    /** A split pane on the head unit (1280 wide): the loading ring and the loaded part at the car's scale. */
    @Test
    @Config(qualifiers = HEAD_UNIT)
    fun carSplitNextLoading() {
        show { WithNext(org.tegro.yamp.presentation.player.NextLook.LOADING, 140_000L) { Car(isLandscape = true) } }
        snap("next_car_split_loading")
    }

    private val catalogActions = CatalogActions({}, {}, { _, _ -> }, { _, _ -> }, { _, _, _ -> }, {})

    @Test
    fun carPortrait() {
        show { Car(isLandscape = false) }
        snap("car_portrait")
    }

    @Test
    @Config(qualifiers = "+land")
    fun carLandscape() {
        show { Car(isLandscape = true) }
        snap("car_landscape")
    }

    @Test
    @Config(qualifiers = "+land")
    fun carLandscapeRightHandDrive() {
        show { Car(isLandscape = true, steeringWheelOnLeft = false) }
        snap("car_landscape_right_wheel")
    }

    /** A head unit: 1920×720, a flat 2.67:1 screen. */
    @Test
    @Config(qualifiers = WIDE_HEAD_UNIT)
    fun carWideHeadUnit() {
        show { Car(isLandscape = true) }
        snap("car_wide_head_unit")
    }

    @Test
    @Config(qualifiers = WIDE_HEAD_UNIT)
    fun carWideHeadUnitRightHandDrive() {
        show { Car(isLandscape = true, steeringWheelOnLeft = false) }
        snap("car_wide_head_unit_right_wheel")
    }

    @Test
    @Config(qualifiers = WIDE_HEAD_UNIT)
    fun settingsWideHeadUnit() = settings("wide_head_settings", org.tegro.yamp.presentation.main.components.SettingsPage.INTERFACE)

    @Test
    @Config(qualifiers = "+land")
    fun carLandscapeUiScaled() {
        // Enlarged UI, as the global density override produces for a low-reported-density screen.
        show {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density * 1.4f, base.fontScale)) {
                Car(isLandscape = true)
            }
        }
        snap("car_landscape_scaled")
    }

    @Composable
    @Suppress("UNUSED_PARAMETER") // standing, the Car layout is the phone player (the wide layout needs a wide screen)
    private fun Car(isLandscape: Boolean, steeringWheelOnLeft: Boolean = true, track: TrackDetailsDto = tracks[0]) =
        CarPlayerForTest(
            track = track.toTrack(), queue = listOf(track, tracks[2], tracks[1], tracks[0]).map { it.toTrack() },
            upcoming = listOf(tracks[2], tracks[1], tracks[0]).map { it.toTrack() }, steeringWheelOnLeft = steeringWheelOnLeft
        )

    /** Radio in the car: no timeline, the on-air line instead. */
    @Test
    @Config(qualifiers = "+land")
    fun carRadio() {
        show { Car(isLandscape = true, track = tracks[0].copy(id = JsonPrimitive("radio_europa"), title = "Европа Плюс", isRadio = true)) }
        snap("car_radio")
    }

    private companion object {
        /** 21.10.2026 10:30 MSK: the car clock in the screenshots. */
        const val FIXED_TIME = 1_792_567_800_000L
        const val HEAD_UNIT = "w1280dp-h720dp-land-mdpi"
        /** A head unit: 1920×720 at mdpi. */
        const val WIDE_HEAD_UNIT = "w1920dp-h720dp-land-mdpi"
        const val OLD_HEAD_UNIT = "w1024dp-h600dp-land-mdpi"
        const val PHONE_ON_SIDE = "w800dp-h360dp-land-xhdpi"
    }
}
