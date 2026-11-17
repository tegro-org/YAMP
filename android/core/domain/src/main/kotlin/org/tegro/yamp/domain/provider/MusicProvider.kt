package org.tegro.yamp.domain.provider

import org.tegro.yamp.domain.account.AccountId
import org.tegro.yamp.domain.media.Album
import org.tegro.yamp.domain.media.Artist
import org.tegro.yamp.domain.media.Playlist
import org.tegro.yamp.domain.media.Track
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

/** What a query finds, per type, in the service's order; rows are short (the service's top hits). */
data class SearchResult(
    val tracks: List<Track> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val albums: List<Album> = emptyList()
)

interface Search {
    suspend fun search(text: String): Outcome<SearchResult>
}

/** An artist's page: who, their popular tracks and their albums. */
data class ArtistPage(val artist: Artist, val popularTracks: List<Track>, val albums: List<Album>)

/** Pages of the service's catalog, readable by any account. */
interface Catalog {
    /** The album with its tracks. */
    suspend fun album(album: MediaRef): Outcome<Album>

    suspend fun artist(artist: MediaRef): Outcome<ArtistPage>
}
