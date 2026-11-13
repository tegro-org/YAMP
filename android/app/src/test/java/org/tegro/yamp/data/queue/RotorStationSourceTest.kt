package org.tegro.yamp.data.queue

import android.app.Application
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.tegro.yamp.data.network.YandexMusicApi
import org.tegro.yamp.domain.queue.MediaRef
import org.tegro.yamp.domain.queue.TrackRef
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** A radio's source: two rotor requests merged and de-duplicated, the queue and the recent list honoured. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RotorStationSourceTest {
    private val requests = mutableListOf<Request>()
    /** The station's answer per `queue=` parameter (null = the fresh request); a missing key is a 500. */
    private val answers = mutableMapOf<String?, List<String>>()

    private val client = OkHttpClient.Builder().addInterceptor(
        Interceptor { chain ->
            val request = chain.request()
            requests += request
            val ids = answers[request.url.queryParameter("queue")]
            val body = if (ids == null) """{"error":{"name":"boom","message":""}}""" else
                """{"result":{"sequence":[${ids.joinToString(",") { track(it) }}],"batchId":"b"}}"""
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (ids == null) 500 else 200).message("")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }
    ).build()

    private val api = Retrofit.Builder()
        .baseUrl("https://api.music.yandex.net/")
        .client(client)
        .addConverterFactory(Json { ignoreUnknownKeys = true }.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(YandexMusicApi::class.java)

    private fun track(id: String, explicit: Boolean = false) =
        """{"track":{"id":"$id","title":"T$id"${if (explicit) ""","contentWarning":"explicit"""" else ""}}}"""

    private fun refs(vararg ids: String) = ids.map { TrackRef.yandex(it) }

    @Test
    fun theStationComesFromTheSeed() {
        assertEquals("track:1", RotorStations.stationOf(MediaRef.parse("yandex:track:1")!!))
        assertEquals("album:2", RotorStations.stationOf(MediaRef.parse("yandex:album:2")!!))
        assertEquals("artist:3", RotorStations.stationOf(MediaRef.parse("yandex:artist:3")!!))
        assertEquals("radio-artist-3", RotorStations.feedbackFrom("artist:3"))
    }

    @Test
    fun twoRequestsAreMergedWithoutDuplicatesAndContinueFromTheLastQueuedTrack() = runBlocking {
        answers["3"] = listOf("4", "5")
        answers[null] = listOf("5", "6")
        val source = RotorStationSource("track:1", api, heardLately = { false })
        val batch = source.fetchTracks(refs("1", "2", "3"))
        assertEquals(listOf("4", "5", "6"), batch.map { it.idString })
        assertEquals(2, requests.size)
        assertTrue(requests.all { it.url.encodedPath == "/rotor/station/track:1/tracks" })
        assertEquals(setOf("3", null), requests.map { it.url.queryParameter("queue") }.toSet())
    }

    @Test
    fun whatIsQueuedOrHeardLatelyOrExplicitIsLeftOut() = runBlocking {
        answers["2"] = listOf("2", "3", "4")
        answers[null] = listOf("5")
        val source = RotorStationSource("album:9", api, heardLately = { it == "3" }, keep = { it.idString != "4" })
        assertEquals(listOf("5"), source.fetchTracks(refs("1", "2")).map { it.idString })
        answers["2"] = listOf("7", "8")
        answers[null] = listOf("7")
        val allHeard = RotorStationSource("album:9", api, heardLately = { true })
        assertEquals("all heard lately: still the ones not queued", listOf("7", "8"), allHeard.fetchTracks(refs("1", "2")).map { it.idString })
    }

    @Test
    fun oneRequestFailingLeavesTheOther() = runBlocking {
        answers[null] = listOf("4")
        val source = RotorStationSource("track:1", api, heardLately = { false })
        assertEquals(listOf("4"), source.fetchTracks(refs("1")).map { it.idString })
        answers.clear()
        assertEquals("both failing: an empty batch, no exception", emptyList<TrackRef>(), source.fetchBatch(setOf(TrackRef.yandex("9"))))
    }

    @Test
    fun feedbackGoesToTheStationWithItsFrom() = runBlocking {
        val source = RotorStationSource("track:42", api, heardLately = { false })
        runCatching { source.feedback(TrackRef.yandex("7"), "trackFinished", playedMs = 90_500L) }
        val request = requests.single()
        assertEquals("/rotor/station/track:42/feedback", request.url.encodedPath)
        val form = okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8()
        assertTrue(form, form.contains("type=trackFinished") && form.contains("trackId=7"))
        assertTrue(form, form.contains("from=radio-track-42") && form.contains("totalPlayedSeconds=90.5"))
    }
}
