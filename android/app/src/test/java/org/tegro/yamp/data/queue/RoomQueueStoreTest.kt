package org.tegro.yamp.data.queue

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.tegro.yamp.data.cache.CacheDatabase
import org.tegro.yamp.data.cache.TrackEntity
import org.tegro.yamp.domain.queue.PlayQueue
import org.tegro.yamp.domain.queue.QueueEntry
import org.tegro.yamp.domain.queue.QueueKind
import org.tegro.yamp.domain.queue.TrackRef

/** The queue store over an in-memory Room: write-through, duplicates, cursor by entry id, orphan rule. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RoomQueueStoreTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CacheDatabase::class.java)
        .allowMainThreadQueries().build()
    private var clock = 1_000L
    private val store = RoomQueueStore(db.queueDao(), db.dao(), now = { clock })

    private fun entry(id: String, title: String = "Track $id") =
        QueueEntry(entryId = 0L, queueId = 0L, position = 0, originalPosition = 0, ref = TrackRef.yandex(id), title = title, artist = "Band")

    @After
    fun tearDown() = db.close()

    @Test
    fun aQueueIsCreatedFilledAndReadBackInPlayOrder() = runBlocking {
        val q = store.create(PlayQueue(id = 0L, name = "With family", kind = QueueKind.STATIC))
        assertTrue(q.id > 0L)
        val entries = store.replaceEntries(q.id, listOf(entry("1"), entry("2"), entry("1")))
        assertEquals(listOf(0, 1, 2), entries.map { it.position })
        assertEquals("the same track twice is two entries", 3, entries.map { it.entryId }.distinct().size)
        assertEquals(listOf("yandex:track:1", "yandex:track:2", "yandex:track:1"), store.entries(q.id).map { it.ref.value })
    }

    @Test
    fun theCursorIsAnEntryIdAndSurvivesARead() = runBlocking {
        val q = store.create(PlayQueue(id = 0L, name = "Solo", kind = QueueKind.STATIC))
        val entries = store.replaceEntries(q.id, listOf(entry("1"), entry("2")))
        store.saveCursor(q.id, entries[1].entryId, 42_000L)
        val read = store.queue(q.id)!!
        assertEquals(entries[1].entryId, read.cursorEntryId)
        assertEquals(42_000L, read.positionMs)
        assertEquals(clock, read.updatedAt)
    }

    @Test
    fun insertingAfterTheCursorShiftsWhatFollowsAndKeepsInsertionOrder() = runBlocking {
        val q = store.create(PlayQueue(id = 0L, name = "Q", kind = QueueKind.STATIC))
        store.replaceEntries(q.id, listOf(entry("1"), entry("2"), entry("3")))
        store.insertEntries(q.id, atPosition = 1, entries = listOf(entry("9")))
        val refs = store.entries(q.id).map { it.ref.id }
        assertEquals(listOf("1", "9", "2", "3"), refs)
        assertEquals("the newcomer is last in insertion order", 3, store.entries(q.id).first { it.ref.id == "9" }.originalPosition)
    }

    @Test
    fun theActiveQueueAndTheSummariesFollowTheStore() = runBlocking {
        val a = store.create(PlayQueue(id = 0L, name = "A", kind = QueueKind.STATIC))
        val b = store.create(PlayQueue(id = 0L, name = "B", kind = QueueKind.WAVE, account = "u1"))
        val es = store.replaceEntries(a.id, listOf(entry("1"), entry("2")))
        store.saveCursor(a.id, es[0].entryId, 0L)
        assertNull(store.activeQueueId().first())
        store.setActiveQueueId(b.id)
        val summaries = store.summaries(store.activeQueueId()).first()
        assertEquals(listOf("A", "B"), summaries.map { it.queue.name })
        assertEquals(2, summaries[0].size)
        assertEquals("Track 1", summaries[0].current?.title)
        assertTrue(summaries[1].isActive)
        assertEquals(0, summaries[1].size)
    }

    @Test
    fun deletingAQueueTakesItsEntriesAlong() = runBlocking {
        val q = store.create(PlayQueue(id = 0L, name = "Gone", kind = QueueKind.STATIC))
        store.replaceEntries(q.id, listOf(entry("1")))
        store.delete(q.id)
        assertEquals(0, db.queueDao().size(q.id))
    }

    @Test
    fun aTrackInAQueueIsNotAnOrphan() = runBlocking {
        db.dao().upsertTracks(listOf(TrackEntity("1", "{}"), TrackEntity("2", "{}")))
        val q = store.create(PlayQueue(id = 0L, name = "Q", kind = QueueKind.STATIC))
        store.replaceEntries(q.id, listOf(entry("1")))
        db.dao().deleteOrphanTracks()
        assertEquals(listOf("1"), db.dao().tracksById(listOf("1", "2")).map { it.id })
    }
}
