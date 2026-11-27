package org.tegro.yamp.presentation

import android.app.Application
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
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
import org.tegro.yamp.R
import org.tegro.yamp.data.provider.local.LocalOverview
import org.tegro.yamp.data.provider.local.LocalVolume
import org.tegro.yamp.data.provider.local.SkippedFile
import org.tegro.yamp.data.provider.local.SourceState
import org.tegro.yamp.data.provider.local.SourceStatus
import org.tegro.yamp.domain.account.AccountId
import org.tegro.yamp.domain.account.Provider
import org.tegro.yamp.domain.account.ProviderAccount
import org.tegro.yamp.domain.account.toProviderAccount
import org.tegro.yamp.domain.media.Album
import org.tegro.yamp.domain.media.ArtistBrief
import org.tegro.yamp.domain.media.Folder
import org.tegro.yamp.domain.media.FolderLevel
import org.tegro.yamp.domain.media.Track
import org.tegro.yamp.domain.model.UserAccount
import org.tegro.yamp.domain.queue.TrackRef
import org.tegro.yamp.presentation.account.AccountManagementDialog
import org.tegro.yamp.presentation.account.AddAccountChooser
import org.tegro.yamp.presentation.account.JellyfinAccountRow
import org.tegro.yamp.presentation.account.LocalAccountRow
import org.tegro.yamp.presentation.account.providerAccountTags
import org.tegro.yamp.presentation.car.CarLibraryCallbacks
import org.tegro.yamp.presentation.car.CarLibraryScreen
import org.tegro.yamp.presentation.car.CarLibraryState
import org.tegro.yamp.presentation.car.CarNowPlaying
import org.tegro.yamp.presentation.car.CarTab
import org.tegro.yamp.presentation.car.CarTile
import org.tegro.yamp.presentation.catalog.AccountLibrary
import org.tegro.yamp.presentation.catalog.CatalogActions
import org.tegro.yamp.presentation.catalog.CatalogPageState
import org.tegro.yamp.presentation.catalog.FolderPage
import org.tegro.yamp.presentation.catalog.LibraryRow
import org.tegro.yamp.presentation.folders.FolderActions
import org.tegro.yamp.presentation.folders.FolderPageScreen
import org.tegro.yamp.presentation.hifi.ArtworkTint
import org.tegro.yamp.presentation.hifi.HiFiBackdrop
import org.tegro.yamp.presentation.hifi.LocalArtworkTint
import org.tegro.yamp.presentation.main.LibraryScreen
import org.tegro.yamp.presentation.player.QueueSheet
import org.tegro.yamp.presentation.player.QueueSplit
import org.tegro.yamp.presentation.player.QueueSheetState
import org.tegro.yamp.presentation.sources.LocalAbsentVolumes
import org.tegro.yamp.presentation.sources.SourcesActions
import org.tegro.yamp.presentation.sources.SourcesDialog
import org.tegro.yamp.presentation.sources.localAccountLines
import org.tegro.yamp.provider.local.LocalRefs
import org.tegro.yamp.ui.theme.YandexMusicTheme

/**
 * Phase 4d step 8, per the approved boards (canvas section 7b, `lo-*`): the add chooser, Account management with
 * "This device", Sources (statuses, scan progress, an absent stick, the skipped files, its menu, the empty state),
 * the Library section, the folders root with the volumes and an absent stick, the car tabs, the queue with entries of
 * an absent stick. Phone, narrow phone, and the 1760×720 head unit with both hands. Sample data is invented.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = RobolectricDeviceQualifiers.Pixel7, application = Application::class)
class LocalScreenshotsTest {

    @get:Rule
    val compose = createComposeRule()

    private val tint = ArtworkTint(Color(0xFF2F6B4C), Color(0xFF173A28))

    private val options = RoborazziOptions(
        recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5),
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.005f)
    )

    private fun show(absent: Map<String, String> = emptyMap(), content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            YandexMusicTheme {
                CompositionLocalProvider(LocalArtworkTint provides tint, LocalAbsentVolumes provides absent) {
                    Box(Modifier.fillMaxSize()) {
                        HiFiBackdrop(tint = tint)
                        content()
                    }
                }
            }
        }
        idle()
    }

    private fun idle() = repeat(10) {
        compose.mainClock.advanceTimeBy(100)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun snap(name: String) = captureScreenRoboImage("src/test/screenshots/$name.png", options)

    // ── Sample data (local.jsx `LoUseSources`) ───────────────────────────────────
    private val sd = "6917-1912"
    private val usbS = "A1B2-C3D4"
    private val usbK = "1A2B-3C4D"
    private val now = System.currentTimeMillis()
    private val sources = listOf(
        SourceStatus(1, LocalVolume.PRIMARY, "Phone", "Music", SourceState.AVAILABLE, 1240, lastScanAt = now - 5 * 60_000),
        SourceStatus(2, sd, "SD card", "Music", SourceState.SCANNING, 1930, percent = 62, done = 1922, total = 3100, scanFolder = "Music/Hollow Pines/2019 - Copper Sky"),
        SourceStatus(3, usbS, "USB “SANDISK”", "Music", SourceState.AVAILABLE, 860, lastScanAt = now - 26 * 3_600_000),
        SourceStatus(4, usbK, "USB “KINGSTON”", "Music", SourceState.ABSENT, 2410, lastSeenAt = now - 3 * 86_400_000, excludes = listOf("Music/Podcasts", "Music/Ringtones"))
    )
    private val skipped = mapOf(
        "ape" to List(7) { SkippedFile("SD card", "Music/Static Bloom/2016 - Low Orbit/0${it + 1} Track.ape") },
        "dsf" to listOf(SkippedFile("SD card", "Music/Hi-Res/Night Drive.dsf"), SkippedFile("SD card", "Music/Hi-Res/Tide.dsf")),
        "wv" to List(3) { SkippedFile("SD card", "Music/Field Notes/Live/0${it + 1} Morning Tide.wv") }
    )
    private val overview = LocalOverview(sources, 6440, skipped)
    private val sourcesActions = SourcesActions({}, {}, {}, { _, _ -> }, { _, _ -> }, {}, {})

    private val anna = UserAccount(uid = "1", login = "anna", displayName = "Anna", token = "t")
    private val home = ProviderAccount(Provider.JELLYFIN, AccountId.jellyfin("srv1", "u1"), "Home Media", null, false)
    private val device = ProviderAccount(Provider.LOCAL, AccountId.local(), "This device", null, false)
    private val tags = providerAccountTags(listOf(anna.toProviderAccount(), home, device))
    private val quality = org.tegro.yamp.presentation.account.AccountQualityUi(
        choices = emptyMap(), global = false, nowMetered = false, onSet = { _, _, _ -> }, onOpenWifi = {}
    )

    @Composable
    private fun Accounts(wheelLeft: Boolean = true, scanning: Boolean = false) {
        val shown = if (scanning) LocalOverview(listOf(sources[1].copy(id = 9, volumeName = "Phone", volumeId = LocalVolume.PRIMARY, percent = 12)), 0) else overview
        val (sub, status) = localAccountLines(shown)
        AccountManagementDialog(
            accounts = listOf(anna), defaultAccountUid = "1",
            onRemoveAccount = {}, onAddNewAccount = {}, onLogoutCurrent = {}, onLogoutAll = {}, onDismiss = {},
            jellyfinAccounts = listOf(JellyfinAccountRow(tags[1], "Jellyfin 10.10.3 · music.example.org")),
            quality = quality,
            order = listOf(AccountId.yandex("1").value, home.id.value, AccountId.local().value),
            wheelLeft = wheelLeft,
            local = LocalAccountRow("This device", sub, status, warning = !scanning)
        )
    }

    // ── 1 · Adding a source ──────────────────────────────────────────────────────
    @Test
    fun addChooserWithThisDevice() {
        show { AddAccountChooser(onYandex = {}, onJellyfin = {}, onDismiss = {}, onLocal = {}) }
        snap("local_add_chooser")
    }

    @Test
    fun accountsWithThisDeviceScanning() {
        show { Accounts(scanning = true) }
        snap("local_accounts")
    }

    @Test
    @Config(qualifiers = "w1173dp-h480dp-land-hdpi")
    fun accountsWithThisDeviceHeadLeft() {
        show { Accounts(wheelLeft = true) }
        snap("local_accounts_head_left")
    }

    @Test
    @Config(qualifiers = "w1173dp-h480dp-land-hdpi")
    fun accountsWithThisDeviceHeadRight() {
        show { Accounts(wheelLeft = false) }
        snap("local_accounts_head_right")
    }

    // ── 2 · Sources ──────────────────────────────────────────────────────────────
    @Test
    fun sources() {
        show { SourcesDialog(overview, big = false, wheelLeft = true, actions = sourcesActions) }
        snap("local_sources")
    }

    @Test
    @Config(qualifiers = "ru-rRU-w360dp-h740dp-port-xhdpi")
    fun sourcesNarrowRussian() {
        show { SourcesDialog(overview, big = false, wheelLeft = true, actions = sourcesActions) }
        snap("local_sources_narrow_ru")
    }

    @Test
    fun sourceMenu() {
        show { SourcesDialog(overview, big = false, wheelLeft = true, actions = sourcesActions) }
        val more = ApplicationProvider.getApplicationContext<Application>().getString(R.string.cd_account_menu)
        compose.onAllNodes(hasContentDescription(more))[1].performClick()
        idle()
        snap("local_sources_menu")
    }

    @Test
    fun sourcesEmpty() {
        show { SourcesDialog(LocalOverview(), big = false, wheelLeft = true, actions = sourcesActions) }
        snap("local_sources_empty")
    }

    @Test
    @Config(qualifiers = "w1173dp-h480dp-land-hdpi")
    fun sourcesHeadLeft() {
        show { SourcesDialog(overview, big = true, wheelLeft = true, actions = sourcesActions) }
        snap("local_sources_head_left")
    }

    @Test
    @Config(qualifiers = "w1173dp-h480dp-land-hdpi")
    fun sourcesHeadRightWithTheMenu() {
        show { SourcesDialog(overview, big = true, wheelLeft = false, actions = sourcesActions) }
        snap("local_sources_head_right")
        val more = ApplicationProvider.getApplicationContext<Application>().getString(R.string.cd_account_menu)
        compose.onAllNodes(hasContentDescription(more))[1].performClick()
        idle()
        snap("local_sources_head_right_menu")
    }

    // ── 3 · Library section ──────────────────────────────────────────────────────
    private val annaTag = tags[0]
    private val ySection = AccountLibrary(AccountId.yandex("1"), "Anna", "Yandex", mapOf(LibraryRow.ALBUMS to 140, LibraryRow.ARTISTS to 58), inContext = true)
    private val jfSection = AccountLibrary(home.id, "Home Media", "Jellyfin", mapOf(LibraryRow.LIKED to 87, LibraryRow.ALBUMS to 486, LibraryRow.FOLDERS to 34))
    private val localSection = AccountLibrary(
        AccountId.local(), "This device", "This device",
        mapOf(LibraryRow.LIKED to 24, LibraryRow.ALBUMS to 214, LibraryRow.ARTISTS to 96, LibraryRow.FOLDERS to 4), local = overview
    )

    @Composable
    private fun Library(sections: List<AccountLibrary>, yandex: Boolean) = LibraryScreen(
        tracks = emptyList(), isLoading = false, currentTrackId = null, isPlaying = false, likedTrackIds = emptySet(),
        downloadedTrackIds = emptySet(), downloadProgress = null, onTrackClick = {}, onPlayAllClick = {}, onShuffleClick = {},
        onDownloadAllClick = {}, onCancelDownload = {}, onToggleLike = {}, sections = sections, accountTags = tags,
        yandex = yandex, historyCount = 12
    )

    @Test
    fun librarySection() {
        show { Library(listOf(ySection, jfSection, localSection), yandex = true) }
        // Scrolled to the end, as canvas `lo-lib`: This device after Home Media (account order)
        val scroll = compose.onNode(androidx.compose.ui.test.hasScrollAction()).fetchSemanticsNode()
        compose.runOnUiThread { scroll.config[androidx.compose.ui.semantics.SemanticsActions.ScrollBy].action?.invoke(0f, 10_000f) }
        idle()
        snap("local_library")
    }

    @Test
    fun libraryOnlyThisDevice() {
        show { Library(listOf(localSection.copy(counts = localSection.counts - LibraryRow.LIKED)), yandex = false) }
        snap("local_library_only")
    }

    @Test
    @Config(qualifiers = "w1173dp-h480dp-land-hdpi")
    fun headLibraryColumns() {
        show { Library(listOf(ySection, jfSection, localSection), yandex = true) }
        snap("local_head_library")
    }

    // ── 4 · Folders: the volumes at the root, an absent stick ────────────────────
    private val folderActions = FolderActions(onPlay = { _, _, _ -> }, onOpen = {}, onJump = {}, onPlayTracks = { _, _, _ -> })
    private val catalogActions = CatalogActions(onBack = {}, onRetry = {}, onOpenArtist = { _, _ -> }, onOpenAlbum = { _, _ -> }, onPlay = { _, _, _ -> }, onToggleLike = {})
    private val volumeFolders = listOf(
        Folder(LocalRefs.folder(LocalVolume.PRIMARY, ""), "Phone", null, trackCount = 1240),
        Folder(LocalRefs.folder(sd, ""), "SD card", null, trackCount = 1930),
        Folder(LocalRefs.folder(usbS, ""), "USB “SANDISK”", null, trackCount = 860),
        Folder(LocalRefs.folder(usbK, ""), "USB “KINGSTON”", null, trackCount = 2410, available = false)
    )
    private val deviceTag = tags[2]

    @Test
    fun foldersRootWithTheVolumes() {
        val state = CatalogPageState(page = FolderPage(null, "Folders", AccountId.local()), folder = FolderLevel(volumeFolders, emptyList()), isLoading = false)
        show(absent = mapOf(usbK to "USB “KINGSTON”")) { FolderPageScreen(state, emptyList(), deviceTag, 160.dp, catalogActions, folderActions) }
        snap("local_folders_root")
    }

    @Test
    fun anAbsentStickOpened() {
        fun f(name: String, tracks: Int) = Folder(LocalRefs.folder(usbK, "Music/$name"), name, null, trackCount = tracks, available = false)
        val page = FolderPage(null, "Folders", AccountId.local()).child(LocalRefs.folder(usbK, "").id, "USB “KINGSTON”")
        val state = CatalogPageState(page = page, folder = FolderLevel(listOf(f("Albums", 1980), f("Audiobooks", 64), f("Lumen Lane - Live", 41), f("YouTube", 325)), emptyList()), isLoading = false)
        show(absent = mapOf(usbK to "USB “KINGSTON”")) { FolderPageScreen(state, emptyList(), deviceTag, 160.dp, catalogActions, folderActions) }
        snap("local_folders_absent")
    }

    @Test
    fun anUntaggedFolder() {
        val untagged = listOf(
            "Lumen Lane - Night Drive (Official Audio)", "Hollow Pines – Copper Sky [live at home]", "The Quiet Coast - Paper Lanterns",
            "Static Bloom - Low Orbit (lyrics)", "Field Notes - Morning Tide", "Северный ветер - Дорога домой"
        ).mapIndexed { i, title ->
            Track(TrackRef(LocalRefs.track(sd, "Music/YouTube/$i.m4a").value), title, listOf(ArtistBrief(LocalRefs.artist(""), "Unknown artist")), null, 241_000L, null, explicit = false)
        }
        val page = FolderPage(null, "Folders", AccountId.local()).child(LocalRefs.folder(sd, "").id, "SD card").child(LocalRefs.folder(sd, "Music/YouTube").id, "YouTube")
        show { FolderPageScreen(CatalogPageState(page = page, folder = FolderLevel(emptyList(), untagged), isLoading = false), emptyList(), deviceTag, 160.dp, catalogActions, folderActions) }
        snap("local_folders_untagged")
    }

    // ── 5 · Car Library: This device's tabs ──────────────────────────────────────
    private val carCallbacks = CarLibraryCallbacks({}, {}, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})

    @Composable
    private fun Car(left: Boolean, tab: CarTab, tiles: List<CarTile>) = CarLibraryScreen(
        state = CarLibraryState(AccountId.local(), tab, tiles, loading = false),
        controlsOnLeft = left,
        account = deviceTag,
        accounts = tags,
        nowPlaying = CarNowPlaying("Night Drive", "Lumen Lane", null, isPlaying = true),
        panel = null,
        queues = emptyList(),
        callbacks = carCallbacks,
        pressed = null
    )

    private val carVolumes = volumeFolders.map { CarTile.OfFolder(it, pinned = false) } +
        listOf(CarTile.OfFolder(Folder(LocalRefs.folder(LocalVolume.PRIMARY, "Music/Audiobooks"), "Audiobooks", null, trackCount = 212), pinned = true))

    @Test
    @Config(qualifiers = "w1760dp-h720dp-land-mdpi")
    fun carFoldersLeft() {
        show { Car(left = true, tab = CarTab.FOLDERS, tiles = carVolumes) }
        snap("local_car_folders_left")
    }

    @Test
    @Config(qualifiers = "w1760dp-h720dp-land-mdpi")
    fun carFoldersRight() {
        show { Car(left = false, tab = CarTab.FOLDERS, tiles = carVolumes) }
        snap("local_car_folders_right")
    }

    @Test
    @Config(qualifiers = "w1760dp-h720dp-land-mdpi")
    fun carAlbumsRight() {
        fun a(title: String, artist: String?, qualifier: String? = null, available: Boolean = true) = CarTile.OfAlbum(
            Album(LocalRefs.album(title + qualifier), title, listOfNotNull(artist).map { ArtistBrief(null, it) }, null, 2019, 10, qualifier = qualifier, available = available)
        )
        val tiles = listOf(
            a("Copper Sky", "Hollow Pines", "SD card"), a("Copper Sky", "Hollow Pines", "USB “SANDISK”"), a("Field Notes", "Field Notes"),
            a("Lumen Lane - Live", "Lumen Lane", available = false), a("Night Drive", "Lumen Lane"), a("YouTube", null), a("Дорога домой", "Северный ветер")
        )
        show { Car(left = false, tab = CarTab.ALBUMS, tiles = tiles) }
        snap("local_car_albums_right")
    }

    // ── 7 · Queue: entries on an absent stick ────────────────────────────────────
    @Test
    fun queueWithAnAbsentStick() {
        fun t(volume: String, title: String, artist: String) =
            Track(TrackRef(LocalRefs.track(volume, "Music/$title.mp3").value), title, listOf(ArtistBrief(null, artist)), null, 241_000L, null, explicit = false)
        val split = QueueSplit(
            current = t(sd, "Night Drive", "Lumen Lane"),
            next = listOf(
                t(sd, "Copper Sky", "Hollow Pines"), t(usbK, "Low Orbit", "Static Bloom"), t(sd, "Paper Lanterns", "The Quiet Coast"),
                t(usbK, "Headlights", "Lumen Lane"), t(usbK, "Exit 14", "Lumen Lane"), t(sd, "Morning Tide", "Field Notes")
            )
        )
        show(absent = mapOf(usbK to "USB “KINGSTON”")) {
            QueueSheet(
                state = QueueSheetState(initiallyOpen = true), split = split, isPlaying = true, sourceLabel = "This device",
                isFetchingMore = false, fullHeight = false, onDismiss = {}, onTrackSelect = {}, onRemove = {}, onMove = { _, _ -> }, onLoadMore = {},
                account = deviceTag
            )
        }
        snap("local_queue")
    }
}
