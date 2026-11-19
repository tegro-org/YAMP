package org.tegro.yamp.presentation.catalog

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.tegro.yamp.data.provider.yandex.ArtistParts
import org.tegro.yamp.data.provider.yandex.toArtistParts
import org.tegro.yamp.data.provider.yandex.toAlbum
import org.tegro.yamp.domain.media.Album
import org.tegro.yamp.domain.media.Track
import org.tegro.yamp.presentation.common.id
import org.tegro.yamp.data.player.PlaybackController
import org.tegro.yamp.data.player.PlaylistSource
import org.tegro.yamp.data.provider.ProviderRegistry
import org.tegro.yamp.data.provider.jellyfin.JellyfinAccountStore
import org.tegro.yamp.data.provider.yandex.repository.CatalogRepository
import org.tegro.yamp.domain.account.AccountId
import org.tegro.yamp.domain.account.Provider
import org.tegro.yamp.domain.media.Genre
import org.tegro.yamp.domain.media.Playlist
import org.tegro.yamp.domain.provider.Outcome
import org.tegro.yamp.domain.provider.Page
import org.tegro.yamp.domain.queue.MediaRef
import org.tegro.yamp.provider.jellyfin.JellyfinRefs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * A page of the catalog stack; [title] is shown while the page loads. [account] names the provider account of a
 * Jellyfin page (docs/phase-3-providers.md step 22); Yandex pages leave it null and load as before.
 */
sealed interface CatalogPage {
    val id: String
    val title: String
    val account: AccountId? get() = null

    data class Artist(override val id: String, override val title: String, override val account: AccountId? = null) : CatalogPage
    data class Album(override val id: String, override val title: String, override val account: AccountId? = null) : CatalogPage

    /** A Jellyfin playlist with its tracks (Yandex playlists keep their own screen in the Library). */
    data class Playlist(override val id: String, override val title: String, override val account: AccountId) : CatalogPage

    /** A Jellyfin library list: albums, artists, playlists, genres, or one genre's albums ([genreId]). */
    data class Browse(
        val kind: BrowseKind,
        override val title: String,
        override val account: AccountId,
        val genreId: String? = null
    ) : CatalogPage {
        override val id: String get() = kind.name + (genreId?.let { ":$it" } ?: "")
    }
}

/** A level of an account's folder tree (Phase 4b); [folderId] null = the root ("Folders"). */
data class FolderPage(
    val folderId: String?,
    override val title: String,
    override val account: AccountId,
    /** The levels above this one, root first: the breadcrumbs. */
    val path: List<Crumb> = emptyList(),
    /** The folder's image (its cache key) as the level above showed it: the head's art. */
    val cover: String? = null
) : CatalogPage {
    override val id: String get() = folderId ?: ROOT

    /** One breadcrumb: a level to jump back to. */
    data class Crumb(val folderId: String?, val title: String)

    /** The subfolder [folder] opened from this level. */
    fun child(folderId: String, title: String, cover: String? = null) =
        FolderPage(folderId, title, account, path + Crumb(this.folderId, this.title), cover)

    /** A folder of this page's server (`jellyfin@<server>:folder:<id>`; the account id starts with the server's). */
    fun refOf(folderId: String) = MediaRef(JellyfinRefs.PROVIDER + MediaRef.INSTANCE_SEPARATOR + account.id.substringBefore('/'), JellyfinRefs.KIND_FOLDER, folderId)

    /** The level of [crumb] (one of [path]). */
    fun up(crumb: Crumb): FolderPage {
        val at = path.indexOf(crumb)
        return FolderPage(crumb.folderId, crumb.title, account, if (at < 0) emptyList() else path.take(at))
    }

    companion object {
        const val ROOT = "folders:root"
    }
}

enum class BrowseKind { ALBUMS, ARTISTS, PLAYLISTS, GENRES, GENRE_ALBUMS, FAVOURITES, DOWNLOADED }

/** One row or tile of a [CatalogPage.Browse] list. */
sealed interface BrowseItem {
    val key: String

    data class OfAlbum(val album: Album) : BrowseItem { override val key get() = album.ref.value }
    data class OfArtist(val artist: org.tegro.yamp.domain.media.Artist) : BrowseItem { override val key get() = artist.ref.value }
    data class OfPlaylist(val playlist: Playlist) : BrowseItem { override val key get() = playlist.ref.value }
    data class OfGenre(val genre: Genre) : BrowseItem { override val key get() = genre.ref.value }
}

/** A browse list loaded so far; [next] asks for more. */
data class BrowseList(val items: List<BrowseItem>, val next: Page.Cursor?, val loadingMore: Boolean = false)

/** An artist's page: who, the popular tracks, the albums (only those that can be opened). */
data class ArtistPage(
    val artist: org.tegro.yamp.domain.media.Artist?,
    val popularTracks: List<Track>,
    val albums: List<Album>
)

/** The provider's artist parts as the screen shows them. */
fun ArtistParts.toArtistPage(): ArtistPage = ArtistPage(artist, popularTracks, albums)

data class CatalogPageState(
    val page: CatalogPage,
    val artist: ArtistPage? = null,
    val album: Album? = null,
    val playlist: Playlist? = null,
    val browse: BrowseList? = null,
    /** A folder level (Phase 4b). */
    val folder: org.tegro.yamp.domain.media.FolderLevel? = null,
    /** The tracks of a Favourites / Downloaded list (gap (e)). */
    val tracks: List<Track>? = null,
    val isLoading: Boolean = true,
    val failed: Boolean = false,
    /** A Jellyfin page shown from the last visit: the server did not answer (canvas `jf-unreachable`). */
    val offline: Boolean = false,
    /** The server's name for the banner and the header ("Home Media"). */
    val serverName: String? = null
)

/**
 * Artist and album pages, opened from search and the track menu, and the Jellyfin library's lists and pages
 * (step 22). They stack: artist → album → artist…, and Back returns through them.
 */
@HiltViewModel
class CatalogViewModel @Inject constructor(
    private val repository: CatalogRepository,
    private val playbackController: PlaybackController,
    private val playRequests: org.tegro.yamp.data.player.PlayRequests,
    private val registry: ProviderRegistry,
    private val jellyfinAccounts: JellyfinAccountStore,
    private val folderPins: org.tegro.yamp.data.folders.FolderPins,
    private val downloads: org.tegro.yamp.data.network.TrackDownloadManager,
    private val accountContext: org.tegro.yamp.data.account.AccountContext
) : ViewModel() {

    private val _stack = MutableStateFlow<List<CatalogPageState>>(emptyList())
    val stack: StateFlow<List<CatalogPageState>> = _stack.asStateFlow()

    /** Pinned folders per account key (Phase 4b): the folder pages put them on top and mark them. */
    val pins: StateFlow<Map<String, List<org.tegro.yamp.data.folders.PinnedFolder>>> = folderPins.pins

    /**
     * A breadcrumb (Phase 4b): back to that level — through the stack when it is there, else (a folder opened from
     * Home's pins) the folder levels on top give way to it.
     */
    fun jumpTo(page: FolderPage) {
        val stack = _stack.value
        val at = stack.indexOfLast { it.page == page }
        if (at >= 0) {
            _stack.value = stack.take(at + 1)
            return
        }
        val keep = stack.dropLastWhile { it.page is FolderPage }
        _stack.value = keep
        open(page)
    }

    /** Play / Shuffle on a folder: all its tracks (subfolders too) as one queue of its account (Phase 4b). */
    fun playFolder(account: AccountId, folderId: String, shuffle: Boolean) {
        val provider = registry.provider(account)?.folders ?: return
        val server = jellyfinAccounts.account(account)?.serverId ?: return
        viewModelScope.launch {
            val tracks = (provider.tracks(MediaRef(JellyfinRefs(server).provider, JellyfinRefs.KIND_FOLDER, folderId)) as? Outcome.Ok)?.value
            if (tracks.isNullOrEmpty()) return@launch
            val queue = if (shuffle) tracks.shuffled() else tracks
            playRequests.userStarted()
            playbackController.playInAccount(account, queue, null)
        }
    }

    private var retryJob: Job? = null

    fun openArtist(id: String, name: String) = open(CatalogPage.Artist(id, name))

    fun openAlbum(id: String, title: String) = open(CatalogPage.Album(id, title))

    fun open(page: CatalogPage) {
        if (_stack.value.lastOrNull()?.page == page) return
        _stack.update { it + CatalogPageState(page, serverName = page.account?.let(::serverName)) }
        load(page)
    }

    fun retry() {
        val top = _stack.value.lastOrNull() ?: return
        replace(top.page) { it.copy(isLoading = it.browse == null && it.album == null && it.artist == null && it.playlist == null && it.folder == null && it.tracks == null, failed = false) }
        load(top.page)
    }

    fun back() = _stack.update { it.dropLast(1) }

    fun closeAll() {
        _stack.value = emptyList()
    }

    /** Plays [tracks] from [trackId] (or the first one, shuffled if asked). */
    fun play(tracks: List<Track>, trackId: String? = null, shuffle: Boolean = false) {
        val queue = if (shuffle) tracks.shuffled() else tracks
        val start = trackId ?: queue.firstOrNull()?.id ?: return
        // A Jellyfin page plays in its account's own queue (step 23, D84), never in another account's; so does a page
        // of a Yandex account out of context (its Library section)
        val account = _stack.value.lastOrNull()?.page?.account
        val otherYandex = account != null && account.provider != Provider.JELLYFIN && account != accountContext.current()
        if (account != null && (otherYandex || tracks.any { it.ref.ref.provider.startsWith(JellyfinRefs.PROVIDER) })) {
            playRequests.userStarted()
            playbackController.playInAccount(account, queue, start)
            return
        }
        playRequests.userStarted()
        playbackController.playTrack(start, queue, PlaylistSource.SEARCH)
    }

    /** The next page of the browse list on top, once its end comes into view. */
    fun loadMore() {
        val top = _stack.value.lastOrNull() ?: return
        val page = top.page as? CatalogPage.Browse ?: return
        val list = top.browse ?: return
        val next = list.next ?: return
        if (list.loadingMore) return
        replace(page) { it.copy(browse = list.copy(loadingMore = true)) }
        viewModelScope.launch {
            val more = browse(page, next)
            replace(page) { state ->
                val shown = state.browse ?: return@replace state
                when (more) {
                    is Outcome.Ok -> state.copy(browse = BrowseList(shown.items + more.value.items, more.value.next), offline = isOffline(page))
                    is Outcome.Err -> state.copy(browse = shown.copy(loadingMore = false), offline = isOffline(page))
                }
            }
        }
    }

    private fun load(page: CatalogPage) {
        retryJob?.cancel()
        viewModelScope.launch {
            // Browse lists, folders and an account's playlists come through the account's provider, Yandex too (its liked
            // albums / artists, its playlists)
            val throughProvider = page is CatalogPage.Browse || page is FolderPage || (page is CatalogPage.Playlist && page.account != null)
            if (page.account?.provider == Provider.JELLYFIN || throughProvider) loadFromProvider(page)
            else loadYandex(page)
        }
    }

    private suspend fun loadYandex(page: CatalogPage) {
        try {
            when (page) {
                is CatalogPage.Artist -> {
                    val artist = repository.artist(page.id).toArtistParts().toArtistPage()
                    replace(page) { it.copy(artist = artist, isLoading = false) }
                }
                is CatalogPage.Album -> {
                    val album = repository.album(page.id).toAlbum()
                    replace(page) { it.copy(album = album, isLoading = false) }
                }
                is CatalogPage.Playlist, is CatalogPage.Browse, is FolderPage -> replace(page) { it.copy(isLoading = false, failed = true) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("CatalogViewModel", "Loading $page failed", e)
            replace(page) { it.copy(isLoading = false, failed = true) }
        }
    }

    /** A Jellyfin page through its account's provider; a page shown from the cache retries by itself. */
    private suspend fun loadFromProvider(page: CatalogPage) {
        val provider = page.account?.let(registry::provider)
        if (provider == null) {
            replace(page) { it.copy(isLoading = false, failed = true) }
            return
        }
        val ref = { kind: String -> MediaRef(refProvider(page), kind, page.id) }
        val loaded: Outcome<(CatalogPageState) -> CatalogPageState> = when (page) {
            is CatalogPage.Album -> provider.catalog?.album(ref(JellyfinRefs.KIND_ALBUM)).orMissing().let { o -> o.mapState { a -> { s -> s.copy(album = a) } } }
            is CatalogPage.Artist -> provider.catalog?.artist(ref(JellyfinRefs.KIND_ARTIST)).orMissing().let { o ->
                o.mapState { a -> { s -> s.copy(artist = ArtistPage(a.artist, a.popularTracks, a.albums)) } }
            }
            is CatalogPage.Playlist -> provider.catalog?.playlist(ref(JellyfinRefs.KIND_PLAYLIST)).orMissing().let { o ->
                o.mapState { p -> { s -> s.copy(playlist = p) } }
            }
            is CatalogPage.Browse -> when (page.kind) {
                BrowseKind.FAVOURITES, BrowseKind.DOWNLOADED -> provider.library?.likedTracks().orMissing().mapState { liked ->
                    val shown = if (page.kind == BrowseKind.DOWNLOADED) liked.filter { downloads.isKnownDownloaded(it.ref.id) } else liked
                    val change: (CatalogPageState) -> CatalogPageState = { s -> s.copy(tracks = shown) }
                    change
                }
                else -> browse(page, null).mapState { p -> { s -> s.copy(browse = BrowseList(p.items, p.next)) } }
            }
            is FolderPage -> {
                val folders = provider.folders
                val level = when {
                    folders == null -> null
                    page.folderId == null -> folders.root()
                    else -> folders.level(ref(JellyfinRefs.KIND_FOLDER))
                }
                level.orMissing().mapState { l -> { s -> s.copy(folder = l) } }
            }
        }
        val offline = isOffline(page)
        replace(page) { state ->
            when (loaded) {
                is Outcome.Ok -> loaded.value(state).copy(isLoading = false, failed = false, offline = offline)
                is Outcome.Err -> state.copy(isLoading = false, failed = true, offline = offline)
            }
        }
        if (offline) retryLater(page)
    }

    /** While the server is away the page asks again by itself, as long as it is on top. */
    private fun retryLater(page: CatalogPage) {
        retryJob?.cancel()
        retryJob = viewModelScope.launch {
            delay(RETRY_MS)
            if (_stack.value.lastOrNull()?.page == page) loadFromProvider(page)
        }
    }

    private suspend fun browse(page: CatalogPage.Browse, cursor: Page.Cursor?): Outcome<Page<BrowseItem>> {
        val browse = registry.provider(page.account)?.browse ?: return Outcome.Err(org.tegro.yamp.domain.provider.ProviderError.NotFound)
        return when (page.kind) {
            BrowseKind.ALBUMS -> browse.albums(cursor).pageOf(BrowseItem::OfAlbum)
            BrowseKind.ARTISTS -> browse.artists(cursor).pageOf(BrowseItem::OfArtist)
            BrowseKind.PLAYLISTS -> browse.playlists(cursor).pageOf(BrowseItem::OfPlaylist)
            BrowseKind.GENRES -> browse.genres(cursor).pageOf(BrowseItem::OfGenre)
            BrowseKind.GENRE_ALBUMS -> browse.genreAlbums(MediaRef(refProvider(page), JellyfinRefs.KIND_GENRE, page.genreId.orEmpty()), cursor)
                .pageOf(BrowseItem::OfAlbum)
            BrowseKind.FAVOURITES, BrowseKind.DOWNLOADED -> Outcome.Ok(Page(emptyList()))
        }
    }

    private fun isOffline(page: CatalogPage): Boolean =
        page.account?.let(registry::provider)?.reachable?.value == false

    /** `jellyfin@<server>` of the page's account; `yandex` for a Yandex account's. */
    private fun refProvider(page: CatalogPage): String = when (page.account?.provider) {
        Provider.JELLYFIN -> page.account?.let(jellyfinAccounts::account)?.let { JellyfinRefs(it.serverId).provider }.orEmpty()
        null -> ""
        else -> MediaRef.PROVIDER_YANDEX
    }

    private fun serverName(account: AccountId): String? = jellyfinAccounts.account(account)?.serverName?.ifBlank { null }

    private fun replace(page: CatalogPage, change: (CatalogPageState) -> CatalogPageState) =
        _stack.update { list -> list.map { if (it.page == page) change(it) else it } }

    private companion object {
        const val RETRY_MS = 15_000L

        fun <T> Outcome<T>?.orMissing(): Outcome<T> = this ?: Outcome.Err(org.tegro.yamp.domain.provider.ProviderError.NotFound)

        inline fun <T, R> Outcome<T>.mapState(map: (T) -> R): Outcome<R> = when (this) {
            is Outcome.Ok -> Outcome.Ok(map(value))
            is Outcome.Err -> this
        }

        inline fun <T> Outcome<Page<T>>.pageOf(item: (T) -> BrowseItem): Outcome<Page<BrowseItem>> = when (this) {
            is Outcome.Ok -> Outcome.Ok(Page(value.items.map(item), value.next))
            is Outcome.Err -> this
        }
    }
}

/** The provider of [page] is a Jellyfin account (its tracks have no likes or queue actions yet). */
val CatalogPage.isJellyfin: Boolean get() = account?.provider == Provider.JELLYFIN
