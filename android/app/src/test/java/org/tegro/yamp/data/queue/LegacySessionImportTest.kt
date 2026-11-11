package org.tegro.yamp.data.queue

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.tegro.yamp.data.cache.PlaybackSnapshot
import org.tegro.yamp.data.cache.PlaybackStateStore
import org.tegro.yamp.data.cache.PlaylistCacheManager
import org.tegro.yamp.data.dto.ArtistDto
import org.tegro.yamp.data.dto.TrackDetailsDto
import org.tegro.yamp.domain.queue.QueueEntry
import org.tegro.yamp.domain.queue.QueueKind

/** The old single session becomes the first queue, once, and the file goes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LegacySessionImportTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val json = Json { ignoreUnknownKeys = true }
    private val stateStore = PlaybackStateStore(context, json)
    private val store = FakeQueueStore()
    private val bridge = object : QueueTrackBridgeApi {
        override suspend fun remember(tracks: List<TrackDetailsDto>) = Unit
        override suspend fun recall(entries: List<QueueEntry>): List<TrackDetailsDto> = emptyList()
    }
    private val import = LegacySessionImport(stateStore, PlaylistCacheManager(context, json), store, bridge)

    private fun dto(id: String) = TrackDetailsDto(id = JsonPrimitive(id), title = "T$id", artists = listOf(ArtistDto(name = "A")))

    @Test
    fun aLibrarySessionBecomesMainWithItsCursorAndPosition() = runBlocking {
        stateStore.save(PlaybackSnapshot("driver", "LIBRARY", listOf(dto("1"), dto("2"), dto("3")), currentTrackId = "2", positionMs = 42_000L))
        val id = import.run("Main", "My Wave")!!
        val q = store.queuesById[id]!!
        assertEquals("Main", q.name)
        assertEquals(QueueKind.STATIC, q.kind)
        assertEquals("Library", q.origin)
        val entries = store.entriesByQueue[id]!!
        assertEquals(listOf("1", "2", "3"), entries.map { it.ref.id })
        assertEquals(entries[1].entryId, q.cursorEntryId)
        assertEquals(42_000L, q.positionMs)
        assertNull("the file is gone", stateStore.load())
        assertNull("a second run imports nothing", import.run("Main", "My Wave"))
    }

    @Test
    fun aWaveSessionBecomesTheAccountsWaveQueue() = runBlocking {
        stateStore.save(PlaybackSnapshot("driver", "VIBE", listOf(dto("5")), currentTrackId = "5", positionMs = 0L))
        val id = import.run("Main", "My Wave")!!
        val q = store.queuesById[id]!!
        assertEquals(QueueKind.WAVE, q.kind)
        assertEquals("driver", q.account)
        assertEquals("My Wave", q.name)
    }

    @Test
    fun anFmStationIsNotImported() = runBlocking {
        stateStore.save(PlaybackSnapshot("driver", "RADIO", listOf(dto("radio_1")), currentTrackId = "radio_1", positionMs = 0L))
        assertNull(import.run("Main", "My Wave"))
        assertEquals(0, store.queuesById.size)
    }
}
