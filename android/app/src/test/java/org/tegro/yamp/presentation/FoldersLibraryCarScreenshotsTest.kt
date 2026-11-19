package org.tegro.yamp.presentation

import android.app.Application
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import org.tegro.yamp.data.folders.PinnedFolder
import org.tegro.yamp.domain.account.AccountId
import org.tegro.yamp.domain.media.Album
import org.tegro.yamp.domain.media.ArtistBrief
import org.tegro.yamp.domain.media.Folder
import org.tegro.yamp.domain.media.FolderLevel
import org.tegro.yamp.domain.media.Track
import org.tegro.yamp.domain.queue.MediaRef
import org.tegro.yamp.domain.queue.QueueKind
import org.tegro.yamp.domain.queue.TrackRef
import org.tegro.yamp.presentation.account.AccountTag
import org.tegro.yamp.presentation.car.CarAlbumPage
import org.tegro.yamp.presentation.car.CarLibraryCallbacks
import org.tegro.yamp.presentation.car.CarLibraryScreen
import org.tegro.yamp.presentation.car.CarLibraryState
import org.tegro.yamp.presentation.car.CarNowPlaying
import org.tegro.yamp.presentation.car.CarPanel
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
import org.tegro.yamp.presentation.main.HomeJellyfinActions
import org.tegro.yamp.presentation.main.HomeJellyfinGroup
import org.tegro.yamp.presentation.main.HomeJellyfinGroupView
import org.tegro.yamp.presentation.main.LibraryScreen
import org.tegro.yamp.presentation.media.MediaMenuContent
import org.tegro.yamp.presentation.media.MediaMenuRow
import org.tegro.yamp.presentation.media.MediaTarget
import org.tegro.yamp.presentation.media.carMediaMenuRows
import org.tegro.yamp.presentation.media.mediaMenuRows
import org.tegro.yamp.presentation.queues.QueueRowState
import org.tegro.yamp.ui.theme.YandexMusicTheme

/**
 * Phase 4b folders (canvas `pf-*`, `cf-*`, `pf-land`), (e) Library per account and Home's Jellyfin group (`hj-lib*`,
 * `hj-home-c-scroll`) and (f) the car Library (`ca-albums-*`, `ca-menu-*`, `ca-add-*`): phone portrait, phone
 * landscape and the 1760×720 unit with both hands.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = RobolectricDeviceQualifiers.Pixel7, application = Application::class)
class FoldersLibraryCarScreenshotsTest {

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

    // ── Sample data (the canvas' folders.jsx / carbrowse.jsx) ────────────────────
    private val account = AccountId.jellyfin("srv1", "u1")
    private val yandexAccount = AccountId.yandex("100")
    private val server = "jellyfin@srv1"
    private val jfTag = AccountTag(account.value, "Home Media", "H", Color(0xFF8E6BD8))
    private val annaTag = AccountTag(yandexAccount.value, "Anna", "A", Color(0xFFE0A050))
    private fun folder(id: String, name: String, tracks: Int) = Folder(MediaRef(server, "folder", id), name, null, childCount = 3, trackCount = tracks)

    private val rootFolders = listOf(
        folder("1", "Fleur - Дискография (2000-2026)", 168), folder("2", "Lindemann - Discography - 2015 - 2026", 61), folder("3", "Louna", 104),
        folder("4", "Metallica (mp3)", 151), folder("5", "Pink Floyd", 182), folder("6", "Queen", 224), folder("7", "RC - Sorted", 912),
        folder("8", "The Beatles", 211), folder("9", "Аквариум и БГ (mp3)", 402), folder("10", "Детские", 88),
        folder("11", "Мельница & Хелависа - альбомы", 97), folder("12", "Мельница & Хелависа - концерты", 71), folder("13", "Сплин", 190),
        folder("14", "Чайф", 143)
    )
    private val pins = listOf(PinnedFolder("5", "Pink Floyd"), PinnedFolder("11", "Мельница & Хелависа - альбомы"), PinnedFolder("7", "RC - Sorted"))
    private val albumsLevel = listOf(
        folder("a1", "2003 — Перевал", 12), folder("a2", "2005 — Зов крови", 14), folder("a3", "2006 — Зов крови (live)", 9),
        folder("a4", "2009 — Дикие травы", 13), folder("a5", "2012 — Ангелофрения", 12), folder("a6", "2015 — Алхимия", 14)
    )
    private fun track(id: String, title: String, artist: String) =
        Track(TrackRef.of(server, id), title, listOf(ArtistBrief(null, artist)), null, 250_000L, null, explicit = false)

    private val folderActions = FolderActions(onPlay = { _, _, _ -> }, onOpen = {}, onJump = {}, onPlayTracks = { _, _, _ -> })
    private val actions = CatalogActions(onBack = {}, onRetry = {}, onOpenArtist = { _, _ -> }, onOpenAlbum = { _, _ -> }, onPlay = { _, _, _ -> }, onToggleLike = {})

    private fun rootState() = CatalogPageState(page = FolderPage(null, "Folders", account), folder = FolderLevel(rootFolders, emptyList()), isLoading = false)

    private val melnitsa = FolderPage(null, "Folders", account).child("11", "Мельница & Хелависа - альбомы")
    private val levelState = CatalogPageState(
        page = melnitsa,
        folder = FolderLevel(albumsLevel, listOf(track("t1", "Белая кобыла (демо)", "Мельница"), track("t2", "Ночная кобыла (акустика)", "Мельница"))),
        isLoading = false
    )

    // ── Phone: folders ───────────────────────────────────────────────────────────
    @Test
    fun foldersRoot() {
        show { FolderPageScreen(rootState(), pins, jfTag, 160.dp, actions, folderActions) }
        snap("folders_root")
    }

    @Test
    fun foldersLevel() {
        show { FolderPageScreen(levelState, listOf(PinnedFolder("a4", "2009 — Дикие травы")), jfTag, 160.dp, actions, folderActions) }
        snap("folders_level")
    }

    @Test
    fun folderMenu() {
        val target = MediaTarget(MediaRef(server, "folder", "12"), "Мельница & Хелависа - концерты", "Folder · 71 tracks", null, isArtist = false, account = account)
        show { Column(Modifier.statusBarsPadding().padding(top = 300.dp)) { MediaMenuContent(target, mediaMenuRows(target, fitsActiveQueue = true)) {} } }
        snap("folders_menu")
    }

    @Test
    @Config(qualifiers = "w915dp-h412dp-land-xhdpi")
    fun foldersRootLandscape() {
        show { FolderPageScreen(rootState(), pins, jfTag, 80.dp, actions, folderActions, landscape = true) }
        snap("folders_root_land")
    }

    // ── Phone: Library per account, Home's group ─────────────────────────────────
    private val jfSection = AccountLibrary(
        account, "Home Media", "Jellyfin",
        mapOf(LibraryRow.LIKED to 87, LibraryRow.PLAYLISTS to 9, LibraryRow.ALBUMS to 486, LibraryRow.ARTISTS to 212, LibraryRow.DOWNLOADED to 64, LibraryRow.FOLDERS to 34)
    )
    private val ySection = AccountLibrary(yandexAccount, "Anna", "Yandex", mapOf(LibraryRow.ALBUMS to 140, LibraryRow.ARTISTS to 58), inContext = true)
    /** A second Yandex account, out of context: its rows carry its own counts (likes, playlists, downloads too). */
    private val y2Tag = AccountTag(AccountId.yandex("200").value, "Boris", "B", Color(0xFF3FA88A))
    private val y2Section = AccountLibrary(
        AccountId.yandex("200"), "Boris", "Yandex",
        mapOf(LibraryRow.LIKED to 412, LibraryRow.PLAYLISTS to 7, LibraryRow.ALBUMS to 33, LibraryRow.ARTISTS to 21, LibraryRow.DOWNLOADED to 18)
    )

    @Composable
    private fun Library(sections: List<AccountLibrary>, yandex: Boolean) = LibraryScreen(
        tracks = emptyList(), isLoading = false, currentTrackId = null, isPlaying = false, likedTrackIds = emptySet(),
        downloadedTrackIds = emptySet(), downloadProgress = null, onTrackClick = {}, onPlayAllClick = {}, onShuffleClick = {},
        onDownloadAllClick = {}, onCancelDownload = {}, onToggleLike = {}, sections = sections, accountTags = listOf(annaTag, y2Tag, jfTag),
        yandex = yandex, historyCount = 12
    )

    @Test
    fun libraryBothAccounts() {
        show { Library(listOf(ySection, jfSection), yandex = true) }
        snap("library_accounts_both")
    }

    @Test
    fun libraryEveryAccount() {
        // Two Yandex accounts and a Jellyfin one: a section each, the second Yandex account's with its own counts
        show { Library(listOf(ySection, y2Section, jfSection), yandex = true) }
        snap("library_accounts_every")
    }

    @Test
    fun libraryYandexOnly() {
        show { Library(listOf(ySection), yandex = true) }
        snap("library_accounts_yandex")
    }

    @Test
    fun libraryJellyfinOnly() {
        show { Library(listOf(jfSection), yandex = false) }
        snap("library_accounts_jellyfin")
    }

    @Test
    @Config(qualifiers = "w915dp-h412dp-land-xhdpi")
    fun libraryLandscape() {
        show { Library(listOf(ySection, jfSection), yandex = true) }
        snap("library_accounts_land")
    }

    @Test
    fun homeJellyfinGroup() {
        val album = { id: String, t: String, a: String -> Album(MediaRef(server, "album", id), t, listOf(ArtistBrief(null, a)), null, 2021, 10) }
        val group = HomeJellyfinGroup(
            account, "Home Media", pins = pins + PinnedFolder("10", "Детские"),
            recent = listOf(album("r1", "Night Drive", "Lumen Lane"), album("r2", "Field Notes", "Field Notes"), album("r3", "Copper Sky", "Hollow Pines")),
            favourites = listOf(track("f1", "Paper Lanterns", "The Quiet Coast"), track("f2", "Night Drive", "Lumen Lane"), track("f3", "Copper Sky", "Hollow Pines")),
            favouriteCount = 87
        )
        show {
            Column(Modifier.statusBarsPadding().padding(top = 70.dp).verticalScroll(rememberScrollState())) {
                HomeJellyfinGroupView(group, jfTag, header = true, HomeJellyfinActions({}, { _, _, _ -> }, { _, _, _ -> }))
            }
        }
        snap("home_jellyfin_group")
    }

    // ── Car (1760 × 720): the Library, its menu and the picker, both hands ───────
    private val yAlbums = listOf(
        "A Night at the Opera" to "Queen", "Abbey Road" to "The Beatles", "Animals" to "Pink Floyd", "Innuendo" to "Queen",
        "Master of Puppets" to "Metallica", "The Wall" to "Pink Floyd", "Wish You Were Here" to "Pink Floyd", "Алхимия" to "Мельница",
        "Гранатовый альбом" to "Сплин", "Дикие травы" to "Мельница", "Зов крови" to "Мельница"
    ).mapIndexed { i, (t, a) -> CarTile.OfAlbum(Album(MediaRef("yandex", "album", "y$i"), t, listOf(ArtistBrief(null, a)), null, 2000, 12)) }
    private val jAlbums = listOf(
        "Afterglow Avenue" to "Static Bloom", "Copper Sky" to "Hollow Pines", "Field Notes" to "Field Notes", "Low Orbit" to "Static Bloom",
        "Morning Tide" to "Field Notes", "Neon Harbour" to "Lumen Lane", "Night Drive" to "Lumen Lane", "Paper Lanterns" to "The Quiet Coast"
    ).mapIndexed { i, (t, a) -> CarTile.OfAlbum(Album(MediaRef(server, "album", "j$i"), t, listOf(ArtistBrief(null, a)), null, 2020, 11)) }
    private val now = CarNowPlaying("Wish You Were Here", "Pink Floyd", null, isPlaying = true)
    private val callbacks = CarLibraryCallbacks(
        {}, {}, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}
    )
    private fun queue(id: Long, name: String, acc: AccountId, track: String, artist: String) =
        QueueRowState(id, name, QueueKind.STATIC, track, artist, null, 0.3f, 4, 31, isActive = false, account = acc.value)

    @Composable
    private fun Car(
        left: Boolean, jellyfin: Boolean, panel: CarPanel? = null, pressed: String? = null,
        tab: CarTab = CarTab.ALBUMS, tiles: List<CarTile>? = null, folder: FolderPage? = null, album: CarAlbumPage? = null,
        playing: TrackRef? = null
    ) {
        val acc = if (jellyfin) account else yandexAccount
        CarLibraryScreen(
            state = CarLibraryState(acc, tab, tiles ?: if (jellyfin) jAlbums else yAlbums, folder = folder, loading = false,
                level = folder?.let { FolderLevel(albumsLevel, emptyList()) }, album = album),
            controlsOnLeft = left,
            account = if (jellyfin) jfTag else annaTag,
            accounts = listOf(annaTag, jfTag),
            nowPlaying = now.copy(ref = playing),
            panel = panel,
            queues = if (jellyfin) listOf(queue(5, "Home Media", account, "Night Drive", "Lumen Lane"))
            else listOf(queue(1, "С семьёй", yandexAccount, "Дороги", "Мельница"), queue(2, "Для работы", yandexAccount, "Shine On", "Pink Floyd")),
            callbacks = callbacks,
            pressed = pressed
        )
    }

    private fun menu(tile: CarTile.OfAlbum) = MediaTarget.of(tile.album, "Album").let { CarPanel.Menu(it, carMediaMenuRows(it, fitsActiveQueue = true)) }

    @Test
    @Config(qualifiers = "w1760dp-h720dp-land-mdpi")
    fun carAlbumsLeft() {
        show { Car(left = true, jellyfin = false) }
        snap("car_albums_left")
    }

    @Test
    @Config(qualifiers = "w1760dp-h720dp-land-mdpi")
    fun carAlbumsJellyfinRight() {
        show { Car(left = false, jellyfin = true) }
        snap("car_albums_jf_right")
    }

    @Test
    @Config(qualifiers = "w1760dp-h720dp-land-mdpi")
    fun carMenuLeft() {
        show { Car(left = true, jellyfin = false, panel = menu(yAlbums[9]), pressed = yAlbums[9].key) }
        snap("car_menu_left")
    }

    @Test
    @Config(qualifiers = "w1760dp-h720dp-land-mdpi")
    fun carMenuJellyfinRight() {
        show { Car(left = false, jellyfin = true, panel = menu(jAlbums[6]), pressed = jAlbums[6].key) }
        snap("car_menu_jf_right")
    }

    @Test
    @Config(qualifiers = "w1760dp-h720dp-land-mdpi")
    fun carAddToQueueLeft() {
        show { Car(left = true, jellyfin = false, panel = CarPanel.AddTo(MediaTarget.of(yAlbums[9].album, "Album"), 13), pressed = yAlbums[9].key) }
        snap("car_add_left")
    }

    @Test
    @Config(qualifiers = "w1760dp-h720dp-land-mdpi")
    fun carAddToQueueJellyfinRight() {
        show { Car(left = false, jellyfin = true, panel = CarPanel.AddTo(MediaTarget.of(jAlbums[6].album, "Album"), 11), pressed = jAlbums[6].key) }
        snap("car_add_jf_right")
    }

    // The album page (canvas `ca-album-*`): Мельница's «Дикие травы» on Yandex, Lumen Lane's «Night Drive» on Jellyfin
    private fun albumPage(tile: CarTile.OfAlbum, year: Int, tracks: List<Pair<String, String>>, ref: (Int) -> TrackRef): CarAlbumPage {
        val list = tracks.mapIndexed { i, (title, time) ->
            val (m, sec) = time.split(":").map(String::toLong)
            Track(ref(i), title, tile.album.artists, null, (m * 60 + sec) * 1000, null, explicit = false)
        }
        return CarAlbumPage(tile.album.copy(year = year, trackCount = list.size, tracks = list), list, loading = false)
    }
    private val yAlbumPage = albumPage(
        yAlbums[9], 2009,
        listOf(
            "Дороги" to "4:05", "Ведьма" to "3:52", "Травушка" to "4:31", "Ночная кобыла" to "4:18", "Невеста Полоза" to "5:03", "Белая кошка" to "3:28",
            "Дорога сна" to "4:12", "Сонг" to "3:46", "Прощание" to "4:31", "Огонь" to "3:57", "Рапунцель" to "4:02", "Господин горных дорог" to "4:44"
        )
    ) { TrackRef.yandex("d$it") }
    private val jAlbumPage = albumPage(
        jAlbums[6], 2021,
        listOf(
            "Headlights" to "3:58", "Night Drive" to "4:21", "Tunnel Lights" to "3:40", "Exit 14" to "4:02", "Sodium Glow" to "5:11", "Overpass" to "3:33",
            "Rearview" to "4:09", "Empty Lanes" to "3:47", "Dashboard Blue" to "4:15", "Last Station" to "3:52", "Home by Dawn" to "5:06"
        )
    ) { TrackRef.of(server, "n$it") }

    @Test
    @Config(qualifiers = "w1760dp-h720dp-land-mdpi")
    fun carAlbumPageLeft() {
        show { Car(left = true, jellyfin = false, album = yAlbumPage, playing = yAlbumPage.tracks[0].ref) }
        snap("car_album_left")
    }

    @Test
    @Config(qualifiers = "w1760dp-h720dp-land-mdpi")
    fun carAlbumPageRight() {
        show { Car(left = false, jellyfin = false, album = yAlbumPage, playing = yAlbumPage.tracks[0].ref) }
        snap("car_album_right")
    }

    @Test
    @Config(qualifiers = "w1760dp-h720dp-land-mdpi")
    fun carAlbumPageJellyfinLeft() {
        show { Car(left = true, jellyfin = true, album = jAlbumPage, playing = jAlbumPage.tracks[0].ref) }
        snap("car_album_jf_left")
    }

    private val folderTiles: List<CarTile> = pins.map { pin -> CarTile.OfFolder(rootFolders.first { it.ref.id == pin.id }, true) } +
        rootFolders.filter { f -> pins.none { it.id == f.ref.id } }.map { CarTile.OfFolder(it, false) }

    @Test
    @Config(qualifiers = "w1760dp-h720dp-land-mdpi")
    fun carFoldersLeft() {
        show { Car(left = true, jellyfin = true, tab = CarTab.FOLDERS, tiles = folderTiles) }
        snap("car_folders_left")
    }

    @Test
    @Config(qualifiers = "w1760dp-h720dp-land-mdpi")
    fun carFoldersRight() {
        show { Car(left = false, jellyfin = true, tab = CarTab.FOLDERS, tiles = folderTiles) }
        snap("car_folders_right")
    }

    @Test
    @Config(qualifiers = "w1760dp-h720dp-land-mdpi")
    fun carFolderLevelLeft() {
        show {
            Car(
                left = true, jellyfin = true, tab = CarTab.FOLDERS, folder = melnitsa,
                tiles = listOf(CarTile.OfFolder(albumsLevel[3], true)) + albumsLevel.filter { it != albumsLevel[3] }.map { CarTile.OfFolder(it, false) }
            )
        }
        snap("car_folder_level_left")
    }

    @Test
    @Config(qualifiers = "w1760dp-h720dp-land-mdpi")
    fun carFolderMenuRight() {
        val f = rootFolders[10]
        val target = MediaTarget(f.ref, f.title, "Folder · 97 tracks", null, isArtist = false, account = account, pinned = true)
        show { Car(left = false, jellyfin = true, tab = CarTab.FOLDERS, tiles = folderTiles, panel = CarPanel.Menu(target, carMediaMenuRows(target, true)), pressed = f.ref.value) }
        snap("car_folder_menu_right")
    }

    private val playing = listOf(
        Track(TrackRef.yandex("1"), "Wish You Were Here", listOf(ArtistBrief(null, "Pink Floyd")), null, 312_000L, null, explicit = false),
        Track(TrackRef.yandex("2"), "Дороги", listOf(ArtistBrief(null, "Мельница")), null, 250_000L, null, explicit = false)
    )

    /** The one entry (canvas `ca-entry-l` / `-r`): the car player's ⋮ with «Музыка» last. */
    @Test
    @Config(qualifiers = "w1760dp-h720dp-land-mdpi")
    fun carEntryLeft() {
        show { CarPlayerForTest(track = playing[0], queue = playing, waveActive = false, initialMoreOpen = true) }
        snap("car_entry_left")
    }

    @Test
    @Config(qualifiers = "w1760dp-h720dp-land-mdpi")
    fun carEntryRight() {
        show { CarPlayerForTest(track = playing[0], queue = playing, waveActive = false, steeringWheelOnLeft = false, initialMoreOpen = true) }
        snap("car_entry_right")
    }

    /** The phone menu's rows keep their order with the new ones: nothing else moved. */
    @Test
    fun menuRowOrderIsStable() {
        val album = MediaTarget(MediaRef("yandex", "album", "1"), "A", "Album", null, isArtist = false)
        org.junit.Assert.assertEquals(MediaMenuRow.PLAY_NOW, mediaMenuRows(album, true).first())
    }
}
