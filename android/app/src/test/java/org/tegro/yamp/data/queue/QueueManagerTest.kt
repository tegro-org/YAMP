package org.tegro.yamp.data.queue

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tegro.yamp.data.dto.ArtistDto
import org.tegro.yamp.data.dto.TrackDetailsDto
import org.tegro.yamp.di.DispatcherProvider
import org.tegro.yamp.domain.queue.QueueEntry
import org.tegro.yamp.domain.queue.QueueKind
import org.tegro.yamp.domain.queue.RepeatMode
import org.tegro.yamp.domain.queue.TrackRef

/** The manager owns the active queue and writes every change through before returning (E3, E5). */
@OptIn(ExperimentalCoroutinesApi::class)
class QueueManagerTest {
    private val store = FakeQueueStore()
    private val remembered = mutableListOf<TrackDetailsDto>()
    private val bridge = object : QueueTrackBridgeApi {
        override suspend fun remember(tracks: List<TrackDetailsDto>) { remembered += tracks }
        override suspend fun recall(entries: List<QueueEntry>): List<TrackDetailsDto> = entries.map { e -> dto(e.ref.id, e.title) }
    }

    private fun dto(id: String, title: String = "Track $id") =
        TrackDetailsDto(id = JsonPrimitive(id), title = title, artists = listOf(ArtistDto(name = "Band")))

    private fun manager(dispatcher: kotlinx.coroutines.CoroutineDispatcher): QueueManager {
        val dispatchers = object : DispatcherProvider {
            override val main get() = dispatcher
            override val io get() = dispatcher
            override val default get() = dispatcher
        }
        return QueueManager(store, bridge, CoroutineScope(SupervisorJob() + dispatcher), dispatchers)
    }

    @Test
    fun playingAListReplacesTheActiveQueueAndCreatesItWhenThereIsNone() = runTest {
        val m = manager(StandardTestDispatcher(testScheduler))
        m.load()
        assertNull(m.active.value)
        val active = m.replaceActive(listOf(dto("1"), dto("2"), dto("1")), origin = "Album · X", startRef = TrackRef.yandex("2"), nameIfNew = "Main")
        assertEquals("Main", active.queue.name)
        assertEquals(3, active.size)
        assertEquals("the cursor is on the chosen track", 1, active.cursorIndex)
        assertEquals("duplicates are two entries", 3, active.entries.map { it.entryId }.distinct().size)
        assertEquals(listOf("1", "2", "1"), active.tracks.map { it.idString })
        assertTrue("the DTOs were remembered for playback", remembered.size == 3)
        assertEquals(active.queue.id, store.queuesById.keys.single())
    }

    @Test
    fun theCursorMovesByEntryIdNotByTrackId() = runTest {
        val m = manager(StandardTestDispatcher(testScheduler))
        m.load()
        val a = m.replaceActive(listOf(dto("1"), dto("2"), dto("1")), null, null, "Main")
        m.moveCursor(a.entries[2].entryId, positionMs = 5_000L)
        assertEquals(2, m.active.value!!.cursorIndex)
        assertEquals(a.entries[2].entryId, store.queuesById[a.queue.id]!!.cursorEntryId)
        assertEquals(5_000L, store.queuesById[a.queue.id]!!.positionMs)
    }

    @Test
    fun everyMutationIsWrittenBeforeItReturns() = runTest {
        val m = manager(StandardTestDispatcher(testScheduler))
        m.load()
        val a = m.replaceActive(listOf(dto("1"), dto("2")), null, null, "Main")
        val before = store.writes
        m.insertAfterCursor(listOf(dto("9")))
        assertTrue(store.writes > before)
        assertEquals(listOf("1", "9", "2"), m.active.value!!.entries.map { it.ref.id })
        val w2 = store.writes
        m.remove(m.active.value!!.entries[2].entryId)
        assertTrue(store.writes > w2)
        assertEquals(listOf("1", "9"), m.active.value!!.entries.map { it.ref.id })
        m.savePosition(42L)
        assertEquals(42L, store.queuesById[a.queue.id]!!.positionMs)
    }

    @Test
    fun removingTheCursorEntryIsRefused() = runTest {
        val m = manager(StandardTestDispatcher(testScheduler))
        m.load()
        val a = m.replaceActive(listOf(dto("1"), dto("2")), null, null, "Main")
        m.remove(a.entries[0].entryId)
        assertEquals(2, m.active.value!!.size)
    }

    @Test
    fun switchingSavesThePositionAndLoadsTheTargetAtItsCursor() = runTest {
        val m = manager(StandardTestDispatcher(testScheduler))
        m.load()
        val main = m.replaceActive(listOf(dto("1"), dto("2")), null, null, "Main")
        val family = m.create("With family")
        m.appendTo(family.id, listOf(dto("7"), dto("8")))
        store.saveCursor(family.id, store.entriesByQueue[family.id]!![1].entryId, 30_000L)
        m.switchTo(family.id, currentPositionMs = 12_000L)
        assertEquals(12_000L, store.queuesById[main.queue.id]!!.positionMs)
        val active = m.active.value!!
        assertEquals(family.id, active.queue.id)
        assertEquals(1, active.cursorIndex)
        assertEquals(30_000L, active.queue.positionMs)
        testScheduler.advanceUntilIdle() // the summaries flow is collected on the app scope
        assertTrue(m.queues.value.single { it.queue.id == family.id }.isActive)
    }

    @Test
    fun deletingTheActiveQueueActivatesTheNextOne() = runTest {
        val m = manager(StandardTestDispatcher(testScheduler))
        m.load()
        val main = m.replaceActive(listOf(dto("1")), null, null, "Main")
        val second = m.create("Second")
        m.delete(main.queue.id)
        assertEquals(second.id, m.active.value?.queue?.id)
        m.delete(second.id)
        assertNull(m.active.value)
    }

    @Test
    fun shuffleKeepsTheCursorFirstAndUnshuffleRestoresTheOrder() = runTest {
        val m = manager(StandardTestDispatcher(testScheduler))
        m.load()
        val a = m.replaceActive((1..6).map { dto("$it") }, null, null, "Main")
        m.moveCursor(a.entries[2].entryId)
        m.setShuffle(true)
        val shuffled = m.active.value!!
        assertTrue(shuffled.queue.shuffle)
        assertEquals("3", shuffled.cursorEntry!!.ref.id)
        assertEquals(0, shuffled.cursorIndex)
        m.setShuffle(false)
        assertEquals((1..6).map { "$it" }, m.active.value!!.entries.map { it.ref.id })
        assertEquals(2, m.active.value!!.cursorIndex)
    }

    @Test
    fun repeatIsPerQueue() = runTest {
        val m = manager(StandardTestDispatcher(testScheduler))
        m.load()
        m.replaceActive(listOf(dto("1")), null, null, "Main")
        m.setRepeat(RepeatMode.OFF)
        assertEquals(RepeatMode.OFF, m.active.value!!.queue.repeat)
        assertEquals(RepeatMode.OFF, store.queuesById.values.single().repeat)
    }

    @Test
    fun aWaveQueueIsOnePerAccountAndOnlyGrows() = runTest {
        val m = manager(StandardTestDispatcher(testScheduler))
        m.load()
        val wave = m.waveQueue("u1", "My Wave")
        assertEquals(wave.id, m.waveQueue("u1", "My Wave").id)
        assertEquals(QueueKind.WAVE, wave.kind)
        m.appendFetched(wave.id, listOf(dto("1"), dto("2")))
        m.appendFetched(wave.id, listOf(dto("2"), dto("3")))
        assertEquals(listOf("1", "2", "3"), store.entriesByQueue[wave.id]!!.map { it.ref.id })
    }

    @Test
    fun loadIsReadOnly() = runTest {
        val m = manager(StandardTestDispatcher(testScheduler))
        val q = store.create(org.tegro.yamp.domain.queue.PlayQueue(0L, "Main", QueueKind.STATIC))
        store.setActiveQueueId(q.id)
        val writes = store.writes
        m.load()
        assertEquals(writes, store.writes)
        assertEquals(q.id, m.active.value?.queue?.id)
        assertTrue(m.loaded.value)
    }
}
