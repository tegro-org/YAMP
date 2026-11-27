package org.tegro.yamp.domain.provider

import org.tegro.yamp.domain.account.AccountId
import org.tegro.yamp.domain.media.Album
import org.tegro.yamp.domain.media.Artist
import org.tegro.yamp.domain.media.FolderLevel
import org.tegro.yamp.domain.media.Genre
import org.tegro.yamp.domain.media.Playlist
import org.tegro.yamp.domain.media.Track
import kotlinx.coroutines.flow.StateFlow
import org.tegro.yamp.domain.queue.MediaRef

/**
 * One signed-in account of a music service (C3, docs/phase-3-providers.md §2). What it can do is grouped into
 * small capabilities; a capability the service lacks is null and the UI hides what needs it. Every call names no
 * account: the provider is the account. Nothing here assumes a server or a network (local files, D25).
 *
 * Capabilities are added as screens move onto the models (steps 12–16), not speculatively: Favorites, playlist
 * editing, radio, home and folders come with the screens that use them.
 */
interface MusicProvider {
    val account: AccountId

    /** Always present: a provider that cannot play is not a provider. */
    val streams: StreamResolver

    val library: Library? get() = null
    val search: Search? get() = null
    val catalog: Catalog? get() = null

    /** Creating playlists and adding tracks to them; null where the service doesn't allow it. */
    val playlistEdit: PlaylistEdit? get() = null

    /** Paged lists of a library too big to load at once (Jellyfin, docs/phase-3-providers.md step 22). */
    val browse: Browse? get() = null

    /** The library's file tree (Jellyfin, Phase 4b); null where the service has no folders (Yandex). */
    val folders: Folders? get() = null

    /**
     * Whether the service answered the last call (null: always reachable, as far as the UI goes — Yandex). A
     * provider that serves the last visit from its cache while away (Jellyfin) says so here, and its pages show
     * the "not reachable" banner.
     */
    val reachable: StateFlow<Boolean>? get() = null
}

/** The account's own collection. */
interface Library {
    /** Liked tracks, newest first, as the service orders them. */
    suspend fun likedTracks(): Outcome<List<Track>>

    /** The account's playlists (without their tracks). */
    suspend fun playlists(): Outcome<List<Playlist>>

    /** [playlist]'s tracks in playlist order. */
    suspend fun playlistTracks(playlist: MediaRef): Outcome<List<Track>>
}

/** The account's playlists as something to add to (D84: only the tracks of this account). */
interface PlaylistEdit {
    /** Adds [tracks] to [playlist], skipping those already in it; how many were added. */
    suspend fun add(playlist: MediaRef, tracks: List<Track>): Outcome<Int>

    /** A new playlist holding [tracks]. */
    suspend fun create(title: String, tracks: List<Track>): Outcome<Playlist>
}

/** What a query finds, per type, in the service's order; rows are short (the service's top hits). */
data class SearchResult(
    val tracks: List<Track> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val albums: List<Album> = emptyList()
)

interface Search {
    suspend fun search(text: String): Outcome<SearchResult>
}

/**
 * An artist's page: who, their popular tracks and their albums. [alsoAlbums]: compilations they appear on, not
 * listed on the page (a provider without them leaves it empty).
 */
data class ArtistPage(
    val artist: Artist,
    val popularTracks: List<Track>,
    val albums: List<Album>,
    val alsoAlbums: List<Album> = emptyList()
)

/** Pages of the service's catalog, readable by any account. */
interface Catalog {
    /** The album with its tracks. */
    suspend fun album(album: MediaRef): Outcome<Album>

    suspend fun artist(artist: MediaRef): Outcome<ArtistPage>

    /** The playlist with its tracks; a provider without playlist pages says not found. */
    suspend fun playlist(playlist: MediaRef): Outcome<Playlist> = Outcome.Err(ProviderError.NotFound)
}

/** How many of each a [Browse] library holds. */
data class BrowseCounts(val albums: Int, val artists: Int, val genres: Int, val playlists: Int)

/**
 * A library browsed by kind (Jellyfin, step 22): every list is paged — pass the [Page.next] of one page to get
 * the next. Order is the service's sort by name.
 */
interface Browse {
    suspend fun counts(): Outcome<BrowseCounts>
    suspend fun albums(cursor: Page.Cursor? = null): Outcome<Page<Album>>
    suspend fun artists(cursor: Page.Cursor? = null): Outcome<Page<Artist>>
    suspend fun genres(cursor: Page.Cursor? = null): Outcome<Page<Genre>>
    suspend fun playlists(cursor: Page.Cursor? = null): Outcome<Page<Playlist>>
    suspend fun genreAlbums(genre: MediaRef, cursor: Page.Cursor? = null): Outcome<Page<Album>>

    /** The albums added to the library last, newest first (Home's "Recently added"). */
    suspend fun recentAlbums(limit: Int): Outcome<List<Album>> = Outcome.Ok(emptyList())
}

/**
 * A library browsed by its file tree (Phase 4b). Levels come whole — subfolders in [org.tegro.yamp.domain.media.NaturalOrder],
 * then the loose tracks — since they are filtered and pinned on the device.
 */
interface Folders {
    /** The top level: the music library's root (the library level itself is skipped when there is only one). */
    suspend fun root(): Outcome<FolderLevel>

    suspend fun level(folder: MediaRef): Outcome<FolderLevel>

    /** Every track under [folder], subfolders included, in folder order and each album's disc / track order. */
    suspend fun tracks(folder: MediaRef): Outcome<List<Track>>
}
