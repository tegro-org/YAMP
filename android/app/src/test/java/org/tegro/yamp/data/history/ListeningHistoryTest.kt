package org.tegro.yamp.data.history

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.tegro.yamp.data.cache.CacheDatabase
import org.tegro.yamp.data.cache.TrackEntity
import org.tegro.yamp.data.dto.ArtistDto
import org.tegro.yamp.data.dto.TrackAlbumDto
import org.tegro.yamp.data.dto.TrackDetailsDto
import org.tegro.yamp.data.player.fakes.FakeSettingsRepository
import org.tegro.yamp.data.queue.QueueTrackBridge
import org.tegro.yamp.di.DispatcherProvider
import org.tegro.yamp.domain.queue.PlayQueue
import org.tegro.yamp.domain.queue.QueueKind

/** The history over an in-memory Room and a fake clock: rows, the merge window, the cap, the setting, the reads. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ListeningHistoryTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }
    private val db = Room.inMemoryDatabaseBuilder(context, CacheDatabase::class.java)
        .allowMainThreadQueries()
        .setQueryExecutor { it.run() }
        .setTransactionExecutor { it.run() }
        .build()
    private val dao = db.historyDao()
    private val settings = FakeSettingsRepository()
    private val dispatcher = UnconfinedTestDispatcher()
    private val dispatchers = object : DispatcherProvider {
        override val main get() = dispatcher
        override val io get() = dispatcher
        override val default get() = dispatcher
    }
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var clock = 1_700_000_000_000L
    private val history = ListeningHistory(dao, QueueTrackBridge(db.dao(), json), settings, scope, dispatchers) { clock }

    private val family = PlayQueue(id = 7L, name = "With family", kind = QueueKind.STATIC)
    private val solo = PlayQueue(id = 8L, name = "Solo drive", kind = QueueKind.STATIC)

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun track(id: String, title: String = "Track $id") =
        TrackDetailsDto(id = JsonPrimitive(id), title = title, artists = listOf(ArtistDto(name = "Band $id")), durationMs = 200_000, coverUri = "cover/$id")

    private fun rows() = runBlocking {
        history.awaitIdle()
        dao.recent(100).first()
    }

    @Test
    fun aTrackThatSoundedIsRecordedWithItsQueueAndItsPlayedTime() {
        history.started(track("1"), family)
        history.stopped(30_000L)

        val row = rows().single()
        assertEquals("yandex:track:1", row.ref)
        assertEquals("1", row.trackId)
        assertEquals("Track 1", row.title)
        assertEquals("Band 1", row.artist)
        assertEquals("cover/1", row.coverUrl)
        assertEquals(200_000L, row.durationMs)
        assertEquals(clock, row.startedAt)
        assertEquals(30_000L, row.playedMs)
        assertEquals(7L, row.queueId)
        assertEquals("With family", row.queueName)
    }

    @Test
    fun theSameTrackWithinTenMinutesAddsToItsRowAndAfterThatGetsANewOne() {
        history.started(track("1"), family)
        history.stopped(60_000L)
        clock += 5 * 60_000L
        history.started(track("1"), family)
        history.stopped(20_000L)

        val merged = rows().single()
        assertEquals("one row, the time added up", 80_000L, merged.playedMs)
        assertEquals("the row keeps when it first sounded", 1_700_000_000_000L, merged.startedAt)

        clock += ListeningHistory.MERGE_WINDOW_MS
        history.started(track("1"), family)
        history.stopped(5_000L)
        assertEquals(listOf(5_000L, 80_000L), rows().map { it.playedMs })
    }

    @Test
    fun aStopWithoutAStartAndAStartWithoutAStopAreHarmless() {
        history.stopped(10_000L)
        history.started(track("1"), family)
        history.started(track("2"), family)
        history.stopped(10_000L)

        val rows = rows()
        assertEquals(listOf("2", "1"), rows.map { it.trackId })
        assertEquals("the stop belongs to the track that sounded last", listOf(10_000L, 0L), rows.map { it.playedMs })
    }

    @Test
    fun nothingIsRecordedWhileTheSettingIsOffAndStationsNever() {
        settings.listeningHistory.value = false
        history.started(track("1"), family)
        history.stopped(10_000L)
        assertTrue(rows().isEmpty())

        settings.listeningHistory.value = true
        history.started(track("radio_1").copy(isRadio = true), null)
        history.stopped(10_000L)
        assertTrue(rows().isEmpty())
    }

    @Test
    fun theTableKeepsOnlyTheNewestRows() = runBlocking {
        (1..3).forEach { i -> dao.insertAndPrune(HistoryEntity(ref = "yandex:track:$i", trackId = "$i", title = "", artist = "", startedAt = clock + i), keep = 2) }
        assertEquals(listOf("3", "2"), dao.recent(10).first().map { it.trackId })
    }

    @Test
    fun theRowsOfOneQueueTheQueuesInTheHistoryAndTheWeekCount() {
        history.started(track("1"), family)
        history.stopped(1_000L)
        clock += 1_000L
        history.started(track("2"), solo)
        history.stopped(1_000L)
        clock += 1_000L
        history.started(track("3"), solo.copy(name = "Renamed"))
        history.stopped(1_000L)
        clock += 1_000L
        history.started(track("4"), null)
        history.stopped(1_000L)

        runBlocking {
            history.awaitIdle()
            assertEquals(listOf("4", "3", "2", "1"), history.items(null).first().map { it.row.trackId })
            assertEquals(listOf("3", "2"), history.items(8L).first().map { it.row.trackId })
            assertEquals("newest first, each under its latest name", listOf(HistoryQueue(8L, "Renamed"), HistoryQueue(7L, "With family")), history.queues().first())
            assertEquals(3, history.countSince(1_700_000_000_000L + 1_000L).first())
        }
    }

    @Test
    fun itemsCarryTheCachedTrackObjectOrAStubFromTheRow() = runBlocking {
        val full = track("1", "Wish You Were Here").copy(albums = listOf(TrackAlbumDto(id = JsonPrimitive(5), title = "Wish You Were Here")))
        db.dao().upsertTracks(listOf(TrackEntity("1", json.encodeToString(full))))
        history.started(full, family)
        history.stopped(1_000L)
        history.started(track("2", "Echoes"), family)
        history.stopped(1_000L)
        history.awaitIdle()

        val items = history.items(null).first()
        assertEquals(listOf("2", "1"), items.map { it.track.idString })
        assertEquals("the stub shows what the row kept", "Echoes", items[0].track.title)
        assertEquals("the cached DTO has what the row does not", "Wish You Were Here", items[1].track.albums.single().title)
    }

    @Test
    fun theHistoryKeepsItsTracksCachedUntilItIsCleared() = runBlocking {
        db.dao().upsertTracks(listOf(TrackEntity("1", json.encodeToString(track("1")))))
        history.started(track("1"), family)
        history.stopped(1_000L)
        history.awaitIdle()

        db.dao().deleteOrphanTracks()
        assertEquals(listOf("1"), db.dao().tracksById(listOf("1")).map { it.id })

        history.clear()
        history.awaitIdle()
        assertTrue(dao.recent(10).first().isEmpty())
        db.dao().deleteOrphanTracks()
        assertTrue(db.dao().tracksById(listOf("1")).isEmpty())
    }
}
