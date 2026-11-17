package org.tegro.yamp.provider.jellyfin

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import org.tegro.yamp.domain.media.Album
import org.tegro.yamp.domain.media.Artist
import org.tegro.yamp.domain.media.Genre
import org.tegro.yamp.domain.media.Playlist
import org.tegro.yamp.domain.media.Track
import org.tegro.yamp.domain.provider.ArtistPage
import org.tegro.yamp.domain.provider.BrowseCounts
import org.tegro.yamp.domain.provider.Outcome
import org.tegro.yamp.domain.provider.Page
import org.tegro.yamp.domain.provider.ProviderError
import org.tegro.yamp.domain.provider.map
import retrofit2.HttpException
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Where a library keeps the last answer of each list and page (docs/phase-3-providers.md D72): the account's own
 * database in the app. Values are this module's JSON; the app never reads them.
 */
interface JellyfinListCache {
    suspend fun get(key: String): String?
    suspend fun put(key: String, json: String)
}

/**
 * One user's music library on one server (step 22): the paged lists, the album / artist / playlist pages and the
 * liked tracks, mapped to the neutral models. Every answer is kept in [cache]; while the server cannot be reached
 * the last visit is served from it and [reachable] says so (the pages show the "not reachable" banner, canvas
 * `jf-unreachable`). Errors leave as [ProviderError] — nothing is thrown across the provider boundary (D73).
 */
class JellyfinLibrary(
    private val api: JellyfinApi,
    private val userId: String,
    private val mappers: JellyfinMappers,
    private val cache: JellyfinListCache,
    private val pageSize: Int = PAGE_SIZE
) {
    private val reachableState = MutableStateFlow(true)
    val reachable: StateFlow<Boolean> = reachableState.asStateFlow()

    suspend fun counts(): Outcome<BrowseCounts> = fetch("counts", CountsDto.serializer()) {
        CountsDto(
            albums = api.items(userId, includeItemTypes = ALBUM, recursive = true, limit = 0).totalRecordCount,
            artists = api.albumArtists(userId, limit = 0).totalRecordCount,
            genres = api.musicGenres(userId, limit = 0).totalRecordCount,
            playlists = api.items(userId, includeItemTypes = PLAYLIST, recursive = true, limit = 0).totalRecordCount
        )
    }.map { BrowseCounts(it.albums, it.artists, it.genres, it.playlists) }

    suspend fun albums(cursor: Page.Cursor?): Outcome<Page<Album>> = page("albums", cursor) { start ->
        api.items(userId, includeItemTypes = ALBUM, recursive = true, sortBy = BY_NAME, startIndex = start, limit = pageSize, fields = FIELDS)
    }.map { it.mapItems { item -> mappers.album(item) } }

    suspend fun artists(cursor: Page.Cursor?): Outcome<Page<Artist>> = page("artists", cursor) { start ->
        api.albumArtists(userId, sortBy = BY_NAME, startIndex = start, limit = pageSize, fields = FIELDS)
    }.map { it.mapItems(mappers::artist) }

    suspend fun genres(cursor: Page.Cursor?): Outcome<Page<Genre>> = page("genres", cursor) { start ->
        api.musicGenres(userId, sortBy = BY_NAME, startIndex = start, limit = pageSize)
    }.map { it.mapItems(mappers::genre) }

    suspend fun playlists(cursor: Page.Cursor?): Outcome<Page<Playlist>> = page("playlists", cursor) { start ->
        api.items(userId, includeItemTypes = PLAYLIST, recursive = true, sortBy = BY_NAME, startIndex = start, limit = pageSize, fields = FIELDS)
    }.map { it.mapItems { item -> mappers.playlist(item) } }

    suspend fun genreAlbums(genreId: String, cursor: Page.Cursor?): Outcome<Page<Album>> = page("genre:$genreId", cursor) { start ->
        api.items(
            userId, includeItemTypes = ALBUM, recursive = true, sortBy = BY_NAME, startIndex = start, limit = pageSize,
            fields = FIELDS, genreIds = genreId
        )
    }.map { it.mapItems { item -> mappers.album(item) } }

    /** The album with its tracks in disc and track order. */
    suspend fun album(itemId: String): Outcome<Album> = fetch("album:$itemId", ItemWithItemsDto.serializer()) {
        ItemWithItemsDto(
            head = api.item(userId, itemId),
            items = api.items(userId, includeItemTypes = AUDIO, parentId = itemId, recursive = true, sortBy = BY_DISC_AND_TRACK).items
        )
    }.map { mappers.album(it.head, it.items) }

    /** The artist, their most played tracks and their albums, newest first. */
    suspend fun artist(itemId: String): Outcome<ArtistPage> = fetch("artist:$itemId", ArtistDto.serializer()) {
        ArtistDto(
            head = api.item(userId, itemId),
            popular = api.items(
                userId, includeItemTypes = AUDIO, recursive = true, artistIds = itemId, sortBy = BY_PLAYS,
                sortOrder = DESCENDING, limit = POPULAR_TRACKS
            ).items,
            albums = api.items(
                userId, includeItemTypes = ALBUM, recursive = true, albumArtistIds = itemId, sortBy = BY_YEAR,
                sortOrder = DESCENDING, fields = FIELDS
            ).items
        )
    }.map { dto -> ArtistPage(mappers.artist(dto.head), dto.popular.map(mappers::track), dto.albums.map { mappers.album(it) }) }

    /** The playlist with its tracks in playlist order. */
    suspend fun playlist(itemId: String): Outcome<Playlist> = fetch("playlist:$itemId", ItemWithItemsDto.serializer()) {
        ItemWithItemsDto(head = api.item(userId, itemId), items = api.playlistItems(itemId, userId).items)
    }.map { mappers.playlist(it.head, it.items) }

    /** The user's favourite tracks, newest first (Jellyfin's "Liked" — the page comes in P5). */
    suspend fun likedTracks(): Outcome<List<Track>> = fetch("liked", ListSerializer(BaseItemDto.serializer())) {
        api.items(userId, includeItemTypes = AUDIO, recursive = true, filters = FAVORITES, sortBy = BY_ADDED, sortOrder = DESCENDING).items
    }.map { items -> items.map(mappers::track) }

    /**
     * Tracks, albums and album artists whose names match [text] (step 24, merged Search). Not cached: a search is
     * typed, not revisited, so a server that is away answers with its error and the other providers' results stay.
     */
    suspend fun search(text: String): Outcome<SearchFound> = try {
        val tracks = api.items(userId, includeItemTypes = AUDIO, recursive = true, searchTerm = text, limit = SEARCH_TRACKS).items
        val albums = api.items(userId, includeItemTypes = ALBUM, recursive = true, searchTerm = text, limit = SEARCH_OTHERS, fields = FIELDS).items
        val artists = api.albumArtists(userId, searchTerm = text, limit = SEARCH_OTHERS, fields = FIELDS).items
        reachableState.value = true
        Outcome.Ok(SearchFound(tracks.map(mappers::track), artists.map(mappers::artist), albums.map { mappers.album(it) }))
    } catch (e: IOException) {
        Outcome.Err(errorOf(e))
    } catch (e: HttpException) {
        Outcome.Err(errorOf(e))
    } catch (e: SerializationException) {
        Outcome.Err(errorOf(e))
    }

    /** What a search found, in the neutral models. */
    data class SearchFound(val tracks: List<Track>, val artists: List<Artist>, val albums: List<Album>)

    private suspend fun page(list: String, cursor: Page.Cursor?, call: suspend (start: Int) -> ItemsResultDto): Outcome<ItemsPage> {
        val start = cursor?.value?.toIntOrNull() ?: 0
        return fetch("$list@$start", ItemsResultDto.serializer()) { call(start) }.map { result ->
            val end = start + result.items.size
            ItemsPage(result.items, next = if (result.items.isNotEmpty() && end < result.totalRecordCount) Page.Cursor(end.toString()) else null)
        }
    }

    /** One call: kept in the cache when it answers, served from it while the server is away. */
    private suspend fun <T> fetch(key: String, serializer: KSerializer<T>, call: suspend () -> T): Outcome<T> {
        val error = try {
            val value = call()
            reachableState.value = true
            cache.put(key, JellyfinApiFactory.json.encodeToString(serializer, value))
            return Outcome.Ok(value)
        } catch (e: IOException) {
            errorOf(e)
        } catch (e: HttpException) {
            errorOf(e)
        } catch (e: SerializationException) {
            errorOf(e)
        }
        if (error == ProviderError.Network || error == ProviderError.Timeout) {
            reachableState.value = false
            val kept = cache.get(key)?.let { json ->
                try {
                    JellyfinApiFactory.json.decodeFromString(serializer, json)
                } catch (_: SerializationException) {
                    null
                }
            }
            if (kept != null) return Outcome.Ok(kept)
        } else {
            // A refusal is still an answer: the server is there
            reachableState.value = true
        }
        return Outcome.Err(error)
    }

    private class ItemsPage(val items: List<BaseItemDto>, val next: Page.Cursor?) {
        fun <T> mapItems(map: (BaseItemDto) -> T) = Page(items.map(map), next)
    }

    companion object {
        const val PAGE_SIZE = 100
        private const val POPULAR_TRACKS = 10
        private const val SEARCH_TRACKS = 30
        private const val SEARCH_OTHERS = 10
        private const val ALBUM = "MusicAlbum"
        private const val AUDIO = "Audio"
        private const val PLAYLIST = "Playlist"
        private const val FIELDS = "ChildCount"
        private const val BY_NAME = "SortName"
        private const val BY_DISC_AND_TRACK = "ParentIndexNumber,IndexNumber,SortName"
        private const val BY_PLAYS = "PlayCount,SortName"
        private const val BY_YEAR = "ProductionYear,SortName"
        private const val BY_ADDED = "DateCreated"
        private const val DESCENDING = "Descending"
        private const val FAVORITES = "IsFavorite"
        private const val UNAUTHORIZED = 401
        private const val FORBIDDEN = 403
        private const val NOT_FOUND = 404
        private const val TOO_MANY = 429

        /** A failed call in the terms the UI acts on (D73). */
        fun errorOf(e: Throwable): ProviderError = when (e) {
            is HttpException -> when (e.code()) {
                UNAUTHORIZED, FORBIDDEN -> ProviderError.Unauthorized
                NOT_FOUND -> ProviderError.NotFound
                TOO_MANY -> ProviderError.RateLimited
                else -> ProviderError.Server(e.code())
            }
            is SocketTimeoutException -> ProviderError.Timeout
            is IOException -> ProviderError.Network
            else -> ProviderError.Unknown(e.message)
        }
    }
}

/** The cached form of the library's counts. */
@Serializable
internal data class CountsDto(
    @SerialName("albums") val albums: Int,
    @SerialName("artists") val artists: Int,
    @SerialName("genres") val genres: Int,
    @SerialName("playlists") val playlists: Int
)

/** A page's head item and its tracks, as cached. */
@Serializable
internal data class ItemWithItemsDto(
    @SerialName("head") val head: BaseItemDto,
    @SerialName("items") val items: List<BaseItemDto>
)

@Serializable
internal data class ArtistDto(
    @SerialName("head") val head: BaseItemDto,
    @SerialName("popular") val popular: List<BaseItemDto>,
    @SerialName("albums") val albums: List<BaseItemDto>
)
