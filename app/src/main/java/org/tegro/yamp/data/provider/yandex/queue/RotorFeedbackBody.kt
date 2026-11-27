package org.tegro.yamp.data.provider.yandex.queue

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * What a Yandex rotor station hears about its tracks: the station started, a track started, was heard to the end or
 * skipped. The rotor's feedback method reads a JSON body only: a form-encoded one is answered "condition is not met",
 * exactly like an empty body, so the station never learned what was heard and offered the same tracks again. Pure;
 * unit-tested.
 *
 * Not to be confused with [RotorFeedbackLine], the journal line of the same send.
 */
internal data class RotorFeedbackBody(
    val station: String,
    val type: String,
    val atMs: Long,
    /** The provider's id of the track; null for the start of the station. */
    val trackId: String? = null,
    val albumId: String? = null,
    /** The answer of the station the track came in; it goes with the request, not in the body. */
    val batchId: String? = null,
    val playedSeconds: Double? = null
) {
    fun body(): JsonObject = buildJsonObject {
        put("type", type)
        put("timestamp", timestamp(atMs))
        put("from", RotorStations.feedbackFrom(station))
        // A track is named with its album, as the service names it in its own lists
        trackId?.let { put("trackId", if (albumId.isNullOrEmpty()) it else "$it:$albumId") }
        playedSeconds?.let { put("totalPlayedSeconds", it) }
    }

    companion object {
        const val RADIO_STARTED = "radioStarted"
        const val TRACK_STARTED = "trackStarted"
        const val TRACK_FINISHED = "trackFinished"
        const val SKIP = "skip"

        /** "2026-11-13T09:43:15.000Z": the form the service asks for in its error answers. */
        fun timestamp(ms: Long): String =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

        /**
         * What one event sends, in order: the start of the station first, then the track itself. [includeStart] is
         * true only for the first track of a start of the station (see `WaveQueue.takeStart`). Pure; unit-tested.
         */
        fun plan(
            station: String,
            type: String,
            trackId: String?,
            albumId: String?,
            batchId: String?,
            atMs: Long,
            includeStart: Boolean = false,
            playedSeconds: Double? = null
        ): List<RotorFeedbackBody> {
            val start = if (includeStart) listOf(RotorFeedbackBody(station, RADIO_STARTED, atMs, batchId = batchId)) else emptyList()
            return start + RotorFeedbackBody(station, type, atMs, trackId, albumId, batchId, playedSeconds)
        }
    }
}
