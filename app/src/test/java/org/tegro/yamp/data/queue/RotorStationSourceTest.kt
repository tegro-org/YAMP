package org.tegro.yamp.data.queue

import android.app.Application
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import org.tegro.yamp.data.provider.yandex.network.YandexMusicApi
import org.tegro.yamp.domain.queue.MediaRef
import org.tegro.yamp.domain.queue.TrackRef
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import org.tegro.yamp.data.provider.yandex.queue.RotorStations
import org.tegro.yamp.data.provider.yandex.queue.RotorStationSource

/** A radio's source: the station asked on until something fresh comes, the queue and the recent list honoured. */
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
    fun theStationGoesOnFromTheLastQueuedTrackAndStopsAtTheFirstFreshAnswer() = runBlocking {
        answers["3"] = listOf("4", "5")
        answers[null] = listOf("6")
        val source = RotorStationSource("track:1", { api }, heardAge = { null })
        val batch = source.fetchTracks(refs("1", "2", "3"))
        assertEquals(listOf("4", "5"), batch.map { it.ref.id })
        assertEquals("one answer was enough", 1, requests.size)
        assertTrue(requests.all { it.url.encodedPath == "/rotor/station/track:1/tracks" })
        assertEquals("3", requests.single().url.queryParameter("queue"))
    }

    @Test
    fun anAnswerOfHeardTracksIsFollowedFromItsEndAndThenFromTheStart() = runBlocking {
        answers["2"] = listOf("3")
        answers["3"] = listOf("4")
        answers[null] = listOf("5")
        val source = RotorStationSource("album:9", { api }, heardAge = { if (it == "3") 0 else null })
        assertEquals(listOf("4"), source.fetchTracks(refs("1", "2")).map { it.ref.id })
        assertEquals(listOf("2", "3"), requests.map { it.url.queryParameter("queue") })
    }

    @Test
    fun allHeardOnOfferBringsTheThreeHeardLongestAgo() = runBlocking {
        // The age grows with the time since it was heard: the fallback takes the largest ones
        val age = mapOf("4" to 1, "5" to 2, "6" to 3, "7" to 4)
        answers["3"] = listOf("4", "5")
        answers["5"] = listOf("6")
        answers[null] = listOf("7")
        val source = RotorStationSource("track:1", { api }, heardAge = { age[it] })
        assertEquals(listOf("7", "6", "5"), source.fetchTracks(refs("1", "2", "3")).map { it.ref.id })
        assertEquals("at most three requests", 3, requests.size)
    }

    @Test
    fun whatIsQueuedOrHeardLatelyOrExplicitIsLeftOut() = runBlocking {
        answers["2"] = listOf("2", "3", "4")
        answers[null] = listOf("5")
        val source = RotorStationSource("album:9", { api }, heardAge = { if (it == "3") 0 else null }, keep = { it.idString != "4" })
        assertEquals(listOf("5"), source.fetchTracks(refs("1", "2")).map { it.ref.id })
        answers["2"] = listOf("7", "8")
        answers[null] = listOf("7")
        val allHeard = RotorStationSource("album:9", { api }, heardAge = { 0 })
        assertEquals("all heard lately: still the ones not queued", listOf("7", "8"), allHeard.fetchTracks(refs("1", "2")).map { it.ref.id })
    }

    @Test
    fun oneRequestFailingLeavesTheOther() = runBlocking {
        answers[null] = listOf("4")
        val source = RotorStationSource("track:1", { api }, heardAge = { null })
        assertEquals(listOf("4"), source.fetchTracks(refs("1")).map { it.ref.id })
        answers.clear()
        assertEquals("both failing: an empty batch, no exception", emptyList<TrackRef>(), source.fetchBatch(setOf(TrackRef.yandex("9"))))
    }

    @Test
    fun feedbackGoesToTheStationAsAJsonBodyWithItsBatch() = runBlocking {
        answers[null] = listOf("7")
        val source = RotorStationSource("track:42", { api }, heardAge = { null })
        source.fetchTracks(refs("1"))
        requests.clear()
        runCatching { source.feedback(TrackRef.yandex("7"), "trackFinished", playedMs = 90_500L) }
        val request = requests.single()
        assertEquals("/rotor/station/track:42/feedback", request.url.encodedPath)
        assertEquals("the batch the track came in", "b", request.url.queryParameter("batch-id"))
        val json = Json.parseToJsonElement(okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
        assertEquals("trackFinished", json["type"]!!.jsonPrimitive.content)
        assertEquals("7", json["trackId"]!!.jsonPrimitive.content)
        assertEquals("radio-track-42", json["from"]!!.jsonPrimitive.content)
        assertEquals(90.5, json["totalPlayedSeconds"]!!.jsonPrimitive.double, 0.001)
    }
}
