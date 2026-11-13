package org.tegro.yamp.data.player

import kotlinx.coroutines.CancellationException
import org.tegro.yamp.data.diagnostics.DiagnosticLog
import org.tegro.yamp.data.network.YandexMusicApi
import org.tegro.yamp.data.repository.LibraryRepository
import javax.inject.Inject

// TODO Phase 2 step 1: temporary probe, remove
/**
 * Calls the rotor for `track:`, `album:` and `artist:` stations seeded by the first liked track and writes one
 * `PROBE` line per call to the diagnostic log. Debug builds only (Settings → About).
 */
class RadioStationProbe @Inject constructor(
    private val api: YandexMusicApi,
    private val library: LibraryRepository,
    private val log: DiagnosticLog
) {
    suspend fun run() {
        library.loadCached()
        if (library.likedTracks.value.isEmpty()) runCatching { library.syncLikes() }
        if (library.likedTracks.value.isEmpty() && library.popularTracks.value.isEmpty()) runCatching { library.refreshCharts() }
        val seed = library.likedTracks.value.firstOrNull() ?: library.popularTracks.value.firstOrNull()
        if (seed == null) {
            log.event(TAG, "no liked tracks, nothing to seed")
            return
        }
        val stations = listOfNotNull(
            "track:${seed.idString}",
            seed.albumId?.let { "album:$it" },
            seed.artists.firstOrNull()?.idString?.let { "artist:$it" }
        )
        log.event(TAG, "seed track=${seed.idString} stations=$stations")
        for (station in stations + WaveQueue.MY_WAVE) {
            val first = tracks(station, queue = null) ?: continue
            val last = first.ids.lastOrNull()
            if (last != null) tracks(station, queue = last)
            val firstTrack = first.ids.firstOrNull() ?: continue
            val from = WaveQueue.feedbackFrom(station)
            val now = java.time.Instant.now().toString()
            // (a) as PlaybackController sends it today; (b) + batch-id; (c) radioStarted then trackStarted with batch-id + timestamp
            feedback("a", station) { api.sendStationFeedback(station, "trackStarted", firstTrack, from = from) }
            feedback("b", station) { api.sendStationFeedback(station, "trackStarted", firstTrack, from = from, batchId = first.batchId) }
            feedback("c1", station) { api.sendStationFeedback(station, "radioStarted", firstTrack, from = from, batchId = first.batchId, timestamp = now) }
            feedback("c2", station) { api.sendStationFeedback(station, "trackStarted", firstTrack, from = from, batchId = first.batchId, timestamp = now) }
        }
        log.event(TAG, "done")
    }

    private suspend fun feedback(variant: String, station: String, call: suspend () -> org.tegro.yamp.data.dto.ResponseWrapper<*>) {
        try {
            val r = call()
            log.event(TAG, "$station feedback[$variant] ok result=${r.result.toString().take(80)} error=${r.error}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.event(TAG, "$station feedback[$variant] FAILED ${describe(e)}")
        }
    }

    private class Batch(val ids: List<String>, val batchId: String?)

    /** Fetches one batch and logs it; returns the track ids, or null when the call failed. */
    private suspend fun tracks(station: String, queue: String?): Batch? = try {
        val r = api.getStationTracks(station, queue = queue)
        val ids = r.result?.sequence?.mapNotNull { it.track?.idString }.orEmpty()
        log.event(
            TAG,
            "$station queue=$queue ok available=${r.result?.radioIsAvailable} batchId=${r.result?.batchId} " +
                "size=${ids.size} first=${ids.take(3)} error=${r.error}"
        )
        Batch(ids, r.result?.batchId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.event(TAG, "$station queue=$queue FAILED ${describe(e)}")
        null
    }

    private fun describe(e: Exception): String = when (e) {
        is retrofit2.HttpException -> "HTTP ${e.code()} ${runCatching { e.response()?.errorBody()?.string()?.take(200) }.getOrNull()}"
        else -> "${e.javaClass.simpleName}: ${e.message}"
    }

    private companion object {
        const val TAG = "PROBE"
    }
}
