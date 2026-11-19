package org.tegro.yamp.data.provider.yandex

import org.tegro.yamp.data.provider.yandex.repository.CatalogRepository
import org.tegro.yamp.data.provider.yandex.repository.LibraryRepository
import org.tegro.yamp.data.provider.yandex.repository.SearchRepository
import org.tegro.yamp.data.provider.yandex.repository.UserPlaylistsRepository
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
import org.tegro.yamp.domain.provider.Page
import org.tegro.yamp.domain.provider.Library
import org.tegro.yamp.domain.provider.MusicProvider
import org.tegro.yamp.domain.provider.Outcome
import org.tegro.yamp.domain.provider.ProviderError
import org.tegro.yamp.domain.provider.Search
import org.tegro.yamp.domain.provider.SearchResult
import org.tegro.yamp.domain.provider.StreamResolver
import org.tegro.yamp.domain.queue.MediaRef
import java.io.IOException
import java.io.InterruptedIOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * One Yandex account as a [MusicProvider] (docs/phase-3-providers.md §2, step 10). It wraps the repositories as
 * they are — they keep their caches and the upstream-hot code (A1, A3) — and asks them for its own [account];
 * the DTOs become the neutral models here, and exceptions become [ProviderError] once (D73).
 */
class YandexProvider(
    override val account: AccountId,
    override val streams: StreamResolver,
    private val libraryRepository: LibraryRepository,
    private val playlistsRepository: UserPlaylistsRepository,
    private val searchRepository: SearchRepository,
    private val catalogRepository: CatalogRepository
) : MusicProvider {

    override val library: Library = object : Library {
        override suspend fun likedTracks(): Outcome<List<Track>> =
            outcome { libraryRepository.likedTracksOf(account).map { it.toTrack() } }

        override suspend fun playlists(): Outcome<List<Playlist>> =
            outcome { playlistsRepository.playlistsFor(account).mapNotNull { it.toPlaylist() } }

        override suspend fun playlistTracks(playlist: MediaRef): Outcome<List<Track>> = outcome {
            val dto = playlistsRepository.playlistsFor(account).firstOrNull { it.toPlaylist()?.ref == playlist }
                ?: return Outcome.Err(ProviderError.NotFound)
            playlistsRepository.tracks(dto, account).map { it.toTrack() }
        }
    }

    override val search: Search = object : Search {
        override suspend fun search(text: String): Outcome<SearchResult> = outcome {
            val found = searchRepository.search(text, account)
            SearchResult(
                tracks = found.tracks.map { it.toTrack() },
                artists = found.artists.mapNotNull { it.toArtist() },
                albums = found.albums.mapNotNull { it.toAlbum() }
            )
        }
    }

    /**
     * The account's liked albums and artists as browse lists (gap (e): Library per account, the car's Albums tab) —
     * one page each, Yandex answers them whole. Genres: none (Yandex has no library genres).
     */
    override val browse: Browse = object : Browse {
        override suspend fun counts(): Outcome<BrowseCounts> = outcome {
            val albums = libraryRepository.likedAlbumsOf(account).size
            val artists = libraryRepository.likedArtistsOf(account).size
            val playlists = playlistsRepository.playlistsFor(account).size
            BrowseCounts(albums = albums, artists = artists, genres = 0, playlists = playlists)
        }

        override suspend fun albums(cursor: Page.Cursor?): Outcome<Page<Album>> =
            outcome { Page(libraryRepository.likedAlbumsOf(account).mapNotNull { it.toAlbum() }) }

        override suspend fun artists(cursor: Page.Cursor?): Outcome<Page<Artist>> =
            outcome { Page(libraryRepository.likedArtistsOf(account).mapNotNull { it.toArtist() }) }

        override suspend fun genres(cursor: Page.Cursor?): Outcome<Page<Genre>> = Outcome.Ok(Page(emptyList()))

        override suspend fun playlists(cursor: Page.Cursor?): Outcome<Page<Playlist>> =
            outcome { Page(playlistsRepository.playlistsFor(account).mapNotNull { it.toPlaylist() }) }

        override suspend fun genreAlbums(genre: MediaRef, cursor: Page.Cursor?): Outcome<Page<Album>> = Outcome.Ok(Page(emptyList()))
    }

    override val catalog: Catalog = object : Catalog {
        override suspend fun album(album: MediaRef): Outcome<Album> = outcome {
            catalogRepository.album(album.id, account).toAlbum() ?: return Outcome.Err(ProviderError.NotFound)
        }

        override suspend fun artist(artist: MediaRef): Outcome<ArtistPage> = outcome {
            val brief = catalogRepository.artist(artist.id, account)
            val who = brief.artist?.toArtist() ?: return Outcome.Err(ProviderError.NotFound)
            ArtistPage(
                who,
                brief.popularTracks.map { it.toTrack() },
                brief.albums.mapNotNull { it.toAlbum() },
                // Compilations the artist is on: "add the artist" with singles and compilations takes them too
                brief.alsoAlbums.mapNotNull { it.toAlbum()?.copy(kind = org.tegro.yamp.domain.media.AlbumKind.COMPILATION) }
            )
        }

        /** One of the account's own playlists with its tracks (the Library of an account out of context). */
        override suspend fun playlist(playlist: MediaRef): Outcome<Playlist> = outcome {
            val dto = playlistsRepository.playlistsFor(account).firstOrNull { it.toPlaylist()?.ref == playlist }
                ?: return Outcome.Err(ProviderError.NotFound)
            val shown = dto.toPlaylist() ?: return Outcome.Err(ProviderError.NotFound)
            shown.copy(tracks = playlistsRepository.tracks(dto, account).map { it.toTrack() })
        }
    }
}

/** Runs a repository call and turns what it throws into a [ProviderError]; cancellation passes through. */
// The provider boundary (D73): whatever a repository throws stops here and becomes an error value
@Suppress("TooGenericExceptionCaught")
internal inline fun <T> outcome(block: () -> T): Outcome<T> = try {
    Outcome.Ok(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Outcome.Err(providerErrorOf(e))
}

internal fun providerErrorOf(e: Throwable): ProviderError = when (e) {
    is InterruptedIOException -> ProviderError.Timeout
    is IOException -> ProviderError.Network
    is retrofit2.HttpException -> when (e.code()) {
        HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> ProviderError.Unauthorized
        HTTP_NOT_FOUND -> ProviderError.NotFound
        HTTP_TOO_MANY -> ProviderError.RateLimited
        else -> ProviderError.Server(e.code())
    }
    else -> ProviderError.Unknown(e.message)
}

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404
private const val HTTP_TOO_MANY = 429
