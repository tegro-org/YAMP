package org.tegro.yamp.provider.jellyfin

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tegro.yamp.domain.provider.Outcome
import org.tegro.yamp.domain.provider.ProviderError
import java.io.IOException

/** Adding to a Jellyfin playlist and creating one (`POST /Playlists`, `POST /Playlists/{id}/Items`). */
class JellyfinPlaylistEditTest {
    private val mappers = JellyfinMappers(JellyfinRefs("server"), "http://10.0.2.2:8096/")

    private val cache = object : JellyfinListCache {
        override suspend fun get(key: String): String? = null
        override suspend fun put(key: String, json: String) {}
    }

    private class Api : FakeJellyfinApi() {
        val inPlaylist = mutableListOf("a")
        var added: Pair<String, String>? = null
        var created: CreatePlaylistDto? = null
        var down: IOException? = null

        override suspend fun playlistItems(playlistId: String, userId: String, startIndex: Int?, limit: Int?, fields: String?): ItemsResultDto {
            down?.let { throw it }
            return ItemsResultDto(inPlaylist.map { BaseItemDto(id = it) }, inPlaylist.size)
        }

        override suspend fun addToPlaylist(playlistId: String, ids: String, userId: String) {
            added = playlistId to ids
            inPlaylist += ids.split(",")
        }

        override suspend fun createPlaylist(body: CreatePlaylistDto): CreatePlaylistResultDto {
            created = body
            return CreatePlaylistResultDto("new")
        }

        override suspend fun item(userId: String, itemId: String): BaseItemDto = BaseItemDto(id = itemId, name = "Road trip", type = "Playlist")
    }

    @Test
    fun `adding skips the tracks already there and sends the rest in one call`() = runTest {
        val api = Api()
        val outcome = JellyfinLibrary(api, "user", mappers, cache).addToPlaylist("p1", listOf("a", "b", "c", "b"))

        assertEquals(2, (outcome as Outcome.Ok).value)
        assertEquals("p1" to "b,c", api.added)
    }

    @Test
    fun `nothing new means no add call`() = runTest {
        val api = Api()
        val outcome = JellyfinLibrary(api, "user", mappers, cache).addToPlaylist("p1", listOf("a"))

        assertEquals(0, (outcome as Outcome.Ok).value)
        assertEquals(null, api.added)
    }

    @Test
    fun `a new playlist is created with its tracks as an audio playlist of the user`() = runTest {
        val api = Api()
        val outcome = JellyfinLibrary(api, "user", mappers, cache).createPlaylist("  Road trip ", listOf("x", "y", "x"))

        assertTrue(outcome is Outcome.Ok)
        assertEquals(CreatePlaylistDto("Road trip", listOf("x", "y"), "user", "Audio"), api.created)
    }

    @Test
    fun `an unreachable server is a network error, nothing queued`() = runTest {
        val api = Api().apply { down = IOException("no route") }
        val outcome = JellyfinLibrary(api, "user", mappers, cache).addToPlaylist("p1", listOf("b"))

        assertEquals(ProviderError.Network, (outcome as Outcome.Err).error)
        assertEquals(null, api.added)
    }
}
