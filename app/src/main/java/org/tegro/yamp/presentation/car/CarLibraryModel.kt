package org.tegro.yamp.presentation.car

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.tegro.yamp.data.account.AccountContext
import org.tegro.yamp.data.folders.FolderPins
import org.tegro.yamp.data.folders.PinnedFolder
import org.tegro.yamp.data.player.PlayRequests
import org.tegro.yamp.data.player.PlaybackController
import org.tegro.yamp.data.player.PlaylistSource
import org.tegro.yamp.data.provider.ProviderRegistry
import org.tegro.yamp.domain.account.AccountId
import org.tegro.yamp.domain.account.Provider
import org.tegro.yamp.domain.media.Album
import org.tegro.yamp.domain.media.Folder
import org.tegro.yamp.domain.media.FolderLevel
import org.tegro.yamp.domain.media.NaturalOrder
import org.tegro.yamp.domain.media.Playlist
import org.tegro.yamp.domain.media.Track
import org.tegro.yamp.domain.provider.Outcome
import org.tegro.yamp.domain.provider.Page
import org.tegro.yamp.domain.provider.getOrNull
import org.tegro.yamp.presentation.catalog.FolderPage
import org.tegro.yamp.presentation.media.MediaMenuRow
import javax.inject.Inject

/** A tab of the car Library (canvas `ca-albums-*`). */
enum class CarTab { FOLDERS, ALBUMS, PLAYLISTS, LIKED }

/**
 * The tabs an account has: Jellyfin Folders · Albums · Playlists · Favourites; Yandex Albums · Playlists · Liked;
 * "This device" as Jellyfin with Playlists only once it has some (canvas `lo-cf-root-*`; no M3U in Stage 1). Pure.
 */
fun carTabs(provider: Provider, hasPlaylists: Boolean = false): List<CarTab> = when (provider) {
    Provider.JELLYFIN -> CarTab.entries
    Provider.LOCAL -> if (hasPlaylists) CarTab.entries else CarTab.entries - CarTab.PLAYLISTS
    Provider.YANDEX -> listOf(CarTab.ALBUMS, CarTab.PLAYLISTS, CarTab.LIKED)
}

/** One tile of the car grid. */
sealed interface CarTile {
    val key: String
    val title: String

    data class OfAlbum(val album: Album) : CarTile {
        override val key get() = album.ref.value
        override val title get() = album.title
    }

    data class OfPlaylist(val playlist: Playlist) : CarTile {
        override val key get() = playlist.ref.value
        override val title get() = playlist.title
    }

    data class OfTrack(val track: Track) : CarTile {
        override val key get() = track.ref.value
        override val title get() = track.title
    }

    data class OfFolder(val folder: Folder, val pinned: Boolean) : CarTile {
        override val key get() = folder.ref.value
        override val title get() = folder.title
    }
}

/**
 * The A–Z rail (canvas `CbRail`): the first letters present, in the grid's order, pinned folders left out (they sit
 * on top whatever their name). Pure; unit-tested.
 */
fun railLetters(tiles: List<CarTile>): List<Char> = tiles
    .filterNot { it is CarTile.OfFolder && it.pinned }
    .mapNotNull { tile -> tile.title.trim().firstOrNull()?.uppercaseChar()?.takeIf { it.isLetterOrDigit() } }
    .distinct()

/** The first tile under [letter] (the rail's jump). */
fun firstIndexOf(tiles: List<CarTile>, letter: Char): Int =
    tiles.indexOfFirst { !(it is CarTile.OfFolder && it.pinned) && it.title.trim().firstOrNull()?.uppercaseChar() == letter }

/** What a tap on a tile does: a folder or an album opens (the album page, canvas `ca-album-*`), the rest plays. Pure. */
enum class CarTap { OPEN_FOLDER, OPEN_ALBUM, PLAY }

fun carTap(tile: CarTile): CarTap = when (tile) {
    is CarTile.OfFolder -> CarTap.OPEN_FOLDER
    is CarTile.OfAlbum -> CarTap.OPEN_ALBUM
    is CarTile.OfPlaylist, is CarTile.OfTrack -> CarTap.PLAY
}

/**
 * The car album page's big buttons (canvas `CbAlbumPage`): Play · Shuffle · Play next · Add to end · Add to queue… ·
 * Start radio. "Play next" and "Add to end" only when the active queue can take the album (D84), as in the menu;
 * "Start radio" only for Yandex (Jellyfin's Instant Mix is Phase 6). Pure; unit-tested.
 */
fun carAlbumButtons(provider: Provider?, fitsActiveQueue: Boolean): List<MediaMenuRow> = buildList {
    add(MediaMenuRow.PLAY_NOW)
    add(MediaMenuRow.SHUFFLE)
    if (fitsActiveQueue) {
        add(MediaMenuRow.PLAY_NEXT)
        add(MediaMenuRow.ADD_TO_END)
    }
    add(MediaMenuRow.ADD_TO_QUEUE)
    if (provider == Provider.YANDEX) add(MediaMenuRow.START_RADIO)
}

/** The car album page: [album] as tapped, then as loaded (with its [tracks]). */
data class CarAlbumPage(val album: Album, val tracks: List<Track> = emptyList(), val loading: Boolean = true, val failed: Boolean = false)

/** What the car Library shows. [folder] is the open folder level (null on the Folders tab's root and other tabs). */
data class CarLibraryState(
    val account: AccountId? = null,
    val tab: CarTab = CarTab.ALBUMS,
    val tiles: List<CarTile> = emptyList(),
    val folder: FolderPage? = null,
    val level: FolderLevel? = null,
    val loading: Boolean = true,
    val failed: Boolean = false,
    val filter: String = "",
    /** The open album page over the Albums tab (null: the grid). */
    val album: CarAlbumPage? = null
) {
    /** The tiles the filter lets through (case-insensitive, the title). */
    val shown: List<CarTile> get() = if (filter.isBlank()) tiles else tiles.filter { it.title.contains(filter.trim(), ignoreCase = true) }
}

/**
 * The car Library (gap (f)): opened from the player's ⋮ → «Музыка» on the active queue's account, on the tab used
 * last. Lists come through the account's provider; folders as on the phone (Phase 4b).
 */
@HiltViewModel
class CarLibraryViewModel @Inject constructor(
    private val registry: ProviderRegistry,
    private val accountContext: AccountContext,
    private val pins: FolderPins,
    private val playbackController: PlaybackController,
    private val playRequests: PlayRequests
) : ViewModel() {

    private val _state = MutableStateFlow(CarLibraryState())
    val state: StateFlow<CarLibraryState> = _state.asStateFlow()
    private var job: Job? = null
    private var albumJob: Job? = null

    /** Opens on [accountKey] (the active queue's; null = the Yandex account in context). */
    fun open(accountKey: String?) {
        val account = accountKey?.let(::AccountId) ?: accountContext.current() ?: return
        val provider = account.provider ?: Provider.YANDEX
        val tabs = carTabs(provider)
        val tab = pins.lastTab(account)?.let { name -> tabs.firstOrNull { it.name == name } } ?: tabs.first()
        _state.value = CarLibraryState(account = account, tab = tab)
        load()
    }

    fun select(tab: CarTab) {
        val account = _state.value.account ?: return
        pins.rememberTab(account, tab.name)
        _state.value = CarLibraryState(account = account, tab = tab)
        load()
    }

    fun filter(text: String) = _state.update { it.copy(filter = text) }

    fun openFolder(page: FolderPage) {
        _state.update { it.copy(folder = page, level = null, loading = true, failed = false, filter = "") }
        load()
    }

    /** A breadcrumb: that level, or the Folders root ([page] with no folder). */
    fun jumpTo(page: FolderPage) = if (page.folderId == null) select(CarTab.FOLDERS) else openFolder(page)

    /** A tap on an album tile: its page, the tracks loading through the Library's account. */
    fun openAlbum(album: Album) {
        val account = _state.value.account ?: return
        _state.update { it.copy(album = CarAlbumPage(album)) }
        albumJob?.cancel()
        albumJob = viewModelScope.launch {
            val loaded = registry.provider(account)?.catalog?.album(album.ref)?.getOrNull()
            _state.update { s ->
                val page = s.album
                when {
                    page == null || page.album.ref != album.ref -> s
                    loaded == null -> s.copy(album = page.copy(loading = false, failed = true))
                    else -> s.copy(album = CarAlbumPage(loaded.copy(cover = loaded.cover ?: album.cover, year = loaded.year ?: album.year), loaded.tracks, loading = false))
                }
            }
        }
    }

    /** A track row of the album page: the album plays from it in the account's queue. */
    fun playAlbumFrom(index: Int) {
        val account = _state.value.account ?: return
        val tracks = _state.value.album?.tracks.orEmpty()
        val from = tracks.getOrNull(index) ?: return
        start(account, tracks, from.ref.id)
    }

    /** Back inside the Library: the album page or a folder level goes up; at the top, false (the screen closes). */
    fun back(): Boolean {
        if (_state.value.album != null) {
            albumJob?.cancel()
            _state.update { it.copy(album = null) }
            return true
        }
        val page = _state.value.folder ?: return false
        val up = page.path.lastOrNull()
        if (up == null || up.folderId == null) select(CarTab.FOLDERS) else openFolder(page.up(up))
        return true
    }

    fun togglePin(folder: Folder) {
        val account = _state.value.account ?: return
        pins.toggle(account, PinnedFolder(folder.ref.id, folder.title, folder.cover?.key))
        // The grid shows pins on top: the level again with the new order
        if (_state.value.tab == CarTab.FOLDERS) load()
    }

    fun isPinned(folderId: String): Boolean = _state.value.account?.let { pins.isPinned(it, folderId) } == true

    /** A playlist or a liked track tapped: it plays in the account's queue (the liked list from that track). */
    fun play(tile: CarTile) {
        val account = _state.value.account ?: return
        viewModelScope.launch {
            val (tracks, from) = when (tile) {
                is CarTile.OfTrack -> _state.value.tiles.filterIsInstance<CarTile.OfTrack>().map { it.track } to tile.track.ref.id
                is CarTile.OfPlaylist -> registry.provider(account)?.library?.playlistTracks(tile.playlist.ref)?.getOrNull().orEmpty() to null
                else -> return@launch
            }
            start(account, tracks, from)
        }
    }

    private fun start(account: AccountId, tracks: List<Track>, from: String?) {
        if (tracks.isEmpty()) return
        playRequests.userStarted()
        if (account.provider != Provider.YANDEX) playbackController.playInAccount(account, tracks, from)
        else playbackController.playFrom(tracks, PlaylistSource.LIBRARY, from)
    }

    /** The car picker's row: [tracks] to the end of [queueId]. */
    fun addToQueue(queueId: Long, tracks: List<Track>) = playbackController.addToQueue(queueId, tracks)

    /** The car picker's "New queue": named after what is added, on the Library's account. */
    fun newQueue(name: String, tracks: List<Track>) = playbackController.createQueueWith(name, tracks, _state.value.account?.value)

    private fun load() {
        job?.cancel()
        val now = _state.value
        val account = now.account ?: return
        val provider = registry.provider(account)
        job = viewModelScope.launch {
            if (provider == null) {
                _state.update { it.copy(loading = false, failed = true) }
                return@launch
            }
            val tiles: List<CarTile>? = when (now.tab) {
                CarTab.ALBUMS -> allPages { provider.browse?.albums(it) }?.sortedWith(compareBy(NaturalOrder) { it.title })?.map(CarTile::OfAlbum)
                CarTab.PLAYLISTS -> provider.library?.playlists()?.getOrNull()?.sortedWith(compareBy(NaturalOrder) { it.title })?.map(CarTile::OfPlaylist)
                CarTab.LIKED -> provider.library?.likedTracks()?.getOrNull()?.map(CarTile::OfTrack)
                CarTab.FOLDERS -> folderTiles(account, now.folder)
            }
            _state.update { s ->
                if (s.account != account || s.tab != now.tab || s.folder != now.folder) s
                else s.copy(tiles = tiles.orEmpty(), loading = false, failed = tiles == null)
            }
        }
    }

    private suspend fun folderTiles(account: AccountId, page: FolderPage?): List<CarTile>? {
        val folders = registry.provider(account)?.folders ?: return null
        val level = (if (page?.folderId == null) folders.root() else folders.level(page.refOf(page.folderId))).getOrNull() ?: return null
        _state.update { if (it.folder == page) it.copy(level = level) else it }
        val pinned = pins.of(account)
        val pinnedIds = pinned.map { it.id }.toSet()
        val top = if (page == null) {
            pinned.map { pin -> level.folders.firstOrNull { it.ref.id == pin.id } ?: Folder(rootRef(account, pin.id), pin.title, null) }
        } else {
            level.folders.filter { it.ref.id in pinnedIds }
        }
        return top.map { CarTile.OfFolder(it, pinned = true) } +
            level.folders.filter { it.ref.id !in pinnedIds }.map { CarTile.OfFolder(it, pinned = false) } +
            level.tracks.map(CarTile::OfTrack)
    }

    private fun rootRef(account: AccountId, id: String) = FolderPage(null, "", account).refOf(id)

    /** Every page of a paged list (up to [MAX_PAGES]); null when the first fails. */
    private suspend fun <T> allPages(call: suspend (Page.Cursor?) -> Outcome<Page<T>>?): List<T>? {
        val all = mutableListOf<T>()
        var cursor: Page.Cursor? = null
        repeat(MAX_PAGES) {
            when (val page = call(cursor)) {
                null -> return null
                is Outcome.Err -> return if (all.isEmpty()) null else all
                is Outcome.Ok -> {
                    all += page.value.items
                    cursor = page.value.next ?: return all
                }
            }
        }
        return all
    }

    private companion object {
        const val MAX_PAGES = 20
    }
}
