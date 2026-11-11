package org.tegro.yamp.data.queue

import android.util.Log
import org.tegro.yamp.data.cache.PlaybackStateStore
import org.tegro.yamp.data.cache.PlaylistCacheManager
import org.tegro.yamp.data.dto.TrackDetailsDto
import org.tegro.yamp.domain.queue.PlayQueue
import org.tegro.yamp.domain.queue.QueueKind
import org.tegro.yamp.domain.queue.QueueStore
import org.tegro.yamp.domain.queue.TrackRef
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-time import of the pre-Phase-1 session (`playback_state.json`, one snapshot with a 50/150 window) into
 * the first queue (docs/phase-1-queues.md §2). Runs only while the `queues` table is empty, in one
 * transaction per queue, and deletes the file afterwards: a kill in between re-runs it on the next start
 * (DPI rule 4). My Wave becomes the account's WAVE queue (snapshot ∪ the cached wave list); anything else
 * becomes "Main"; an FM station is not a queue and is not imported.
 */
@Singleton
class LegacySessionImport @Inject constructor(
    private val stateStore: PlaybackStateStore,
    private val cacheManager: PlaylistCacheManager,
    private val store: QueueStore,
    private val tracks: QueueTrackBridgeApi
) {
    /** Returns the active queue id after the import, or null when there was nothing to import. */
    suspend fun run(mainName: String, waveName: String): Long? {
        if (store.queues().isNotEmpty()) return null
        val snapshot = stateStore.load() ?: return null
        val result = runCatching { import(snapshot, mainName, waveName) }
            .onFailure { Log.w(TAG, "Legacy session not imported", it) }
            .getOrNull()
        if (result != null || snapshot.queue.isEmpty()) stateStore.clear()
        return result
    }

    private suspend fun import(snapshot: org.tegro.yamp.data.cache.PlaybackSnapshot, mainName: String, waveName: String): Long? {
        val isWave = snapshot.source == "VIBE"
        val isRadio = snapshot.source == "RADIO" || snapshot.currentTrackId.startsWith("radio_")
        if (isRadio) return null
        val list: List<TrackDetailsDto> = if (isWave) {
            val cached = cacheManager.loadWaveQueue(snapshot.userFolder)
            (snapshot.queue + cached).distinctBy { it.idString }
        } else snapshot.queue
        if (list.isEmpty()) return null

        val queue = store.create(
            if (isWave) PlayQueue(0L, waveName, QueueKind.WAVE, account = snapshot.userFolder, seed = snapshot.waveSeed?.idString, sortOrder = -1)
            else PlayQueue(0L, mainName, QueueKind.STATIC, origin = snapshot.source.lowercase().replaceFirstChar { it.uppercase() })
        )
        tracks.remember(list)
        val entries = store.replaceEntries(queue.id, list.map { it.toEntry() })
        val cursor = entries.firstOrNull { it.ref.id == snapshot.currentTrackId } ?: entries.first()
        store.saveCursor(queue.id, cursor.entryId, snapshot.positionMs)
        store.setActiveQueueId(queue.id)
        Log.i(TAG, "Imported the old session as queue ${queue.id} (${list.size} tracks, wave=$isWave)")
        return queue.id
    }

    private fun TrackDetailsDto.toEntry() = org.tegro.yamp.domain.queue.QueueEntry(
        entryId = 0L, queueId = 0L, position = 0, originalPosition = 0,
        ref = TrackRef.yandex(idString), title = title.orEmpty(),
        artist = artists.joinToString(", ") { it.name.orEmpty() }, coverUrl = coverUri, durationMs = durationMs ?: 0L
    )

    private companion object {
        const val TAG = "LegacySessionImport"
    }
}
