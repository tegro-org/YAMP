package org.tegro.yamp.data.queue

import org.tegro.yamp.data.dto.TrackDetailsDto
import org.tegro.yamp.domain.queue.MediaRef

/** Yandex rotor station ids and the pure rules My Wave and the radios share (docs/phase-2-radio.md §4). */
object RotorStations {
    const val MY_WAVE = "user:onyourwave"

    fun trackStation(trackId: String) = "track:$trackId"

    /** The station of a radio seed: `yandex:track:1` → `track:1`, the same for `album` and `artist`. */
    fun stationOf(seed: MediaRef): String = "${seed.kind}:${seed.id}"

    /** The "from" the service expects with feedback for [station]. */
    fun feedbackFrom(station: String) = "radio-" + station.replace(':', '-')

    /**
     * Which tracks of a fetched [batch] to add: not queued yet and not heard lately; if every one was heard
     * lately, still the ones not queued. Pure; unit-tested.
     */
    fun freshOf(batch: List<TrackDetailsDto>, queued: Set<String>, heardLately: (String) -> Boolean): List<TrackDetailsDto> =
        batch.filter { it.idString !in queued && !heardLately(it.idString) }
            .ifEmpty { batch.filter { it.idString !in queued } }
}
