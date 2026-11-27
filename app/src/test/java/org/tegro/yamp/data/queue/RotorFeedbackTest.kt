package org.tegro.yamp.data.queue

import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tegro.yamp.data.provider.yandex.queue.RotorFeedbackBody
import org.tegro.yamp.data.provider.yandex.queue.RotorFeedbackBody.Companion.RADIO_STARTED
import org.tegro.yamp.data.provider.yandex.queue.RotorFeedbackBody.Companion.SKIP
import org.tegro.yamp.data.provider.yandex.queue.RotorFeedbackBody.Companion.TRACK_FINISHED
import org.tegro.yamp.data.provider.yandex.queue.RotorFeedbackBody.Companion.TRACK_STARTED

/**
 * What a station hears (the rotor reads a JSON body only): the type, the ISO-8601 UTC timestamp, the "from", the track
 * as id:albumId, the batch as a request parameter — and the start of the station before its first track.
 */
class RotorFeedbackTest {
    private fun RotorFeedbackBody.text(key: String): String? = body()[key]?.jsonPrimitive?.content

    @Test
    fun theBodyNamesTheTypeTheTimestampTheStationTheTrackAndItsAlbum() {
        val body = RotorFeedbackBody(station = "track:42", type = TRACK_STARTED, atMs = 0L, trackId = "7", albumId = "8")
        assertEquals(TRACK_STARTED, body.text("type"))
        assertEquals("1970-01-01T00:00:00.000Z", body.text("timestamp"))
        assertEquals("radio-track-42", body.text("from"))
        assertEquals("7:8", body.text("trackId"))
        assertFalse("a start carries no played time", body.body().containsKey("totalPlayedSeconds"))
    }

    @Test
    fun aTrackWithoutAnAlbumKeepsThePlainIdAndOnlyEndsCarryThePlayedTime() {
        val heard = RotorFeedbackBody("user:onyourwave", TRACK_FINISHED, 0L, trackId = "7", playedSeconds = 180.0)
        assertEquals("7", heard.text("trackId"))
        assertEquals(180.0, heard.body()["totalPlayedSeconds"]!!.jsonPrimitive.content.toDouble(), 0.001)
        assertEquals("radio-user-onyourwave", heard.text("from"))
        val skipped = RotorFeedbackBody("user:onyourwave", SKIP, 0L, trackId = "7", playedSeconds = 42.5)
        assertEquals(42.5, skipped.body()["totalPlayedSeconds"]!!.jsonPrimitive.content.toDouble(), 0.001)
    }

    @Test
    fun theStartOfTheStationGoesBeforeItsFirstTrack() {
        val sent = Channel<RotorFeedbackBody>(Channel.UNLIMITED)
        // The first track of a start of the station, then the next one
        RotorFeedbackBody.plan("track:42", TRACK_STARTED, "7", "8", "b1", 0L, includeStart = true).forEach { sent.trySend(it) }
        RotorFeedbackBody.plan("track:42", TRACK_STARTED, "9", null, "b2", 0L, includeStart = false).forEach { sent.trySend(it) }
        sent.close()

        val drained = generateSequence { sent.tryReceive().getOrNull() }.toList()
        assertEquals(listOf(RADIO_STARTED, TRACK_STARTED, TRACK_STARTED), drained.map { it.type })
        assertEquals("the batch the track came in", listOf("b1", "b1", "b2"), drained.map { it.batchId })
        assertFalse("the start of the station names no track", drained.first().body().containsKey("trackId"))
        assertFalse("the batch goes with the request, not in the body", drained.first().body().containsKey("batch-id"))
        assertTrue("two events: the start and the track", drained.first().body().isNotEmpty())
    }
}
