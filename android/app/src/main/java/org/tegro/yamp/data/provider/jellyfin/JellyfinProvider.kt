package org.tegro.yamp.data.provider.jellyfin

import kotlinx.coroutines.flow.StateFlow
import org.tegro.yamp.domain.account.AccountId
import org.tegro.yamp.domain.media.Album
import org.tegro.yamp.domain.media.Artist
import org.tegro.yamp.domain.media.Genre
import org.tegro.yamp.domain.media.Playlist
import org.tegro.yamp.domain.media.Track
import org.tegro.yamp.domain.provider.ArtistPage
import org.tegro.yamp.domain.provider.Browse
import org.tegro.yamp.domain.provider.BrowseCounts
import org.tegro.yamp.domain.provider.Catalog
import org.tegro.yamp.domain.provider.Library
import org.tegro.yamp.domain.provider.MusicProvider
import org.tegro.yamp.domain.provider.Outcome
import org.tegro.yamp.domain.provider.Page
import org.tegro.yamp.domain.provider.ProviderError
import org.tegro.yamp.domain.provider.ResolvedStream
import org.tegro.yamp.domain.provider.Search
import org.tegro.yamp.domain.provider.SearchResult
import org.tegro.yamp.domain.provider.StreamPrefs
import org.tegro.yamp.domain.provider.StreamResolver
import org.tegro.yamp.domain.queue.MediaRef
import org.tegro.yamp.domain.queue.TrackRef
import org.tegro.yamp.provider.jellyfin.JellyfinLibrary

/**
 * A signed-in Jellyfin account as a [MusicProvider] (docs/phase-3-providers.md §2, step 22): its library — browsed
 * by kind and paged — the album, artist and playlist pages, served from the account's own database while the
 * server is away ([reachable]) — and its streams ([streams], step 23).
 */
class JellyfinProvider(
    override val account: AccountId,
    private val lib: JellyfinLibrary,
    /** The account's streams (step 23); a provider built without one plays nothing ("unavailable"). */
    override val streams: StreamResolver = NoStreams
) : MusicProvider {

    override val reachable: StateFlow<Boolean> get() = lib.reachable

    override val library: Library = object : Library {
        override suspend fun likedTracks(): Outcome<List<Track>> = lib.likedTracks()

        override suspend fun playlists(): Outcome<List<Playlist>> {
            val all = mutableListOf<Playlist>()
            var cursor: Page.Cursor? = null
            repeat(MAX_PAGES) {
                when (val page = lib.playlists(cursor)) {
                    is Outcome.Err -> return page
                    is Outcome.Ok -> {
                        all += page.value.items
                        cursor = page.value.next ?: return Outcome.Ok(all)
                    }
                }
            }
            return Outcome.Ok(all)
        }

        override suspend fun playlistTracks(playlist: MediaRef): Outcome<List<Track>> =
            when (val page = lib.playlist(playlist.id)) {
                is Outcome.Ok -> Outcome.Ok(page.value.tracks)
                is Outcome.Err -> page
            }
    }

    /** Merged Search (step 24): the account's tracks, albums and album artists matching the text. */
    override val search: Search = object : Search {
        override suspend fun search(text: String): Outcome<SearchResult> = when (val found = lib.search(text)) {
            is Outcome.Ok -> Outcome.Ok(SearchResult(found.value.tracks, found.value.artists, found.value.albums))
            is Outcome.Err -> found
        }
    }

    override val catalog: Catalog = object : Catalog {
        override suspend fun album(album: MediaRef): Outcome<Album> = lib.album(album.id)
        override suspend fun artist(artist: MediaRef): Outcome<ArtistPage> = lib.artist(artist.id)
        override suspend fun playlist(playlist: MediaRef): Outcome<Playlist> = lib.playlist(playlist.id)
    }

    override val browse: Browse = object : Browse {
        override suspend fun counts(): Outcome<BrowseCounts> = lib.counts()
        override suspend fun albums(cursor: Page.Cursor?): Outcome<Page<Album>> = lib.albums(cursor)
        override suspend fun artists(cursor: Page.Cursor?): Outcome<Page<Artist>> = lib.artists(cursor)
        override suspend fun genres(cursor: Page.Cursor?): Outcome<Page<Genre>> = lib.genres(cursor)
        override suspend fun playlists(cursor: Page.Cursor?): Outcome<Page<Playlist>> = lib.playlists(cursor)
        override suspend fun genreAlbums(genre: MediaRef, cursor: Page.Cursor?): Outcome<Page<Album>> = lib.genreAlbums(genre.id, cursor)
    }

    /** No resolver given (tests of the library alone): the controller gets "unavailable", never a wrong link. */
    private object NoStreams : StreamResolver {
        override fun cached(track: TrackRef): ResolvedStream? = null
        override suspend fun resolve(track: TrackRef, prefs: StreamPrefs): Outcome<ResolvedStream> =
            Outcome.Err(ProviderError.Unavailable("no stream resolver"))
        override fun invalidate(track: TrackRef) = Unit
    }

    private companion object {
        /** A user's playlists in one list: up to this many pages of a hundred. */
        const val MAX_PAGES = 20
    }
}
