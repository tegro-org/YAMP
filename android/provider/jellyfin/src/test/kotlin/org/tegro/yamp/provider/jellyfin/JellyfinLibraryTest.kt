package org.tegro.yamp.provider.jellyfin

import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tegro.yamp.domain.provider.Outcome
import org.tegro.yamp.domain.provider.ProviderError
import org.tegro.yamp.domain.queue.MediaRef
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

/** The library's lists and pages against JSON recorded from the dev server (scripts/jellyfin-dev, D85); ids scrubbed. */
class JellyfinLibraryTest {
    private val serverId = "0123456789abcdef0123456789abcdef"
    private val mappers = JellyfinMappers(JellyfinRefs(serverId), "http://10.0.2.2:8096/")
    private val json = JellyfinApiFactory.json
    private fun result(name: String) = json.decodeFromString<ItemsResultDto>(resource(name))

    private class MapCache : JellyfinListCache {
        val map = mutableMapOf<String, String>()
        override suspend fun get(key: String) = map[key]
        override suspend fun put(key: String, json: String) {
            map[key] = json
        }
    }

    /** The recorded library; [down] makes every call fail as an unreachable server does. */
    private inner class RecordedApi : FakeJellyfinApi() {
        var down: Throwable? = null
        val calls = mutableListOf<String>()

        override suspend fun items(
            userId: String, includeItemTypes: String?, parentId: String?, recursive: Boolean?, sortBy: String?,
            startIndex: Int?, limit: Int?, fields: String?, albumArtistIds: String?, artistIds: String?, genreIds: String?,
            sortOrder: String?, filters: String?, searchTerm: String?
        ): ItemsResultDto {
            down?.let { throw it }
            calls += "items:$includeItemTypes:$parentId:$startIndex:$limit"
            searchTerm?.let { term -> calls += "search:$includeItemTypes:$term" }
            val all = when (includeItemTypes) {
                "MusicAlbum" -> result("items-albums.json")
                "Playlist" -> result("playlists.json")
                else -> result("items-audio.json").let { r -> parentId?.let { id -> r.copy(items = r.items.filter { it.albumId == id }) } ?: r }
            }
            val found = searchTerm?.let { term -> all.copy(items = all.items.filter { it.name.orEmpty().contains(term, ignoreCase = true) }) } ?: all
            return found.window(startIndex, limit)
        }

        override suspend fun item(userId: String, itemId: String): BaseItemDto {
            down?.let { throw it }
            return (result("items-albums.json").items + result("playlists.json").items + result("artists.json").items).first { it.id == itemId }
        }

        override suspend fun albumArtists(userId: String, searchTerm: String?, sortBy: String?, startIndex: Int?, limit: Int?, fields: String?) =
            down?.let { throw it } ?: result("artists.json").let { r ->
                searchTerm?.let { term -> r.copy(items = r.items.filter { it.name.orEmpty().contains(term, ignoreCase = true) }) } ?: r
            }.window(startIndex, limit)

        override suspend fun musicGenres(userId: String, sortBy: String?, startIndex: Int?, limit: Int?) =
            down?.let { throw it } ?: result("genres.json").window(startIndex, limit)

        override suspend fun playlistItems(playlistId: String, userId: String, startIndex: Int?, limit: Int?, fields: String?) =
            down?.let { throw it } ?: result("playlist-items.json")

        private fun ItemsResultDto.window(start: Int?, limit: Int?): ItemsResultDto {
            val from = start ?: 0
            val to = if (limit == null) items.size else (from + limit).coerceAtMost(items.size)
            return ItemsResultDto(items = items.subList(from.coerceAtMost(items.size), to), totalRecordCount = items.size, startIndex = from)
        }
    }

    private val api = RecordedApi()
    private val cache = MapCache()

    @Test
    fun countsComeFromTheServersTotals() = runTest {
        val counts = (JellyfinLibrary(api, "u", mappers, cache).counts() as Outcome.Ok).value
        assertEquals(2, counts.albums)
        assertEquals(1, counts.artists)
        assertEquals(4, counts.genres)
        assertEquals(1, counts.playlists)
    }

    @Test
    fun aListIsPagedUntilTheServerHasNoMore() = runTest {
        val library = JellyfinLibrary(api, "u", mappers, cache, pageSize = 1)
        val first = (library.albums(null) as Outcome.Ok).value
        assertEquals(1, first.items.size)
        assertNotNull(first.next)
        val next = first.next!!
        val second = (library.albums(next) as Outcome.Ok).value
        assertEquals(1, second.items.size)
        assertNull("the second album is the last", second.next)
        assertTrue(first.items[0].ref != second.items[0].ref)
    }

    @Test
    fun genresAndArtistsMapWithRefsNamingTheServer() = runTest {
        val library = JellyfinLibrary(api, "u", mappers, cache)
        val genres = (library.genres(null) as Outcome.Ok).value.items
        assertEquals(listOf("celtic", "electronica", "irish folk", "production music"), genres.map { it.name })
        assertEquals("jellyfin@$serverId", genres[0].ref.provider)
        assertEquals("genre", genres[0].ref.kind)
        val artist = (library.artists(null) as Outcome.Ok).value.items.single()
        assertEquals("Kevin MacLeod", artist.name)
        assertEquals("artist", artist.ref.kind)
    }

    @Test
    fun anAlbumPageHasItsTracksInTrackOrder() = runTest {
        val library = JellyfinLibrary(api, "u", mappers, cache)
        val albumId = result("items-albums.json").items.first { it.name == "YAMP Test FLAC" }.id
        val album = (library.album(albumId) as Outcome.Ok).value
        assertEquals("YAMP Test FLAC", album.title)
        assertEquals(3, album.tracks.size)
        assertEquals(MediaRef("jellyfin@$serverId", "album", albumId), album.ref)
    }

    @Test
    fun aPlaylistPageKeepsThePlaylistOrder() = runTest {
        val library = JellyfinLibrary(api, "u", mappers, cache)
        val id = result("playlists.json").items.single().id
        val playlist = (library.playlist(id) as Outcome.Ok).value
        assertEquals("YAMP Test Playlist", playlist.title)
        assertEquals(listOf("Fluffing a Duck", "Sneaky Snitch", "Carefree", "Monkeys Spinning Monkeys"), playlist.tracks.map { it.title })
    }

    @Test
    fun anUnreachableServerServesTheLastVisitAndSaysSo() = runTest {
        val library = JellyfinLibrary(api, "u", mappers, cache)
        val online = (library.albums(null) as Outcome.Ok).value
        assertTrue(library.reachable.value)

        api.down = IOException("connection refused")
        val offline = (library.albums(null) as Outcome.Ok).value
        assertEquals(online.items.map { it.ref }, offline.items.map { it.ref })
        assertFalse(library.reachable.value)

        api.down = null
        library.albums(null)
        assertTrue("an answer clears the banner", library.reachable.value)
    }

    @Test
    fun anUnreachableServerWithNothingKeptIsANetworkError() = runTest {
        api.down = IOException("connection refused")
        val outcome = JellyfinLibrary(api, "u", mappers, cache).genres(null)
        assertEquals(Outcome.Err(ProviderError.Network), outcome)
    }

    @Test
    fun aRejectedTokenIsUnauthorizedAndNotServedFromTheCache() = runTest {
        val library = JellyfinLibrary(api, "u", mappers, cache)
        library.genres(null)
        api.down = HttpException(Response.error<Any>(401, "".toResponseBody()))
        assertEquals(Outcome.Err(ProviderError.Unauthorized), library.genres(null))
        assertTrue("an answer, even a refusal, means the server is there", library.reachable.value)
    }

    @Test
    fun `search asks for tracks, albums and artists by the typed name and maps them`() = runTest {
        val lib = JellyfinLibrary(api, "user", mappers, cache)
        val term = result("items-audio.json").items.first().name!!.take(4)

        val found = (lib.search(term) as Outcome.Ok).value

        assertTrue(found.tracks.isNotEmpty())
        assertTrue(found.tracks.all { it.title.contains(term, ignoreCase = true) })
        assertTrue(api.calls.contains("search:Audio:$term"))
        assertTrue(api.calls.contains("search:MusicAlbum:$term"))
        assertTrue("a search is never cached", cache.map.isEmpty())
    }

    @Test
    fun `a search on an unreachable server is an error, not a cached answer`() = runTest {
        val lib = JellyfinLibrary(api, "user", mappers, cache)
        api.down = IOException("no route")

        val outcome = lib.search("any")

        assertEquals(ProviderError.Network, (outcome as Outcome.Err).error)
    }
}
