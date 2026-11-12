package org.tegro.yamp.data.history

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tegro.yamp.data.dto.TrackDetailsDto
import org.tegro.yamp.data.queue.QueueTrackBridgeApi
import org.tegro.yamp.di.AppScope
import org.tegro.yamp.di.DispatcherProvider
import org.tegro.yamp.domain.queue.PlayQueue
import org.tegro.yamp.domain.queue.QueueEntry
import org.tegro.yamp.domain.queue.TrackRef
import org.tegro.yamp.domain.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/** A history row with the track object the rows' actions need (the Phase 1 bridge, as the queues do). */
data class HistoryItem(val row: HistoryEntity, val track: TrackDetailsDto)

/**
 * The listening history (docs/phase-1b-history.md): every track that sounded, app-wide. The controller reports
 * [started] when the engine's sounding track becomes the current one and [stopped] when it stops sounding; the
 * same track sounding again within [MERGE_WINDOW_MS] of its row adds to that row instead of a new one. Each
 * event is one write, in call order, on the IO dispatcher; nothing is recorded while the setting is off.
 */
@Singleton
class ListeningHistory(
    private val dao: HistoryDao,
    private val tracks: QueueTrackBridgeApi,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val now: () -> Long
) {
    /** The injected form: the clock is a test seam only. */
    @Inject
    constructor(
        dao: HistoryDao,
        tracks: QueueTrackBridgeApi,
        settings: SettingsRepository,
        @AppScope scope: CoroutineScope,
        dispatchers: DispatcherProvider
    ) : this(dao, tracks, settings, scope, dispatchers, System::currentTimeMillis)

    /** The row of the track sounding now and what it had played before this time. */
    private class Open(val rowId: Long, val playedBefore: Long)

    private var open: Open? = null
    private var lastWrite: Job? = null

    /** [track] started sounding out of [queue] (null when it came from nowhere in particular). Stations are not kept. */
    fun started(track: TrackDetailsDto, queue: PlayQueue?) {
        if (track.isRadio) return
        write {
            open = null
            if (!settings.listeningHistory.first()) return@write
            val time = now()
            val last = dao.lastFor(track.idString)
            open = if (last != null && time - last.startedAt < MERGE_WINDOW_MS) {
                Open(last.id, last.playedMs)
            } else {
                Open(dao.insertAndPrune(rowOf(track, queue, time), MAX_ROWS), 0L)
            }
        }
    }

    /** The sounding track stopped (next, pause, end) after [playedMs] of sound. */
    fun stopped(playedMs: Long) {
        write {
            val row = open ?: return@write
            open = null
            dao.updatePlayed(row.rowId, row.playedBefore + playedMs)
        }
    }

    /** The newest rows, all queues or [queueId] only, each with its track object. */
    fun items(queueId: Long?, limit: Int = PAGE): Flow<List<HistoryItem>> =
        (if (queueId == null) dao.recent(limit) else dao.recentOf(queueId, limit)).map { rows ->
            withContext(dispatchers.io) { rows.zip(tracks.recall(rows.map { it.toEntry() }), ::HistoryItem) }
        }

    fun queues(): Flow<List<HistoryQueue>> = dao.queuesInHistory()

    fun countSince(sinceMs: Long): Flow<Int> = dao.countSince(sinceMs)

    fun clear() = write {
        open = null
        dao.clear()
    }

    /** Waits for the writes asked for so far; the tests need it. */
    suspend fun awaitIdle() {
        lastWrite?.join()
    }

    /** One write after another, in call order, so a stop never lands before its start. */
    private fun write(block: suspend () -> Unit) {
        val previous = lastWrite
        val job = scope.launch(start = CoroutineStart.LAZY) {
            previous?.join()
            withContext(dispatchers.io) { block() }
        }
        lastWrite = job
        job.start()
    }

    private fun rowOf(track: TrackDetailsDto, queue: PlayQueue?, time: Long) = HistoryEntity(
        ref = TrackRef.yandex(track.idString).value,
        trackId = track.idString,
        title = track.title.orEmpty(),
        artist = track.artists.joinToString(", ") { it.name.orEmpty() },
        coverUrl = track.coverUri,
        durationMs = track.durationMs ?: 0L,
        startedAt = time,
        queueId = queue?.id,
        queueName = queue?.name
    )

    /** The row as a queue entry, for the bridge's track lookup (the DTO, or a stub from the display columns). */
    private fun HistoryEntity.toEntry() = QueueEntry(
        entryId = id, queueId = queueId ?: 0L, position = 0, originalPosition = 0,
        ref = TrackRef(ref), title = title, artist = artist, coverUrl = coverUrl, durationMs = durationMs
    )

    companion object {
        const val MERGE_WINDOW_MS = 10 * 60_000L
        const val MAX_ROWS = 5_000
        /** Rows a page reads at most; older ones stay in the table until pruned. */
        const val PAGE = 1_000
    }
}
