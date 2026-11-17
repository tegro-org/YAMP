package org.tegro.yamp.data.provider.yandex.queue

import android.util.Log
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.tegro.yamp.data.dto.TrackDetailsDto
import org.tegro.yamp.data.provider.yandex.toTrack
import org.tegro.yamp.domain.media.Track
import org.tegro.yamp.data.provider.yandex.network.YandexMusicApi
import org.tegro.yamp.domain.queue.DynamicSource
import org.tegro.yamp.domain.queue.TrackRef
import retrofit2.HttpException
import java.io.IOException

/**
 * A radio's [DynamicSource] (docs/phase-2-radio.md §4): one Yandex rotor [station], fetched the way My Wave
 * is — a request continued from the last queued track plus a fresh one — minus what the queue holds, what was
 * heard lately and what the listener excludes. One fetch at a time per source; the controller owns one source
 * for the active radio and drops it on a switch or re-seed, so a stale batch never lands.
 */
internal class RotorStationSource(
    val station: String,
    /** Whose API: read per call, so the account in force at that moment is used. */
    private val apiOf: () -> YandexMusicApi,
    private val heardLately: (String) -> Boolean,
    private val keep: (TrackDetailsDto) -> Boolean = { true }
) : DynamicSource {
    private val api: YandexMusicApi get() = apiOf()

    private val fetching = Mutex()

    override suspend fun fetchBatch(exclude: Set<TrackRef>): List<TrackRef> =
        fetchTracks(exclude.toList()).map { TrackRef.yandex(it.ref.id) }

    /**
     * [fetchBatch] with the tracks themselves (the queue stores them); [queued] is the queue in play order, its
     * last entry is where the station continues from.
     */
    suspend fun fetchTracks(queued: List<TrackRef>): List<Track> = fetching.withLock {
        val batch = fetchBoth(queued.lastOrNull()?.id).filter(keep)
        RotorStations.freshOf(batch, queued.map { it.id }.toSet(), heardLately).map { it.toTrack() }
    }

    override suspend fun feedback(ref: TrackRef, event: String, playedMs: Long) {
        val seconds = if (playedMs > 0L) playedMs / MS_PER_SECOND else null
        api.sendStationFeedback(station, event, ref.id, from = RotorStations.feedbackFrom(station), totalPlayedSeconds = seconds)
    }

    private suspend fun fetchBoth(lastQueued: String?): List<TrackDetailsDto> = supervisorScope {
        // One request continues from the last queued track, the other adds variety
        val continued = async { api.getStationTracks(station, queue = lastQueued).result?.sequence?.mapNotNull { it.track } }
        val fresh = async { api.getStationTracks(station).result?.sequence?.mapNotNull { it.track } }
        (continued.awaitOrEmpty() + fresh.awaitOrEmpty()).distinctBy { it.idString }
    }

    /** One of the two requests failing (network or a refusal) leaves the other. */
    private suspend fun Deferred<List<TrackDetailsDto>?>.awaitOrEmpty(): List<TrackDetailsDto> = try {
        await().orEmpty()
    } catch (e: IOException) {
        Log.w(TAG, "Radio request failed", e)
        emptyList()
    } catch (e: HttpException) {
        Log.w(TAG, "Radio request refused: ${e.code()}", e)
        emptyList()
    }

    private companion object {
        const val TAG = "RotorStationSource"
        const val MS_PER_SECOND = 1000.0
    }
}
