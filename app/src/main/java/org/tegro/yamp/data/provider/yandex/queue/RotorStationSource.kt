package org.tegro.yamp.data.provider.yandex.queue

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import org.tegro.yamp.data.dto.RotorStationTracksResponseDto
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
 * is (see [RotorStations.gather]): continued from the last queued track, then from that answer's end, then from
 * the station's start, minus what the queue holds, what was heard lately and what the listener excludes; with
 * nothing fresh, the tracks heard longest ago. One fetch at a time per source; the controller owns one source
 * for the active radio and drops it on a switch or re-seed, so a stale batch never lands.
 */
internal class RotorStationSource(
    val station: String,
    /** Whose API: read per call, so the account in force at that moment is used. */
    private val apiOf: () -> YandexMusicApi,
    /** How long ago a track was heard, in tracks (null — not lately): the radios share the wave's rule. */
    private val heardAge: (String) -> Int?,
    private val keep: (TrackDetailsDto) -> Boolean = { true }
) : DynamicSource {
    private val api: YandexMusicApi get() = apiOf()

    private val fetching = Mutex()

    /** What the station answered about each fetched track (its feedback names both); the latest few hundred stay. */
    private val fetched = object : LinkedHashMap<String, Fetched>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Fetched>?): Boolean = size > BATCHES_KEPT
    }

    private data class Fetched(val batchId: String, val albumId: String?)

    override suspend fun fetchBatch(exclude: Set<TrackRef>): List<TrackRef> =
        fetchTracks(exclude.toList()).map { TrackRef.yandex(it.ref.id) }

    /**
     * [fetchBatch] with the tracks themselves (the queue stores them); [queued] is the queue in play order, its
     * last entry is where the station continues from.
     */
    suspend fun fetchTracks(queued: List<TrackRef>): List<Track> = fetching.withLock {
        RotorStations.gather(
            lastQueued = queued.lastOrNull()?.id,
            queued = queued.map { it.id }.toSet(),
            heardAge = heardAge
        ) { queue ->
            ask(queue)?.let { answer ->
                remember(answer)
                RotorStations.Answer(answer.sequence.mapNotNull { it.track }.filter(keep), answer.batchId)
            }
        }.tracks.map { it.toTrack() }
    }

    override suspend fun feedback(ref: TrackRef, event: String, playedMs: Long) {
        val seconds = if (playedMs > 0L) playedMs / MS_PER_SECOND else null
        val known = fetchedOf(ref.id)
        val body = RotorFeedbackBody(
            station = station,
            type = event,
            atMs = System.currentTimeMillis(),
            trackId = ref.id,
            albumId = known?.albumId,
            batchId = known?.batchId,
            playedSeconds = seconds
        ).body()
        api.sendStationFeedback(station, body, known?.batchId)
    }

    /** One request to [queue] (null: from the station's start); a failure (network or a refusal) is null. */
    private suspend fun ask(queue: String?): RotorStationTracksResponseDto? = try {
        api.getStationTracks(station, queue = queue).result
    } catch (e: CancellationException) {
        throw e
    } catch (e: HttpException) {
        Log.w(TAG, "Radio request refused: ${e.code()}", e)
        null
    } catch (e: IOException) {
        Log.w(TAG, "Radio request failed", e)
        null
    } catch (e: SerializationException) {
        Log.w(TAG, "Radio request failed", e)
        null
    }

    /** Remembers the batch (and the album) of each track of [answer], so its feedback can name them. */
    @Synchronized
    private fun remember(answer: RotorStationTracksResponseDto) {
        val batch = answer.batchId ?: return
        answer.sequence.mapNotNull { it.track }.forEach { fetched[it.idString] = Fetched(batch, it.albumId) }
    }

    /** What the station said about [trackId]; the map is written from the fetching coroutine, read from the sender. */
    @Synchronized
    private fun fetchedOf(trackId: String): Fetched? = fetched[trackId]

    private companion object {
        const val TAG = "RotorStationSource"
        const val MS_PER_SECOND = 1000.0
        const val BATCHES_KEPT = 500
    }
}
