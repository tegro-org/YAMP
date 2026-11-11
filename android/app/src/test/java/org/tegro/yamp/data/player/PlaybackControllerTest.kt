package org.tegro.yamp.data.player

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.tegro.yamp.R
import org.tegro.yamp.data.cache.CacheDatabase
import org.tegro.yamp.data.cache.PlaybackSnapshot
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
import org.tegro.yamp.data.queue.LegacySessionImport
import org.tegro.yamp.data.queue.QueueManager
import org.tegro.yamp.data.queue.QueueTrackBridge
import org.tegro.yamp.data.queue.RoomQueueStore
import org.tegro.yamp.data.repository.RadioRepository
import org.tegro.yamp.data.reserve.RoadReserve
import org.tegro.yamp.di.DispatcherProvider
import org.tegro.yamp.domain.queue.RepeatMode
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * The controller with a fake engine and a fake resolver over the real queue manager and Room: what it asks
 * the engine to do for a play, a skip, a failure, a queue edit, a switch and a restored session. The queue
 * operations run one after another on the controller's scope, so every test waits for them ([settle]).
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
    /**
     * The queues' database, in memory and on the calling thread: a queue operation then runs to its end
     * before the call returns (the controller's scope is unconfined here), as every assertion below expects.
     */
    private val db = Room.inMemoryDatabaseBuilder(context, CacheDatabase::class.java)
        .allowMainThreadQueries()
        .setQueryExecutor { it.run() }
        .setTransactionExecutor { it.run() }
        .build()
    private val cacheManager = PlaylistCacheManager(context, json)

    // The controller's scope is Dispatchers.Main.immediate; unconfined, so every call runs to its end at once
    private val dispatcher = UnconfinedTestDispatcher()
    private val dispatchers = object : DispatcherProvider {
        override val main get() = dispatcher
        override val io get() = dispatcher
        override val default get() = dispatcher
    }
    private val managerScopes = mutableListOf<CoroutineScope>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
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
        managerScopes.forEach { it.cancel() }
        db.close()
        Dispatchers.resetMain()
    }

    /** A queue manager over this test's database, as the controller and the tests see the queues. */
    private fun manager(): QueueManager {
        val scope = CoroutineScope(SupervisorJob() + dispatcher).also { managerScopes += it }
        return QueueManager(RoomQueueStore(db.queueDao(), db.dao()), QueueTrackBridge(db.dao(), json), scope, dispatchers)
    }

    /** A controller on the real stores of this test's app, with its saved session (if any) already loaded. */
    private fun controller(engine: FakePlayerRepository = this.engine, queueManager: QueueManager = manager()): PlaybackController {
        val legacyImport = LegacySessionImport(store, cacheManager, RoomQueueStore(db.queueDao(), db.dao()), QueueTrackBridge(db.dao(), json))
        val controller = PlaybackController(
            context, api, auth, engine,
            TrackDownloadManager(context, client), resolver, cacheManager, RadioRepository(context),
            queueManager, legacyImport, db.dao(),
            settings, NetworkMonitor(context), RoadReserve(context, client, json),
            PlaybackJournal(now = { 0L }), DiagnosticLog(context, settings)
        )
        controllers += controller
        await { controller.awaitRestoredSession() }
        return controller
    }

    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(5_000) { block() } }

    /** The queue operations asked for so far have run: the engine calls and the derived flows are settled. */
    private fun PlaybackController.settle() = await { awaitQueueIdle() }

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
        settle()
        engine.sounds()
        engine.calls.clear()
    }

    private val List<TrackDetailsDto>.ids get() = map { it.idString }

    @Test
    fun playingATrackStreamsItsLinkAndWarmsUpWhatFollows() {
        val controller = controller()
        val queue = playlist("1", "2", "3", "4", "5")

        controller.playTrack("1", queue, PlaylistSource.LIBRARY)
        controller.settle()

        assertEquals(listOf(Call("playTrack", "1", resolver.url("1"), 0L)), engine.played)
        assertEquals("1", controller.currentTrack.value?.idString)
        assertEquals(queue.ids, controller.currentPlaylist.value.ids)
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
        controller.settle()

        assertEquals("fadeOutForSkip", engine.names.first())
        assertEquals(listOf("2"), engine.played.map { it.trackId })

        engine.progress.value = PlaybackQueue.RESTART_ON_PREV_THRESHOLD_MS - 1
        engine.calls.clear()
        controller.playPrev()
        controller.settle()
        assertEquals("fadeOutForSkip", engine.names.first())
        assertEquals(listOf("1"), engine.played.map { it.trackId })

        engine.progress.value = 5_000
        engine.calls.clear()
        controller.playPrev()
        controller.settle()
        assertEquals(listOf(Call("seekTo", positionMs = 0L)), engine.calls)
        assertEquals("1", controller.currentTrack.value?.idString)
    }

    @Test
    fun theEndOfALibraryQueueLoopsToItsStartWithoutAFade() {
        val controller = controller()
        controller.playing("3", playlist("1", "2", "3"))

        controller.playNext(isAuto = true)
        controller.settle()

        assertEquals(listOf("1"), engine.played.map { it.trackId })
        assertFalse("an automatic transition keeps the crossfade", "fadeOutForSkip" in engine.names)
        assertEquals("1", controller.currentTrack.value?.idString)
    }

    @Test
    fun withRepeatOffTheQueueEndsAtItsLastTrack() {
        val controller = controller()
        controller.playing("2", playlist("1", "2"))
        controller.setRepeat(RepeatMode.OFF)
        controller.settle()

        controller.playNext(isAuto = true)
        controller.settle()

        assertTrue("nothing else starts", engine.played.isEmpty())
        assertTrue("the engine is stopped", "stop" in engine.names)
        assertEquals("the last track stays on screen", "2", controller.currentTrack.value?.idString)
        assertFalse(controller.isPlaying.value)
    }

    @Test
    fun aSavedSessionComesBackPausedAtItsPosition() {
        val first = controller()
        first.playing("2", playlist("1", "2", "3"))
        engine.progress.value = 42_000
        first.persistNow()
        first.settle()

        val engineAfterRestart = FakePlayerRepository(context)
        val second = controller(engineAfterRestart)

        // The restore ran on an IO thread; the mirrored flows settle a moment after the session is back
        assertEquals("2", await { second.currentTrack.first { it != null } }?.idString)
        assertEquals(listOf("1", "2", "3"), second.currentPlaylist.value.ids)
        assertEquals(PlaylistSource.LIBRARY, second.currentSource.value)
        assertEquals(42_000L, await { second.progress.first { it == 42_000L } })
        assertTrue("restored paused: the engine is not started", engineAfterRestart.played.isEmpty())
        assertFalse(second.isPlaying.value)
        assertNull("the session lives in the queue table, not in the json file", await { store.load() })
    }

    @Test
    fun theOldSessionFileBecomesTheMainQueueOnFirstStart() {
        val queue = playlist("1", "2", "3")
        await { store.save(PlaybackSnapshot(userFolder = "driver", source = "LIBRARY", queue = queue, currentTrackId = "2", positionMs = 7_000L)) }

        val controller = controller()

        assertEquals("2", await { controller.currentTrack.first { it != null } }?.idString)
        assertEquals(context.getString(R.string.queue_main_name), controller.activeQueue.value?.queue?.name)
        assertEquals(listOf("1", "2", "3"), controller.currentPlaylist.value.ids)
        assertEquals(PlaylistSource.LIBRARY, controller.currentSource.value)
        assertEquals(7_000L, await { controller.progress.first { it == 7_000L } })
        assertTrue("shown paused", engine.played.isEmpty())
        assertNull("imported once: the file is gone", await { store.load() })
    }

    @Test
    fun pausingWhileTheLinkIsStillBeingResolvedCancelsTheStart() {
        val controller = controller()
        val queue = playlist("1", "2")
        resolver.pending = CompletableDeferred()

        controller.playTrack("1", queue, PlaylistSource.LIBRARY)
        controller.settle()
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
        controller.settle()
        engine.calls.clear()

        engine.fail("1", positionMs = 30_000)

        assertEquals("the old link is forgotten", listOf("1"), resolver.evicted)
        assertEquals("the same track again, from where it broke", listOf(Call("playTrack", "1", resolver.url("1"), 30_000L)), engine.played)

        engine.calls.clear()
        engine.fail("1", positionMs = 30_000)
        controller.settle()

        // recoveryFor: already retried, one failed track in a row, no copy, good link → SKIP
        assertEquals(listOf("2"), engine.played.map { it.trackId })
        assertEquals("2", controller.currentTrack.value?.idString)
    }

    @Test
    fun removingTheNextTrackPreparesTheOneAfterIt() {
        val controller = controller()
        controller.playing("1", playlist("1", "2", "3", "4"))

        controller.removeFromQueue("2")
        controller.settle()

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
        controller.settle()

        assertEquals(listOf("1", "9", "2", "3"), controller.currentPlaylist.value.ids)
        // enqueueNext changes the queue only: the engine keeps "2" standing by until the track ends
        assertEquals(emptyList<Call>(), engine.calls)
    }

    @Test
    fun movingATrackToTheNextSlotPreparesIt() {
        val controller = controller()
        controller.playing("1", playlist("1", "2", "3", "4"))

        controller.moveInQueue("4", "2")
        controller.settle()

        assertEquals(listOf("1", "4", "2", "3"), controller.currentPlaylist.value.ids)
        assertEquals(listOf("4"), engine.prepared)

        engine.calls.clear()
        controller.moveInQueue("3", "1")
        controller.settle()
        assertEquals("the current track never moves", listOf("1", "4", "2", "3"), controller.currentPlaylist.value.ids)
        assertEquals(emptyList<Call>(), engine.calls)
    }

    @Test
    fun nextAfterADuplicateFollowsThatEntryNotTheFirstOccurrence() {
        val controller = controller()
        // "a" twice: the second one is played by its entry, as the queue sheet will
        controller.playing("b", playlist("a", "b", "a", "c"))

        controller.playEntry(controller.activeQueue.value!!.entries[2].entryId)
        controller.settle()
        assertEquals(listOf("a"), engine.played.map { it.trackId })
        assertEquals("the cursor is that entry", 2, controller.activeQueue.value?.cursorIndex)
        assertEquals("the warm-up counts from that entry", listOf("c"), resolver.preloaded.last())
        engine.calls.clear()

        controller.playNext()
        controller.settle()

        assertEquals("next after the second \"a\" is \"c\"", listOf("c"), engine.played.map { it.trackId })
    }

    @Test
    fun switchingQueuesShowsTheTargetAtItsCursorAndKeepsPlayingIfItWas() {
        val manager = manager()
        val controller = controller(queueManager = manager)
        controller.playing("2", playlist("1", "2", "3"))
        val mainId = controller.activeQueue.value!!.queue.id
        engine.progress.value = 10_000
        val trip = await { manager.create("Поездка") }
        controller.addToQueue(trip.id, playlist("7", "8"))
        controller.settle()

        controller.switchQueue(trip.id)
        controller.settle()

        assertEquals("playing: the target starts at once", listOf("7"), engine.played.map { it.trackId })
        assertEquals(listOf("7", "8"), controller.currentPlaylist.value.ids)
        assertEquals("the position of the queue left is saved", 10_000L, await { db.queueDao().queue(mainId)!!.positionMs })
        engine.sounds()
        controller.pause()
        controller.settle()
        engine.calls.clear()

        controller.switchQueue(mainId)
        controller.settle()

        assertEquals("2", controller.currentTrack.value?.idString)
        assertEquals(listOf("1", "2", "3"), controller.currentPlaylist.value.ids)
        assertEquals(10_000L, await { controller.progress.first { it == 10_000L } })
        assertTrue("paused: shown at the cursor, not started", engine.played.isEmpty())
        assertFalse(controller.isPlaying.value)
    }

    @Test
    fun shuffleKeepsTheCurrentTrackAndCanBeUndone() {
        val controller = controller()
        val queue = playlist("1", "2", "3", "4", "5", "6")
        controller.playing("3", queue)

        controller.setShuffle(true)
        controller.settle()

        val shuffled = controller.activeQueue.value!!
        assertTrue(shuffled.queue.shuffle)
        assertEquals("3", controller.currentTrack.value?.idString)
        assertEquals("the current track leads the new order", 0, shuffled.cursorIndex)
        assertEquals(queue.ids.toSet(), controller.currentPlaylist.value.ids.toSet())

        controller.setShuffle(false)
        controller.settle()

        assertEquals(queue.ids, controller.currentPlaylist.value.ids)
        assertEquals("3", controller.currentTrack.value?.idString)
        assertEquals(2, controller.activeQueue.value?.cursorIndex)
    }
}
