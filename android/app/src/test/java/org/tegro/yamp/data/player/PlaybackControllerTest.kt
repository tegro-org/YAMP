package org.tegro.yamp.data.player

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.tegro.yamp.data.cache.PlaybackStateStore
import org.tegro.yamp.data.cache.PlaylistCacheManager
import org.tegro.yamp.data.diagnostics.DiagnosticLog
import org.tegro.yamp.data.dto.ArtistDto
import org.tegro.yamp.data.dto.TrackDetailsDto
import org.tegro.yamp.data.network.NetworkMonitor
import org.tegro.yamp.data.network.TrackDownloadManager
import org.tegro.yamp.data.network.YandexMusicApi
import org.tegro.yamp.data.player.fakes.FakeAuthRepository
import org.tegro.yamp.data.player.fakes.FakePlayerRepository
import org.tegro.yamp.data.player.fakes.FakePlayerRepository.Call
import org.tegro.yamp.data.player.fakes.FakeSettingsRepository
import org.tegro.yamp.data.player.fakes.FakeTrackUrlResolver
import org.tegro.yamp.data.repository.RadioRepository
import org.tegro.yamp.data.reserve.RoadReserve
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * The controller with a fake engine and a fake resolver: what it asks the engine to do for a play, a skip,
 * a failure, a queue edit and a restored session. These pin TODAY'S queue handling (one list, the current
 * track found by id) before Phase 1 restructures it (docs/phase-1-queues.md §6, ARCHITECTURE E6).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PlaybackControllerTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }

    /** The service is never reached: every request is refused, which the wave and the feedback survive. */
    private val client = OkHttpClient.Builder().addInterceptor(
        Interceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(404)
                .message("")
                .body("""{"error":{"name":"not-found","message":""}}""".toResponseBody("application/json".toMediaType()))
                .build()
        }
    ).build()

    private val api = Retrofit.Builder()
        .baseUrl("https://api.music.yandex.net/")
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(YandexMusicApi::class.java)

    private val auth = FakeAuthRepository()
    private val settings = FakeSettingsRepository()
    private val engine = FakePlayerRepository(context)
    private val resolver = FakeTrackUrlResolver()
    private val store = PlaybackStateStore(context, json)

    @Before
    fun setUp() {
        // The controller's scope is Dispatchers.Main.immediate; unconfined, so every call runs to its end at once
        Dispatchers.setMain(UnconfinedTestDispatcher())
        // Robolectric's default network carries no capabilities: without this the controller believes it is offline
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val internet = ShadowNetworkCapabilities.newInstance()
        // addCapability is hidden from the SDK stubs the tests compile against, but present in Robolectric's framework
        NetworkCapabilities::class.java.getMethod("addCapability", Int::class.javaPrimitiveType)
            .invoke(internet, NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(connectivity).setNetworkCapabilities(connectivity.activeNetwork, internet)
    }

    private val controllers = mutableListOf<PlaybackController>()

    @After
    fun tearDown() {
        // Nothing may still touch Dispatchers.Main while it is reset
        await { controllers.forEach { it.shutdown() } }
        Dispatchers.resetMain()
    }

    /** A controller on the real stores of this test's app, with its saved session (if any) already loaded. */
    private fun controller(engine: FakePlayerRepository = this.engine): PlaybackController {
        val controller = PlaybackController(
            context, api, auth, engine,
            TrackDownloadManager(context, client), resolver, PlaylistCacheManager(context, json), RadioRepository(context),
            store, settings, NetworkMonitor(context), RoadReserve(context, client, json),
            PlaybackJournal(now = { 0L }), DiagnosticLog(context, settings)
        )
        controllers += controller
        await { controller.awaitRestoredSession() }
        return controller
    }

    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(5_000) { block() } }

    private fun track(id: String) = TrackDetailsDto(
        id = JsonPrimitive(id),
        title = "Track $id",
        artists = listOf(ArtistDto(id = JsonPrimitive("a$id"), name = "Artist $id")),
        durationMs = 200_000
    )

    /** Tracks by id, each with a stream link the resolver knows. */
    private fun playlist(vararg ids: String): List<TrackDetailsDto> {
        ids.forEach { resolver.urls[it] = resolver.url(it) }
        return ids.map(::track)
    }

    /** Starts [id] from a library queue, lets it sound and forgets the engine calls so far. */
    private fun PlaybackController.playing(id: String, queue: List<TrackDetailsDto>) {
        playTrack(id, queue, PlaylistSource.LIBRARY)
        engine.sounds()
        engine.calls.clear()
    }

    private val List<TrackDetailsDto>.ids get() = map { it.idString }

    @Test
    fun playingATrackStreamsItsLinkAndWarmsUpWhatFollows() {
        val controller = controller()
        val queue = playlist("1", "2", "3", "4", "5")

        controller.playTrack("1", queue, PlaylistSource.LIBRARY)

        assertEquals(listOf(Call("playTrack", "1", resolver.url("1"), 0L)), engine.played)
        assertEquals("1", controller.currentTrack.value?.idString)
        assertEquals(queue, controller.currentPlaylist.value)
        assertEquals(PlaylistSource.LIBRARY, controller.currentSource.value)
        assertEquals("the next three links are warmed", listOf(listOf("2", "3", "4")), resolver.preloaded)
        assertEquals("the next track stands by in the engine", listOf("2"), engine.prepared)
        assertTrue("starting until the sound renders", controller.isStarting.value)
        engine.sounds()
        assertFalse(controller.isStarting.value)
        assertTrue(controller.isPlaying.value)
    }

    @Test
    fun nextFadesTheOldTrackOutAndPreviousGoesBackOnlyWithinTheFirstThreeSeconds() {
        val controller = controller()
        controller.playing("1", playlist("1", "2", "3"))

        controller.playNext()

        assertEquals("fadeOutForSkip", engine.names.first())
        assertEquals(listOf("2"), engine.played.map { it.trackId })

        engine.progress.value = PlaybackQueue.RESTART_ON_PREV_THRESHOLD_MS - 1
        engine.calls.clear()
        controller.playPrev()
        assertEquals("fadeOutForSkip", engine.names.first())
        assertEquals(listOf("1"), engine.played.map { it.trackId })

        engine.progress.value = 5_000
        engine.calls.clear()
        controller.playPrev()
        assertEquals(listOf(Call("seekTo", positionMs = 0L)), engine.calls)
        assertEquals("1", controller.currentTrack.value?.idString)
    }

    @Test
    fun theEndOfALibraryQueueLoopsToItsStartWithoutAFade() {
        val controller = controller()
        controller.playing("3", playlist("1", "2", "3"))

        controller.playNext(isAuto = true)

        assertEquals(listOf("1"), engine.played.map { it.trackId })
        assertFalse("an automatic transition keeps the crossfade", "fadeOutForSkip" in engine.names)
        assertEquals("1", controller.currentTrack.value?.idString)
    }

    @Test
    fun aSavedSessionComesBackPausedAtItsPosition() {
        val first = controller()
        first.playing("2", playlist("1", "2", "3"))
        // playTrack saves the session by itself; wait for that write before the one with the position
        await { while (store.load()?.currentTrackId != "2") delay(20) }
        engine.progress.value = 42_000
        first.persistNow()
        await { while (store.load()?.positionMs != 42_000L) delay(20) }

        val engineAfterRestart = FakePlayerRepository(context)
        val second = controller(engineAfterRestart)

        assertEquals("2", second.currentTrack.value?.idString)
        assertEquals(listOf("1", "2", "3"), second.currentPlaylist.value.ids)
        assertEquals(PlaylistSource.LIBRARY, second.currentSource.value)
        // The restore ran on an IO thread; the derived progress flow settles a moment after the session is back
        assertEquals(42_000L, await { second.progress.first { it == 42_000L } })
        assertTrue("restored paused: the engine is not started", engineAfterRestart.played.isEmpty())
        assertFalse(second.isPlaying.value)
    }

    @Test
    fun pausingWhileTheLinkIsStillBeingResolvedCancelsTheStart() {
        val controller = controller()
        val queue = playlist("1", "2")
        resolver.pending = CompletableDeferred()

        controller.playTrack("1", queue, PlaylistSource.LIBRARY)
        assertTrue(controller.isStarting.value)
        assertTrue("no link yet, nothing to play", engine.played.isEmpty())

        controller.pause()
        assertFalse(controller.isStarting.value)
        assertTrue("pause" in engine.names)

        resolver.pending!!.complete(Unit)
        assertTrue("the link arriving after the pause starts nothing", engine.played.isEmpty())
        assertFalse(controller.isPlaying.value)
        assertEquals("the track stays on screen", "1", controller.currentTrack.value?.idString)
    }

    @Test
    fun aFailedStreamIsRetriedOnceWithAFreshLinkThenSkipped() {
        val controller = controller()
        controller.playTrack("1", playlist("1", "2", "3"), PlaylistSource.LIBRARY)
        engine.calls.clear()

        engine.fail("1", positionMs = 30_000)

        assertEquals("the old link is forgotten", listOf("1"), resolver.evicted)
        assertEquals("the same track again, from where it broke", listOf(Call("playTrack", "1", resolver.url("1"), 30_000L)), engine.played)

        engine.calls.clear()
        engine.fail("1", positionMs = 30_000)

        // recoveryFor: already retried, one failed track in a row, no copy, good link → SKIP
        assertEquals(listOf("2"), engine.played.map { it.trackId })
        assertEquals("2", controller.currentTrack.value?.idString)
    }

    @Test
    fun removingTheNextTrackPreparesTheOneAfterIt() {
        val controller = controller()
        controller.playing("1", playlist("1", "2", "3", "4"))

        controller.removeFromQueue("2")

        assertEquals(listOf("1", "3", "4"), controller.currentPlaylist.value.ids)
        assertEquals(listOf("3"), engine.prepared)
        assertEquals("1", controller.currentTrack.value?.idString)
    }

    @Test
    fun enqueueNextPutsTheTrackRightAfterTheCurrentOne() {
        val controller = controller()
        controller.playing("1", playlist("1", "2", "3"))
        resolver.urls["9"] = resolver.url("9")

        controller.enqueueNext(track("9"))

        assertEquals(listOf("1", "9", "2", "3"), controller.currentPlaylist.value.ids)
        // Today enqueueNext changes the list only: the engine keeps "2" standing by until the track ends
        assertEquals(emptyList<Call>(), engine.calls)
    }

    @Test
    fun movingATrackToTheNextSlotPreparesIt() {
        val controller = controller()
        controller.playing("1", playlist("1", "2", "3", "4"))

        controller.moveInQueue("4", "2")

        assertEquals(listOf("1", "4", "2", "3"), controller.currentPlaylist.value.ids)
        assertEquals(listOf("4"), engine.prepared)

        engine.calls.clear()
        controller.moveInQueue("3", "1")
        assertEquals("the current track never moves", listOf("1", "4", "2", "3"), controller.currentPlaylist.value.ids)
        assertEquals(emptyList<Call>(), engine.calls)
    }

    @Test
    fun nextAfterADuplicateFollowsItsFirstOccurrence_todayIndexesByTrackId() {
        val controller = controller()
        // "a" twice: the second one is reached by "next" from "b"
        controller.playing("b", playlist("a", "b", "a", "c"))

        controller.playNext()
        assertEquals("a", controller.currentTrack.value?.idString)
        // The queue finds the current track by id: the warm-up already counts from the first "a"
        assertEquals(listOf("b", "a", "c"), resolver.preloaded.last())
        engine.calls.clear()

        controller.playNext()

        // ...so "next" after the second "a" is what follows the first, not "c"
        assertEquals(listOf("b"), engine.played.map { it.trackId })
    }
}
