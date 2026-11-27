package org.tegro.yamp.data.provider.yandex.queue

import org.tegro.yamp.data.dto.TrackDetailsDto
import org.tegro.yamp.domain.queue.MediaRef

/** Yandex rotor station ids and the pure rules My Wave and the radios share (docs/phase-2-radio.md §4). */
object RotorStations {
    const val MY_WAVE = "user:onyourwave"

    /** At most this many requests make one fetch. */
    const val MAX_ASKS = 3

    /** Only tracks heard lately on offer: this many of them, the ones heard longest ago, so the wave goes on. */
    const val FALLBACK_TAKE = 3

    fun trackStation(trackId: String) = "track:$trackId"

    /** The station of a radio seed: `yandex:track:1` → `track:1`, the same for `album` and `artist`. */
    fun stationOf(seed: MediaRef): String = "${seed.kind}:${seed.id}"

    /** The "from" the service expects with feedback for [station]. */
    fun feedbackFrom(station: String) = "radio-" + station.replace(':', '-')

    /** One answer of a station: its tracks (the unwanted ones left out) and the batch they came in. */
    data class Answer(val tracks: List<TrackDetailsDto>, val batchId: String?)

    /** What a fetch brought: the tracks to add, the batch each offered track came in, the offered and heard counts. */
    data class Gathered(val tracks: List<TrackDetailsDto>, val batches: Map<String, String>, val offered: Int, val heard: Int)

    /** The first of [tracks] not [heard] lately; null when all were: never the first one again. Pure. */
    fun firstUnheard(tracks: List<TrackDetailsDto>, heard: (String) -> Boolean): TrackDetailsDto? =
        tracks.firstOrNull { !heard(it.idString) }

    /**
     * One fetch, apart from the network ([ask] is one request; a null queue asks from the station's start).
     * It goes on from [lastQueued]; when all it offers is queued or was heard lately, it goes on once more from
     * the end of that answer, then asks from the start. Only when every answer is made of such tracks do the ones
     * heard longest ago come, a few. ([heardAge]: how long ago a track was heard, in tracks; null — not lately.)
     * Pure; unit-tested.
     */
    suspend fun gather(
        lastQueued: String?,
        queued: Set<String>,
        heardAge: (String) -> Int?,
        ask: suspend (queue: String?) -> Answer?
    ): Gathered {
        val offered = LinkedHashMap<String, TrackDetailsDto>()
        val batches = HashMap<String, String>()
        var next = lastQueued
        var askedFromStart = false
        var fresh = emptyList<TrackDetailsDto>()
        var step = 0
        // Asking from the start twice would bring the same answer
        fun canAsk() = step < MAX_ASKS && (next != null || !askedFromStart)
        while (fresh.isEmpty() && canAsk()) {
            val queue = next
            if (queue == null) askedFromStart = true
            val answer = ask(queue)
            answer?.tracks?.forEach { track ->
                offered.putIfAbsent(track.idString, track)
                answer.batchId?.let { batches.putIfAbsent(track.idString, it) }
            }
            fresh = answer?.tracks.orEmpty().filter { it.idString !in queued && heardAge(it.idString) == null }
                .distinctBy { it.idString }
            // Once more from the end of this answer, then from the station's start
            next = if (step == 0) answer?.tracks?.lastOrNull()?.idString else null
            step++
        }
        val tracks = fresh.ifEmpty {
            offered.values.filter { it.idString !in queued }
                .sortedByDescending { heardAge(it.idString) ?: Int.MAX_VALUE }
                .take(FALLBACK_TAKE)
        }
        return Gathered(tracks, batches, offered.size, offered.keys.count { heardAge(it) != null })
    }
}
