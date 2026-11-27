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
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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
import org.junit.Assert.assertNotEquals
import org.tegro.yamp.domain.repository.PlaybackFailure
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.tegro.yamp.R
import org.tegro.yamp.data.cache.CacheDatabase
import org.tegro.yamp.data.provider.yandex.cache.PlaybackSnapshot
import org.tegro.yamp.data.provider.yandex.cache.PlaybackStateStore
import org.tegro.yamp.data.provider.yandex.cache.PlaylistCacheManager
import org.tegro.yamp.data.diagnostics.DiagnosticLog
import org.tegro.yamp.data.dto.ArtistDto
import org.tegro.yamp.data.dto.TrackDetailsDto
import org.tegro.yamp.data.provider.yandex.toTrack
import org.tegro.yamp.data.provider.yandex.toYandexTrack
import org.tegro.yamp.domain.media.Track
import org.tegro.yamp.data.history.ListeningHistory
import org.tegro.yamp.data.network.NetworkMonitor
import org.tegro.yamp.data.network.TrackDownloadManager
import org.tegro.yamp.data.provider.yandex.network.YandexMusicApi
import org.tegro.yamp.data.player.fakes.FixedYandexClients
import org.tegro.yamp.data.player.fakes.FakeAuthRepository
import org.tegro.yamp.data.player.fakes.FakePlayerRepository
import org.tegro.yamp.data.player.fakes.FakePlayerRepository.Call
import org.tegro.yamp.data.player.fakes.FakeSettingsRepository
import org.tegro.yamp.data.player.fakes.FakeTrackUrlResolver
import org.tegro.yamp.data.player.fakes.TestThreadMain
import org.tegro.yamp.data.provider.yandex.queue.LegacySessionImport
import org.tegro.yamp.data.queue.QueueManager
import org.tegro.yamp.data.provider.yandex.queue.QueueTrackBridge
import org.tegro.yamp.data.queue.RoomQueueStore
import org.tegro.yamp.data.repository.RadioRepository
import org.tegro.yamp.data.provider.yandex.reserve.RoadReserve
import org.tegro.yamp.di.DispatcherProvider
import org.tegro.yamp.domain.queue.MediaRef
import org.tegro.yamp.domain.queue.QueueKind
import org.tegro.yamp.domain.queue.RadioSeed
import org.tegro.yamp.domain.queue.RadioTarget
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

    /** What the service was asked, as "METHOD path" — the rotor requests of the radio tests. */
    private val requests = java.util.concurrent.CopyOnWriteArrayList<String>()

    /** Rotor stations that answer, by station id, with these track ids per batch (one batch per request). */
    private val stations = java.util.concurrent.ConcurrentHashMap<String, ArrayDeque<List<String>>>()

    /**
     * The service is never reached: every request is refused, which the wave and the feedback survive — except
     * the station tracks the radio tests set up in [stations].
     */
    private val client = OkHttpClient.Builder().addInterceptor(
        Interceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath.removePrefix("/").replace("%3A", ":")
            requests += "${request.method} $path"
            val station = Regex("rotor/station/(.+)/tracks").find(path)?.groupValues?.get(1)
            val batch = station?.let { id -> stations[id]?.let { synchronized(it) { it.removeFirstOrNull() } } }
            val (code, body) = if (batch != null) 200 to stationBody(batch) else 404 to """{"error":{"name":"not-found","message":""}}"""
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
    ).build()

    private fun stationBody(ids: List<String>): String = ids.joinToString(",", """{"result":{"sequence":[""", """],"batchId":"b"}}""") { id ->
        resolver.urls[id] = resolver.url(id)
        """{"track":{"id":"$id","title":"Track $id","artists":[{"id":"a$id","name":"Artist $id"}],"durationMs":200000},"liked":false}"""
    }

    private val api = Retrofit.Builder()
        .baseUrl("https://api.music.yandex.net/")
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(YandexMusicApi::class.java)

    private val auth = FakeAuthRepository()
    private val settings = FakeSettingsRepository()
    private val engine = FakePlayerRepository(context)
    private val nextFetch = org.tegro.yamp.data.player.fakes.FakeNextTrackFetch()
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

    // The controller's scope is Dispatchers.Main.immediate: here the test thread, so every call runs to its end
    // at once, and whatever resumes on another thread comes back to it ([TestThreadMain])
    private val time = UnconfinedTestDispatcher()
    private val dispatcher = TestThreadMain(time)

    /** Moves the controller's clock (its delays run on [time]). */
    private fun passes(ms: Long) {
        time.scheduler.advanceTimeBy(ms)
        time.scheduler.runCurrent()
    }
    private val dispatchers = object : DispatcherProvider {
        override val main get() = dispatcher
        override val io get() = dispatcher
        override val default get() = dispatcher
    }
    private val managerScopes = mutableListOf<CoroutineScope>()

    @Before
    fun setUp() {
        dispatcher.bindToCurrentThread()
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

    /** The Plus gate over this test's accounts and settings: nothing is refused until an account's answer says "no Plus" (D101). */
    private val plusGate by lazy { org.tegro.yamp.data.account.AccountPlusGate(auth, settings) }

    /** The signed-in account's answer from the server: no Plus. */
    private fun withoutPlus() {
        val driver = auth.activeAccount.value!!.copy(hasPlus = false, plusCheckedAt = 5_000L)
        auth.allAccounts.value = listOf(driver)
        auth.activeAccount.value = driver
    }

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

    /** The listening history over this test's database, written as the controller reports sound. */
    private val history by lazy {
        val scope = CoroutineScope(SupervisorJob() + dispatcher).also { managerScopes += it }
        ListeningHistory(db.historyDao(), QueueTrackBridge(db.dao(), json), settings, scope, dispatchers) { 1_700_000_000_000L }
    }

    /** A controller on the real stores of this test's app, with its saved session (if any) already loaded. */
    private fun controller(
        engine: FakePlayerRepository = this.engine,
        queueManager: QueueManager = manager(),
        clients: org.tegro.yamp.data.network.YandexClients = FixedYandexClients(api),
        journal: PlaybackJournal = PlaybackJournal(now = { 0L }),
        jellyfin: org.tegro.yamp.data.provider.jellyfin.JellyfinAccountStore? = null,
        otherStreams: (org.tegro.yamp.domain.account.AccountId) -> org.tegro.yamp.domain.provider.StreamResolver? = { null },
        audioRouter: AudioRouterWatch = AudioRouterWatch.None,
        local: org.tegro.yamp.data.provider.local.LocalAccountStore? = null,
        localPlayback: LocalPlayback = LocalPlayback.None,
        plusGate: org.tegro.yamp.data.account.PlusGate = this.plusGate
    ): PlaybackController {
        val legacyImport = LegacySessionImport(store, cacheManager, RoomQueueStore(db.queueDao(), db.dao()), QueueTrackBridge(db.dao(), json))
        val controller = PlaybackController(
            context, clients, auth, engine,
            TrackDownloadManager(context, client), resolver, cacheManager, org.tegro.yamp.data.provider.radio.FmRadio(context, RadioRepository(context)),
            queueManager, legacyImport, db.dao(),
            settings, NetworkMonitor(context), RoadReserve(context, client, json),
            journal, DiagnosticLog(context, settings), history,
            org.tegro.yamp.data.account.AccountContext(auth, queueManager, jellyfin, local),
            org.tegro.yamp.data.provider.StreamResolvers { account ->
                account?.let(otherStreams) ?: org.tegro.yamp.data.provider.yandex.YandexStreamResolver(resolver, dispatchers, { null }, account)
            },
            nextFetch,
            audioRouter,
            localPlayback,
            plusGate
        )
        controllers += controller
        await { controller.awaitRestoredSession() }
        return controller
    }

    private fun <T> await(block: suspend () -> T): T = dispatcher.await(block = block)

    /** The queue operations asked for so far have run: the engine calls and the derived flows are settled. */
    private fun PlaybackController.settle() = await { awaitQueueIdle() }

    private fun dto(id: String) = TrackDetailsDto(
        id = JsonPrimitive(id),
        title = "Track $id",
        artists = listOf(ArtistDto(id = JsonPrimitive("a$id"), name = "Artist $id")),
        durationMs = 200_000
    )

    private fun track(id: String): Track = dto(id).toTrack()

    /** Tracks by id, each with a stream link the resolver knows. */
    private fun playlist(vararg ids: String): List<Track> {
        ids.forEach { resolver.urls[it] = resolver.url(it) }
        return ids.map(::track)
    }

    /** Starts [id] from a library queue, lets it sound and forgets the engine calls so far. */
    private fun PlaybackController.playing(id: String, queue: List<Track>) {
        playTrack(id, queue, PlaylistSource.LIBRARY)
        settle()
        engine.sounds()
        engine.calls.clear()
    }

    private val List<Track>.ids get() = map { it.idString }

    @Test
    fun aJellyfinPagePlaysInItsAccountsQueueThroughItsOwnResolverAndYandexStaysAsItWas() {
        // step 23: the tracks of a Jellyfin page resolve through that account's resolver, in its own queue (D84)
        val home = org.tegro.yamp.data.provider.jellyfin.JellyfinAccount(
            serverUrl = "http://10.0.2.2:8096", serverId = "srv1", serverName = "Home Media",
            userId = "u1", userName = "alex", token = "t0k"
        )
        val store = org.tegro.yamp.data.provider.jellyfin.JellyfinAccountStore {
            context.getSharedPreferences("controller_jellyfin", android.content.Context.MODE_PRIVATE)
        }.apply { add(home) }
        val asked = mutableListOf<String>()
        val jellyfinStreams = object : org.tegro.yamp.domain.provider.StreamResolver {
            override fun cached(track: org.tegro.yamp.domain.queue.TrackRef): org.tegro.yamp.domain.provider.ResolvedStream? = null
            override suspend fun resolve(
                track: org.tegro.yamp.domain.queue.TrackRef,
                prefs: org.tegro.yamp.domain.provider.StreamPrefs
            ): org.tegro.yamp.domain.provider.Outcome<org.tegro.yamp.domain.provider.ResolvedStream> {
                asked += track.value
                return org.tegro.yamp.domain.provider.Outcome.Ok(
                    org.tegro.yamp.domain.provider.ResolvedStream("http://10.0.2.2:8096/Audio/${track.id}/universal", headers = mapOf("Authorization" to "MediaBrowser Token=\"t0k\""))
                )
            }
            override fun invalidate(track: org.tegro.yamp.domain.queue.TrackRef) = Unit
        }
        val controller = controller(jellyfin = store, otherStreams = { account -> jellyfinStreams.takeIf { account == home.id } })
        val refs = org.tegro.yamp.provider.jellyfin.JellyfinRefs("srv1")
        val album = listOf("j1", "j2").map { id ->
            Track(ref = refs.track(id), title = "Song $id", artists = emptyList(), album = null, durationMs = 180_000L, cover = null, explicit = false)
        }

        controller.playInAccount(home.id, album, "j1")
        controller.settle()

        assertEquals(listOf(refs.track("j1").value), asked.take(1))
        assertEquals("http://10.0.2.2:8096/Audio/j1/universal", engine.played.last().url)
        assertEquals("the page plays in the Jellyfin account's own queue", home.id.value, controller.activeQueue.value?.queue?.account)

        // A Yandex queue afterwards resolves through the Yandex resolver as before
        val yandex = playlist("1", "2")
        controller.playTrack("1", yandex, PlaylistSource.LIBRARY)
        controller.settle()
        assertEquals(resolver.url("1"), engine.played.last().url)
        assertNotEquals("Yandex tracks left the Jellyfin queue", home.id.value, controller.activeQueue.value?.queue?.account)
        assertTrue("no Yandex track was asked of the Jellyfin resolver", asked.all { it.startsWith("jellyfin@srv1:") })
    }


    // region The Plus gate (D101)

    @Test
    fun aQueueOfAnAccountWithoutPlusStartsNothingAndIsNotCleared() {
        withoutPlus()
        val journal = PlaybackJournal(now = { 0L })
        val refused = mutableListOf<String>()
        val controller = controller(journal = journal)
        CoroutineScope(SupervisorJob() + dispatcher).also { managerScopes += it }.launch { controller.plusRefused.collect { refused += it } }

        controller.playTrack("1", playlist("1", "2"), PlaylistSource.LIBRARY)
        controller.settle()

        assertTrue("nothing was handed to the engine", engine.played.isEmpty())
        assertEquals("the message bar is told which account", listOf("driver"), refused)
        assertTrue(journal.state.value.events.any { it.kind == PlaybackJournal.Kind.STOP && it.detail == "no-plus" })
        // A refusal is not a failure: no skip, no retry
        assertFalse("next" in engine.names)

        controller.play()
        controller.settle()
        assertTrue("Play of a refused account starts nothing either", engine.played.isEmpty())
    }

    @Test
    fun theDeveloperOptionLiftsTheGateAndTurningItOffBringsItBack() {
        withoutPlus()
        settings.allowYandexWithoutPlus.value = true
        val controller = controller()
        controller.playTrack("1", playlist("1", "2"), PlaylistSource.LIBRARY)
        controller.settle()
        assertEquals(listOf("1"), engine.played.map { it.trackId })

        engine.sounds()
        settings.allowYandexWithoutPlus.value = false
        assertTrue("the option off: what played pauses", "pause" in engine.names)
    }

    @Test
    fun anAnswerThatFlipsMidPlayPausesAndKeepsTheQueue() {
        val journal = PlaybackJournal(now = { 0L })
        val controller = controller(journal = journal)
        val list = playlist("1", "2", "3")
        controller.playing("2", list)

        auth.updatePlus("1001", hasPlus = false, checkedAt = 9_000L)

        assertEquals("paused where it was", listOf("pause"), engine.names)
        assertEquals("the queue is not cleared", list.ids, controller.currentPlaylist.value.ids)
        assertEquals("2", controller.currentTrack.value?.idString)
        assertTrue(journal.state.value.events.any { it.kind == PlaybackJournal.Kind.STOP && it.detail == "no-plus" })
    }

    @Test
    fun mediaSessionResumptionReturnsNothingForAnAccountWithoutPlus() {
        val controller = controller()
        controller.playing("2", playlist("1", "2", "3"))
        engine.isPlaying.value = false
        assertTrue("with Plus it resumes", await { controller.prepareResumption(startPlayback = false) } != null)

        auth.updatePlus("1001", hasPlus = false, checkedAt = 9_000L)
        engine.calls.clear()

        assertNull(await { controller.prepareResumption(startPlayback = true) })
        assertTrue("and starts nothing", engine.played.isEmpty())
    }

    @Test
    fun jellyfinAndFmKeepPlayingWhileAYandexAccountHasNoPlusAndFmStopsOnlyUnderABlockedActiveAccount() {
        withoutPlus()
        val (controller, home, jellyfinTracks) = jellyfinSetup("controller_jellyfin_plus")
        val station = org.tegro.yamp.data.provider.radio.FmRadio(context, RadioRepository(context)).stations().first()

        controller.playInAccount(home.id, jellyfinTracks, "j1")
        controller.settle()
        assertEquals("Jellyfin plays", "http://10.0.2.2:8096/Audio/j1/universal", engine.played.last().url)

        engine.calls.clear()
        controller.playRadioStation(station)
        controller.settle()
        assertEquals("FM plays: the active account is Jellyfin", station.streamUrl, engine.played.last().url)

        // The Yandex account is the active one: its queue (and FM through it) is refused
        engine.calls.clear()
        controller.playTrack("1", playlist("1"), PlaylistSource.LIBRARY)
        controller.settle()
        assertTrue("its music does not start", engine.played.isEmpty())
    }

    @Test
    fun fmDoesNotStartUnderABlockedActiveAccount() {
        withoutPlus()
        val controller = controller()
        val station = org.tegro.yamp.data.provider.radio.FmRadio(context, RadioRepository(context)).stations().first()

        controller.playRadioStation(station)
        controller.settle()

        assertTrue(engine.played.isEmpty())
    }


    @Test
    fun aRefusedSkipChangesNothing() {
        val controller = controller()
        controller.playing("2", playlist("1", "2", "3"))
        auth.updatePlus("1001", hasPlus = false, checkedAt = 9_000L)
        engine.calls.clear()

        controller.playNext()
        controller.settle()
        controller.playPrev()
        controller.settle()

        assertTrue("no fade, no stop, no start: ${engine.names}", engine.calls.isEmpty())
        assertEquals("2", controller.currentTrack.value?.idString)
        assertFalse("not 'loading'", controller.isStarting.value)
    }

    @Test
    fun aListPlayedFromOneAccountsWaveNeverReplacesAnotherAccountsQueue() {
        val rockWithoutPlus = second.copy(hasPlus = false, plusCheckedAt = 5_000L)
        auth.allAccounts.value = listOf(auth.activeAccount.value!!, rockWithoutPlus)
        val manager = manager()
        val controller = controller(queueManager = manager)
        controller.ensureWaveQueue(anna)
        controller.settle()
        val rockQueue = await { manager.create("Rock", account = rockId.value) }
        controller.addToQueue(rockQueue.id, playlist("7", "8"))
        controller.settle()
        controller.switchQueue(controller.queues.value.single { it.queue.kind == QueueKind.WAVE && it.queue.account == anna.value }.queue.id)
        controller.settle()

        controller.playTrack("1", playlist("1", "2"), PlaylistSource.LIBRARY)
        controller.settle()

        assertEquals("the refused account's queue is untouched", listOf("7", "8"), await { controller.peekQueue(rockQueue.id) }!!.tracks.ids)
        assertEquals("1", controller.currentTrack.value?.idString)
        assertEquals("the list went to Anna's own queue", anna.value, controller.activeQueue.value?.queue?.account)
    }

    @Test
    fun aRefusedListLeavesTheQueueItWouldHaveReplacedAsItWas() {
        withoutPlus()
        val manager = manager()
        val controller = controller(queueManager = manager)
        val mine = await { manager.create("Mine", account = anna.value) }
        controller.addToQueue(mine.id, playlist("7", "8"))
        controller.switchQueue(mine.id)
        controller.settle()

        controller.playTrack("1", playlist("1", "2"), PlaylistSource.LIBRARY)
        controller.settle()

        assertEquals(listOf("7", "8"), await { controller.peekQueue(mine.id) }!!.tracks.ids)
        assertTrue(engine.played.isEmpty())
    }

    @Test
    fun aYandexRadioIsNeverOwnedByAnotherProvidersAccount() {
        withoutPlus()
        val (controller, home, _) = jellyfinSetup("controller_jellyfin_radio")

        controller.startRadio(radioSeed("1"), RadioTarget.New, keepHistory = false, track = null, account = home.id.value)
        controller.settle()

        assertTrue("no radio of a Yandex seed under Jellyfin", controller.queues.value.none { it.queue.kind == QueueKind.RADIO })
        assertTrue(engine.played.isEmpty())
    }

    /** The settings of a cold start: the developer option has not been read yet. */
    private class ColdSettings(
        inner: org.tegro.yamp.domain.repository.SettingsRepository,
        override val allowYandexWithoutPlus: kotlinx.coroutines.flow.Flow<Boolean>
    ) : org.tegro.yamp.domain.repository.SettingsRepository by inner

    /** A saved session of a known no-Plus account, the way a power-on finds it, and a gate whose option is still being read. */
    private fun coldStart(): Triple<PlaybackController, FakePlayerRepository, kotlinx.coroutines.flow.MutableSharedFlow<Boolean>> {
        val first = controller()
        first.playing("2", playlist("1", "2", "3"))
        first.persistNow()
        first.settle()
        withoutPlus()
        val option = kotlinx.coroutines.flow.MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
        val gate = org.tegro.yamp.data.account.AccountPlusGate(auth, ColdSettings(settings, option))
        assertTrue("a known no-Plus account is refused from the first moment", gate.blocks(anna))
        assertFalse(gate.isReady)
        val coldEngine = FakePlayerRepository(context)
        return Triple(controller(coldEngine, plusGate = gate), coldEngine, option)
    }

    @Test
    fun aPlayBeforeTheOptionIsReadWaitsForItAndIsRefusedWhenItIsOff() {
        val (controller, coldEngine, option) = coldStart()

        // The Play waits in the queue (its settle would wait for the option too), so the engine is looked at directly
        controller.play()
        assertTrue("nothing starts while the option is unknown", coldEngine.played.isEmpty())

        option.tryEmit(false)
        controller.settle()
        assertTrue("the known no-Plus account is refused", coldEngine.played.isEmpty())
    }

    @Test
    fun aPlayBeforeTheOptionIsReadStartsOnceItIsOn() {
        val (controller, coldEngine, option) = coldStart()

        controller.play()
        option.tryEmit(true)
        controller.settle()
        passes(100)
        assertEquals(listOf("2"), coldEngine.played.map { it.trackId })
    }

    @Test
    fun resumptionOfAKnownNoPlusAccountReturnsNothingEvenBeforeTheOptionIsRead() {
        val (controller, _, option) = coldStart()
        option.tryEmit(false)

        assertNull(await { controller.prepareResumption(startPlayback = true) })
    }

    // endregion

    /** A Jellyfin account on server "srv1" and a controller that resolves its tracks (steps 23–25). */
    private fun jellyfinSetup(
        prefs: String,
        midTrack: (Int?) -> org.tegro.yamp.domain.provider.MidTrackAction = { org.tegro.yamp.domain.provider.MidTrackAction.RESTART }
    ): Triple<PlaybackController, org.tegro.yamp.data.provider.jellyfin.JellyfinAccount, List<Track>> {
        val home = org.tegro.yamp.data.provider.jellyfin.JellyfinAccount(
            serverUrl = "http://10.0.2.2:8096", serverId = "srv1", serverName = "Home Media",
            userId = "u1", userName = "alex", token = "t0k"
        )
        val store = org.tegro.yamp.data.provider.jellyfin.JellyfinAccountStore {
            context.getSharedPreferences(prefs, android.content.Context.MODE_PRIVATE)
        }.apply { add(home) }
        val streams = object : org.tegro.yamp.domain.provider.StreamResolver {
            override fun cached(track: org.tegro.yamp.domain.queue.TrackRef): org.tegro.yamp.domain.provider.ResolvedStream? = null
            override suspend fun resolve(
                track: org.tegro.yamp.domain.queue.TrackRef,
                prefs: org.tegro.yamp.domain.provider.StreamPrefs
            ): org.tegro.yamp.domain.provider.Outcome<org.tegro.yamp.domain.provider.ResolvedStream> =
                org.tegro.yamp.domain.provider.Outcome.Ok(org.tegro.yamp.domain.provider.ResolvedStream("http://10.0.2.2:8096/Audio/${track.id}/universal"))
            override fun invalidate(track: org.tegro.yamp.domain.queue.TrackRef) = Unit
            override fun midTrackAction(track: org.tegro.yamp.domain.queue.TrackRef, httpCode: Int?) = midTrack(httpCode)
        }
        val controller = controller(jellyfin = store, otherStreams = { account -> streams.takeIf { account == home.id } })
        val refs = org.tegro.yamp.provider.jellyfin.JellyfinRefs("srv1")
        val tracks = listOf("j1", "j2").map { id ->
            Track(ref = refs.track(id), title = "Song $id", artists = emptyList(), album = null, durationMs = 180_000L, cover = null, explicit = false)
        }
        return Triple(controller, home, tracks)
    }

    @Test
    fun aQueueNeverTakesAnotherAccountsTracks() {
        // D84 (step 25): "Add to queue…", "Play next" and "Add to end" keep a queue to its own account's tracks
        val (controller, home, jellyfinTracks) = jellyfinSetup("controller_jellyfin_fit")
        controller.playTrack("1", playlist("1", "2"), PlaylistSource.LIBRARY)
        controller.settle()
        val yandexQueue = controller.activeQueue.value!!.queue.id

        controller.enqueueNext(jellyfinTracks[0])
        controller.addToEnd(jellyfinTracks)
        controller.addToQueue(yandexQueue, jellyfinTracks)
        controller.settle()
        assertEquals("a Yandex queue took no Jellyfin track", listOf("1", "2"), controller.activeQueue.value!!.tracks.map { it.ref.id })

        controller.playInAccount(home.id, jellyfinTracks, "j1")
        controller.settle()
        val jellyfinQueue = controller.activeQueue.value!!.queue.id
        controller.addToQueue(jellyfinQueue, listOf(track("3")))
        controller.enqueueNext(track("3"))
        controller.settle()
        assertEquals("the Jellyfin queue took no Yandex track", listOf("j1", "j2"), controller.activeQueue.value!!.tracks.map { it.ref.id })
    }

    /** The connection goes: Robolectric's network loses its internet capability. */
    private fun goOffline() {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        shadowOf(connectivity).setNetworkCapabilities(connectivity.activeNetwork, ShadowNetworkCapabilities.newInstance())
    }

    @Test
    fun offlineAJellyfinTrackAllInTheDiskCachePlaysAndOneNotThereIsPassedOver() {
        // gap (b): with no connection, a fully cached Jellyfin stream plays by its key; a track not on the device is skipped
        val (controller, home, jellyfinTracks) = jellyfinSetup("controller_jellyfin_offline")
        nextFetch.cached["j2"] = org.tegro.yamp.data.player.StreamCache.CachedState.COMPLETE
        goOffline()

        controller.playInAccount(home.id, jellyfinTracks, "j1")
        controller.settle()

        assertEquals("http://10.0.2.2:8096/Audio/j2/universal", engine.played.lastOrNull()?.url)
        assertTrue("j1 is not on the device: never handed to the engine", engine.played.none { it.url?.endsWith("/j1/universal") == true })
    }

    @Test
    fun aJellyfinTrackTappedInMergedSearchOrHistoryPlaysInItsOwnAccountsQueue() {
        // Search mixes providers and History keeps every track (step 24/25): a Jellyfin one plays under its account
        val (controller, home, jellyfinTracks) = jellyfinSetup("controller_jellyfin_tap")
        controller.playTrack("1", playlist("1", "2"), PlaylistSource.LIBRARY)
        controller.settle()

        controller.playTrack("j2", playlist("5") + jellyfinTracks, PlaylistSource.SEARCH)
        controller.settle()
        assertEquals(home.id.value, controller.activeQueue.value?.queue?.account)
        assertEquals("only the tapped track's service went into the queue", listOf("j1", "j2"), controller.activeQueue.value!!.tracks.map { it.ref.id })
        assertEquals("http://10.0.2.2:8096/Audio/j2/universal", engine.played.last().url)

        controller.playTrack("1", playlist("1", "2"), PlaylistSource.LIBRARY)
        controller.settle()
        controller.playNowKeepQueue(jellyfinTracks[0])
        controller.settle()
        assertEquals("History's Jellyfin row plays in its account's queue", home.id.value, controller.activeQueue.value?.queue?.account)
        assertEquals("http://10.0.2.2:8096/Audio/j1/universal", engine.played.last().url)
    }

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

    /** D80/D86: the next track loads right after the current one starts, its file goes to disk, then it is ready. */
    @Test
    fun theNextTrackIsFetchedEarlyAndReadyOnceItsFileIsOnDisk() {
        val controller = controller()
        controller.playTrack("1", playlist("1", "2", "3"), PlaylistSource.LIBRARY)
        controller.settle()

        assertEquals(org.tegro.yamp.domain.player.NextTrack.Loading("2"), controller.nextTrack.value)
        assertEquals("the whole file goes to disk", listOf("2"), nextFetch.prefetched)

        nextFetch.finish("2", NextTrackFetch.Outcome.WRITTEN)
        controller.settle()
        assertEquals(org.tegro.yamp.domain.player.NextTrack.Ready("2", onDisk = true), controller.nextTrack.value)
    }

    /** A Jellyfin account whose tracks are converted streams (D91): no length, downloaded whole. */
    private fun convertedSetup(prefs: String): Pair<PlaybackController, List<Track>> {
        val home = org.tegro.yamp.data.provider.jellyfin.JellyfinAccount(
            serverUrl = "http://10.0.2.2:8096", serverId = "srv1", serverName = "Home Media",
            userId = "u1", userName = "alex", token = "t0k"
        )
        val store = org.tegro.yamp.data.provider.jellyfin.JellyfinAccountStore {
            context.getSharedPreferences(prefs, android.content.Context.MODE_PRIVATE)
        }.apply { add(home) }
        val links = mutableMapOf<String, org.tegro.yamp.domain.provider.ResolvedStream>()
        val streams = object : org.tegro.yamp.domain.provider.StreamResolver {
            override fun cached(track: org.tegro.yamp.domain.queue.TrackRef) = links[track.value]
            override suspend fun resolve(
                track: org.tegro.yamp.domain.queue.TrackRef,
                prefs: org.tegro.yamp.domain.provider.StreamPrefs
            ): org.tegro.yamp.domain.provider.Outcome<org.tegro.yamp.domain.provider.ResolvedStream> =
                org.tegro.yamp.domain.provider.Outcome.Ok(
                    org.tegro.yamp.domain.provider.ResolvedStream(
                        "http://10.0.2.2:8096/Audio/${track.id}/universal", cacheKey = "jf|${track.id}|lossy-320", progressive = true
                    ).also { links[track.value] = it }
                )
            override fun invalidate(track: org.tegro.yamp.domain.queue.TrackRef) {
                links.remove(track.value)
            }
        }
        val controller = controller(jellyfin = store, otherStreams = { account -> streams.takeIf { account == home.id } })
        val refs = org.tegro.yamp.provider.jellyfin.JellyfinRefs("srv1")
        val tracks = listOf("j1", "j2", "j3").map { id ->
            Track(ref = refs.track(id), title = "Song $id", artists = emptyList(), album = null, durationMs = 180_000L, cover = null, explicit = false)
        }
        controller.playInAccount(home.id, tracks, "j1")
        controller.settle()
        return controller to tracks
    }

    /** D91 error table: 6 s with no data on a converted stream is a link check only — no copy, no restart of the track. */
    @Test
    fun aStallOnAConvertedStreamOnlyChecksTheLink() {
        val (controller, _) = convertedSetup("controller_converted_stall")
        engine.calls.clear()
        passes(7_000)
        assertEquals("nothing restarted", emptyList<Call>(), engine.played)
        assertEquals("j1", controller.currentTrack.value?.idString)
    }

    /** D91 error table: while a reopen waits for a restarted download, its written bytes count as data — no pause. */
    @Test
    fun aMovingDownloadNeverPausesAndAStillOnePausesAfter20s() {
        val (controller, _) = convertedSetup("controller_converted_moving")
        engine.calls.clear()
        repeat(40) {
            engine.written += 64_000
            passes(1_000)
        }
        assertFalse("never paused", "pause" in engine.names)
        assertTrue(controller.isPlaying.value)

        passes(21_000)
        assertTrue("no bytes for 20 s: paused", "pause" in engine.names)
        assertEquals("never skipped", emptyList<Call>(), engine.played)
    }

    /**
     * D91 error policy with D67: a download that broke on slowness (timeouts with bytes on the way) and used its restarts
     * pauses at once — no RETRY, never a skip; Play then loads it anew from where it stopped.
     */
    @Test
    fun aDownloadThatTimedOutPausesAndPlayLoadsItAgain() {
        val (controller, _) = convertedSetup("controller_converted_slow")
        engine.sounds()
        engine.calls.clear()
        val reason = androidx.media3.common.PlaybackException.getErrorCodeName(
            ProgressiveDownloadException.of(org.tegro.yamp.data.player.fakes.FeedServer.timeout(), broughtBytes = true).reason
        )
        engine.fail("j1", 30_000L, reason, download = true)
        controller.settle()
        assertTrue("paused", "pause" in engine.names)
        assertEquals("no RETRY, no skip", emptyList<Call>(), engine.played)
        assertEquals("j1", controller.currentTrack.value?.idString)

        // Play on a failed player: the engine reports it idle, the controller loads the track again at the position
        engine.fail("j1", 30_000L, "RESUME_IDLE")
        controller.settle()
        assertEquals(listOf(Call("playTrack", "j1", "http://10.0.2.2:8096/Audio/j1/universal", 30_000L)), engine.played)
    }

    /** D91 error policy: a download the server refused goes RETRY with a new link, then skip. */
    @Test
    fun aRefusedDownloadIsRetriedOnceThenSkipped() {
        val (controller, _) = convertedSetup("controller_converted_failed")
        engine.sounds()
        engine.calls.clear()
        val reason = androidx.media3.common.PlaybackException.getErrorCodeName(
            ProgressiveDownloadException.of(org.tegro.yamp.data.player.fakes.FeedServer.refused(403), broughtBytes = true).reason
        )
        engine.fail("j1", 30_000L, reason, download = true)
        controller.settle()
        assertEquals("RETRY: the same track, a new link, from where it broke", listOf(Call("playTrack", "j1", "http://10.0.2.2:8096/Audio/j1/universal", 30_000L)), engine.played)

        engine.fail("j1", 30_000L, reason, download = true)
        controller.settle()
        assertEquals("then skip", listOf("j1", "j2"), engine.played.map { it.trackId })
        assertFalse("never paused", "pause" in engine.names)
    }

    /** D91: the downloads hear the controller's link — a restart is held while there is none. */
    @Test
    fun theDownloadsHearTheLink() {
        controller()
        assertEquals(true, nextFetch.links.lastOrNull())
    }

    /** D91: before anything is read there is no share to show — the message says so without a percent. */
    @Test
    fun aSeekRefusedWithNothingToEstimateHasNoPercent() {
        val controller = controller()
        controller.playing("1", playlist("1", "2"))
        val messages = java.util.concurrent.CopyOnWriteArrayList<String>()
        val collecting = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch { controller.messages.collect { messages += it } }
        engine.seekLock.value = 0f
        controller.seekTo(42_000L)
        controller.settle()
        assertEquals(listOf(ApplicationProvider.getApplicationContext<Application>().getString(org.tegro.yamp.R.string.seek_locked_message_no_percent)), messages.toList())
        collecting.cancel()
    }

    /**
     * b85 checkpoint: a next track already on disk (the one just left by a skip back) is ready at once — even while the
     * engine is still settling, when the preload itself waits; its start on disk is ready too, the rest fills in.
     */
    @Test
    fun aNextTrackAlreadyOnDiskIsReadyAtOnceAfterASkipBack() {
        val controller = controller()
        val tracks = playlist("1", "2", "3")
        controller.playing("2", tracks)
        nextFetch.cached["2"] = org.tegro.yamp.data.player.StreamCache.CachedState.COMPLETE
        engine.unsettled = true

        controller.playPrev()
        controller.settle()

        assertEquals("1", controller.currentTrack.value?.idString)
        assertEquals(org.tegro.yamp.domain.player.NextTrack.Ready("2", onDisk = true), controller.nextTrack.value)

        nextFetch.cached["3"] = org.tegro.yamp.data.player.StreamCache.CachedState.START
        controller.playTrack("2", tracks, PlaylistSource.LIBRARY)
        controller.settle()
        assertEquals("its start on disk: a skip to it sounds at once", org.tegro.yamp.domain.player.NextTrack.Ready("3", onDisk = false), controller.nextTrack.value)
    }

    /**
     * Local files (docs/phase-4d-local-files.md §4, D96): tracks on volume "SD" and on a stick "USB"; [absent] are the
     * volumes not mounted. The resolver answers as the real one does — "volume" for a track on an absent volume.
     */
    private inner class LocalFiles(prefs: String) {
        val absent = mutableSetOf<String>()
        val changes = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 4)
        val store = org.tegro.yamp.data.provider.local.LocalAccountStore(
            { context.getSharedPreferences(prefs, android.content.Context.MODE_PRIVATE) }, { "This device" }
        ).apply { set(true) }
        private fun volumeOf(ref: org.tegro.yamp.domain.queue.TrackRef) = ref.id.substringBefore('/')
        val streams = object : org.tegro.yamp.domain.provider.StreamResolver {
            override fun cached(track: org.tegro.yamp.domain.queue.TrackRef): org.tegro.yamp.domain.provider.ResolvedStream? = null
            override suspend fun resolve(
                track: org.tegro.yamp.domain.queue.TrackRef,
                prefs: org.tegro.yamp.domain.provider.StreamPrefs
            ): org.tegro.yamp.domain.provider.Outcome<org.tegro.yamp.domain.provider.ResolvedStream> =
                if (volumeOf(track) in absent) org.tegro.yamp.domain.provider.Outcome.Err(org.tegro.yamp.domain.provider.ProviderError.Unavailable("volume"))
                else org.tegro.yamp.domain.provider.Outcome.Ok(org.tegro.yamp.domain.provider.ResolvedStream("file:///storage/${track.id}", localFile = true))
            override fun invalidate(track: org.tegro.yamp.domain.queue.TrackRef) = Unit
        }
        val playback = object : LocalPlayback {
            override fun isAvailable(track: org.tegro.yamp.domain.queue.TrackRef) = volumeOf(track) !in absent
            override fun volumeName(track: org.tegro.yamp.domain.queue.TrackRef) = "USB “${volumeOf(track)}”"
            override val changes: kotlinx.coroutines.flow.Flow<Unit> = this@LocalFiles.changes
        }

        fun controller(engine: FakePlayerRepository = this@PlaybackControllerTest.engine) = controller(
            engine = engine, local = store, localPlayback = playback,
            otherStreams = { account -> streams.takeIf { account == org.tegro.yamp.domain.account.AccountId.local() } }
        )

        fun track(path: String) = Track(
            ref = org.tegro.yamp.domain.queue.TrackRef.of("local", path), title = path.substringAfterLast('/'), artists = emptyList(),
            album = null, durationMs = 180_000L, cover = null, explicit = false
        )

        /** Mounts (or unmounts) [volume], as the volume monitor reports it. */
        fun mount(volume: String, mounted: Boolean) {
            if (mounted) absent -= volume else absent += volume
            check(changes.tryEmit(Unit))
        }
    }

    @Test
    fun aTrackOnAnAbsentVolumeIsSkippedAtOnceDuringPlayback() {
        val local = LocalFiles("controller_local_skip")
        val controller = local.controller()
        val tracks = listOf(local.track("SD/Music/01.mp3"), local.track("USB/Music/02.mp3"), local.track("SD/Music/03.mp3"))
        controller.playInAccount(org.tegro.yamp.domain.account.AccountId.local(), tracks, "SD/Music/01.mp3")
        controller.settle()
        engine.sounds()
        assertEquals("the queue is This device's (D84)", org.tegro.yamp.domain.account.AccountId.local().value, controller.activeQueue.value?.queue?.account)
        assertEquals("file:///storage/SD/Music/01.mp3", engine.played.last().url)

        local.mount("USB", mounted = false)
        controller.playNext(isAuto = true)
        controller.settle()
        assertEquals("02 is on the stick: passed over, no wait (it is not the network)", "SD/Music/03.mp3", engine.played.last().trackId)
        assertTrue(engine.played.none { it.trackId == "USB/Music/02.mp3" })

        // A tap on a dimmed entry: it can't play, the next one that can does
        controller.playEntry(controller.activeQueue.value!!.entries[1].entryId)
        controller.settle()
        assertEquals("SD/Music/03.mp3", engine.played.last().trackId)
    }

    @Test
    fun withEveryRemainingTrackUnavailablePlaybackPausesWithTheMessageNoSkipLoop() {
        val local = LocalFiles("controller_local_all")
        val controller = local.controller()
        val tracks = listOf(local.track("SD/Music/01.mp3"), local.track("USB/Music/02.mp3"), local.track("USB/Music/03.mp3"))
        controller.playInAccount(org.tegro.yamp.domain.account.AccountId.local(), tracks, "SD/Music/01.mp3")
        controller.settle()
        engine.sounds()
        val starts = engine.played.size

        // Both volumes go: the playing file fails, nothing else can play
        local.mount("USB", mounted = false)
        local.mount("SD", mounted = false)
        engine.fail("SD/Music/01.mp3", 30_000, "ERROR_CODE_IO_FILE_NOT_FOUND")
        controller.settle()
        assertEquals("nothing else was started", starts, engine.played.size)
        assertEquals("the cursor stays", "SD/Music/01.mp3", controller.currentTrack.value?.idString)
        assertFalse(controller.isPlaying.value)
        assertTrue("paused with the message", engine.names.contains("pause"))
    }

    @Test
    fun atRestoreTheQueueWaitsForItsStickAndPlaysOnWhenItMounts() {
        val local = LocalFiles("controller_local_restore")
        val first = local.controller()
        val tracks = listOf(local.track("USB/Music/01.mp3"), local.track("SD/Music/02.mp3"))
        first.playInAccount(org.tegro.yamp.domain.account.AccountId.local(), tracks, "USB/Music/01.mp3")
        first.settle()
        engine.sounds()
        engine.progress.value = 61_000
        first.persistNow()
        first.settle()

        // Cold boot: the stick mounts late
        local.absent += "USB"
        val engineAfterBoot = FakePlayerRepository(context)
        val restored = local.controller(engineAfterBoot)
        assertEquals("USB/Music/01.mp3", await { restored.currentTrack.first { it != null } }?.idString)
        restored.play() // auto-resume
        restored.settle()
        assertTrue("it waits — no skip to the SD track", engineAfterBoot.played.isEmpty())
        assertEquals("cursor unchanged", "USB/Music/01.mp3", restored.currentTrack.value?.idString)

        local.mount("USB", mounted = true)
        restored.settle()
        assertEquals("the stick is there: it plays on from where it was", "USB/Music/01.mp3", engineAfterBoot.played.single().trackId)
        assertEquals(61_000L, engineAfterBoot.played.single().positionMs)
    }

    /** Startup paths (first start, Jellyfin only): which queue is active once the saved session is restored. */
    @Test
    fun withNoAccountNoQueueIsActive() {
        auth.activeAccount.value = null
        auth.allAccounts.value = emptyList()
        val controller = controller()
        assertNull(controller.activeQueue.value)
    }

    @Test
    fun withJellyfinOnlyItsDefaultQueueIsActiveAtStart() {
        auth.activeAccount.value = null
        auth.allAccounts.value = emptyList()
        val home = org.tegro.yamp.data.provider.jellyfin.JellyfinAccount(
            serverUrl = "http://10.0.2.2:8096", serverId = "srv1", serverName = "Home Media",
            userId = "u1", userName = "alex", token = "t0k"
        )
        val store = org.tegro.yamp.data.provider.jellyfin.JellyfinAccountStore {
            context.getSharedPreferences("startup_jellyfin_only", android.content.Context.MODE_PRIVATE)
        }.apply { add(home) }

        val controller = controller(jellyfin = store)
        controller.settle()

        val active = controller.activeQueue.value
        assertEquals("no My Wave: the Jellyfin default queue is the way in (D83)", home.id.value, active?.queue?.account)
        assertEquals(org.tegro.yamp.domain.queue.QueueKind.STATIC, active?.queue?.kind)
        assertEquals("Home Media", active?.queue?.name)
        assertTrue(active?.entries.orEmpty().isEmpty())
    }

    @Test
    fun withYandexOnlyOrBothNothingIsMadeActiveBeforeTheSignInEntersMyWave() {
        assertNull("Yandex only: as before, the status refresh enters My Wave", controller().activeQueue.value)

        val home = org.tegro.yamp.data.provider.jellyfin.JellyfinAccount(
            serverUrl = "http://10.0.2.2:8096", serverId = "srv1", serverName = "Home Media",
            userId = "u1", userName = "alex", token = "t0k"
        )
        val store = org.tegro.yamp.data.provider.jellyfin.JellyfinAccountStore {
            context.getSharedPreferences("startup_both", android.content.Context.MODE_PRIVATE)
        }.apply { add(home) }
        assertNull("both: Yandex's first start is unchanged", controller(jellyfin = store).activeQueue.value)
    }

    /** The last Yandex account leaving while Jellyfin stays: playback falls back to the Jellyfin default queue. */
    @Test
    fun signingOutTheLastYandexAccountFallsBackToJellyfin() {
        val home = org.tegro.yamp.data.provider.jellyfin.JellyfinAccount(
            serverUrl = "http://10.0.2.2:8096", serverId = "srv1", serverName = "Home Media",
            userId = "u1", userName = "alex", token = "t0k"
        )
        val store = org.tegro.yamp.data.provider.jellyfin.JellyfinAccountStore {
            context.getSharedPreferences("fallback_jellyfin", android.content.Context.MODE_PRIVATE)
        }.apply { add(home) }
        val controller = controller(jellyfin = store)
        controller.playing("1", playlist("1", "2"))
        val yandex = org.tegro.yamp.domain.account.AccountId.yandex("1001")

        val fallback = controller.fallbackAccount(setOf(yandex))
        assertEquals(home.id, fallback)
        controller.leaveAccount(yandex, fallback)
        controller.settle()

        assertEquals(home.id.value, controller.activeQueue.value?.queue?.account)
    }

    /** D80: a warm standby makes the next track ready even before its file is all on disk. */
    @Test
    fun aWarmStandbyMakesTheNextTrackReady() {
        val controller = controller()
        controller.playTrack("1", playlist("1", "2"), PlaylistSource.LIBRARY)
        controller.settle()

        engine.warmTrackId.value = "2"
        controller.settle()

        assertEquals(org.tegro.yamp.domain.player.NextTrack.Ready("2", onDisk = false), controller.nextTrack.value)
    }

    /** D80/D81: a standby failing on the next track shows "failed"; a skip starts afresh and stops the write. */
    @Test
    fun aFailedNextTrackShowsFailedAndASkipStartsAfresh() {
        val controller = controller()
        controller.playTrack("1", playlist("1", "2", "3"), PlaylistSource.LIBRARY)
        controller.settle()

        engine.standbyFailedEvent.tryEmit("2" to "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED")
        controller.settle()
        assertEquals(org.tegro.yamp.domain.player.NextTrack.Failed("2", "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED"), controller.nextTrack.value)

        val cancelsBefore = nextFetch.cancels
        controller.playTrack("3", controller.currentPlaylist.value, PlaylistSource.LIBRARY)
        controller.settle()
        assertTrue("the write for another track stops", nextFetch.cancels > cancelsBefore)
        assertEquals("nothing after the last track", org.tegro.yamp.domain.player.NextTrack.None, controller.nextTrack.value)
    }

    /** D81: Next on a failed next track is its retry — the spent link is dropped and the skip loads it afresh. */
    @Test
    fun nextOnAFailedNextTrackRetriesWithAFreshLink() {
        val controller = controller()
        controller.playTrack("1", playlist("1", "2", "3"), PlaylistSource.LIBRARY)
        controller.settle()
        engine.standbyFailedEvent.tryEmit("2" to "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED")
        controller.settle()
        val evictedBefore = resolver.evicted.count { it == "2" }

        controller.playNext()
        controller.settle()

        assertTrue("the failed link is dropped", resolver.evicted.count { it == "2" } > evictedBefore)
        assertEquals("the skip goes to the failed track (load-first)", "2", controller.currentTrack.value?.idString)
    }

    /** D81: a track played from a file on disk shows its bar full. */
    @Test
    fun aTrackFromDiskReportsItsWholeFileAsBuffered() {
        val controller = controller()
        controller.playTrack("1", playlist("1", "2"), PlaylistSource.LIBRARY)
        controller.settle()
        engine.bufferedProgress.value = 12_000L
        controller.settle()
        assertEquals(12_000L, controller.bufferedProgress.value)

        nextFetch.finish("2", NextTrackFetch.Outcome.WRITTEN)
        controller.settle()
        controller.playNext()
        controller.settle()
        assertEquals("on disk: the whole bar", Long.MAX_VALUE, controller.bufferedProgress.value)
    }

    /** F4/D80: during a burst of skips nothing is prepared for a passing track. */
    @Test
    fun nothingIsPreparedUntilTheEngineIsSettled() {
        val controller = controller()
        engine.unsettled = true
        controller.playTrack("1", playlist("1", "2"), PlaylistSource.LIBRARY)
        controller.settle()

        assertTrue("held back during the burst", engine.prepared.isEmpty())
        assertTrue(nextFetch.prefetched.isEmpty())
    }

    /** Phase 2a step 4: a stream is resolved under a named account — the active one until queues carry theirs. */
    @Test
    fun aTracksLinkIsResolvedForTheActiveAccount() {
        val controller = controller()
        controller.playTrack("1", playlist("1", "2"), PlaylistSource.LIBRARY)
        controller.settle()

        // The playing track and the one standing by
        assertEquals(listOf("1", "2"), resolver.fetched)
        assertEquals(List(2) { org.tegro.yamp.domain.account.AccountId.yandex("1001") }, resolver.fetchedFor)
    }

    /** F6: the user's next / prev / pick taps are journal lines, so a dropout can be tied to a tap. */
    @Test
    fun nextPrevAndPickTapsAreJournalled() {
        val journal = PlaybackJournal(now = { 0L })
        val controller = controller(journal = journal)
        val queue = playlist("1", "2", "3")
        controller.playTrack("2", queue, PlaylistSource.LIBRARY)
        controller.settle()
        engine.sounds()
        controller.playNext()
        controller.settle()
        controller.playPrev()
        controller.settle()
        controller.playNext(isAuto = true)
        controller.settle()

        val taps = journal.state.value.events.filter { it.kind == PlaybackJournal.Kind.TAP }.map { it.detail to it.title }
        assertEquals(listOf("pick" to "Track 2", "next" to "Track 2", "prev" to "Track 3"), taps)
    }

    /** D67: a start that brings no data at all for 20 s pauses — it is not skipped, and the track stays. */
    @Test
    fun aLoadWithNoDataPausesAfter20sAndNeverSkips() {
        val journal = PlaybackJournal(now = { 0L })
        val controller = controller(journal = journal)
        controller.playTrack("1", playlist("1", "2"), PlaylistSource.LIBRARY)
        controller.settle()
        engine.calls.clear()

        passes(19_000)
        assertFalse("still loading at 19 s", "pause" in engine.names)
        passes(2_000)

        assertTrue("paused", "pause" in engine.names)
        assertEquals("not skipped", emptyList<Call>(), engine.played)
        assertEquals("1", controller.currentTrack.value?.idString)
        val pause = journal.state.value.events.last { it.kind == PlaybackJournal.Kind.PAUSE }
        assertEquals("no data 20 s", pause.detail)
    }

    /** D67: slowness never gives up — a trickle of data keeps the load going well past any old limit. */
    @Test
    fun aSlowLoadThatStillBringsDataKeepsLoading() {
        val controller = controller()
        controller.playTrack("1", playlist("1", "2"), PlaylistSource.LIBRARY)
        controller.settle()
        engine.calls.clear()

        repeat(40) {
            engine.buffered += 100
            passes(1_000)
        }

        assertFalse("never paused", "pause" in engine.names)
        assertEquals(emptyList<Call>(), engine.played)
        assertTrue(controller.isPlaying.value)
    }

    /** D67: the engine's "no data" for a transition pauses on the new track — no going back, no skip. */
    @Test
    fun theEnginesNoDataReportPausesWithoutSkipping() {
        val controller = controller()
        controller.playing("1", playlist("1", "2"))
        check(engine.playbackErrorEvent.tryEmit(PlaybackFailure("1", 0L, PlaybackFailure.NO_DATA)))
        controller.settle()

        assertEquals(listOf("pause"), engine.names.filter { it == "pause" || it == "playTrack" })
        assertEquals("1", controller.currentTrack.value?.idString)
    }

    /** D67: a network timeout after its retry pauses; a broken track (HTTP error) still skips. */
    @Test
    fun aNetworkTimeoutPausesAfterItsRetryInsteadOfSkipping() {
        val controller = controller()
        controller.playing("1", playlist("1", "2"))
        engine.fail("1", 5_000L, "ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT")
        controller.settle()
        // The fresh link times out too, before any sound
        engine.fail("1", 5_000L, "ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT")
        controller.settle()

        assertEquals("one retry of the same track, no skip", listOf("1"), engine.played.map { it.trackId })
        assertEquals("pause", engine.names.last())
    }

    /** R4: an output that stood still once in the run (media3's ERROR_CODE_TIMEOUT) stops the early standby for good. */
    @Test
    fun aStuckOutputTurnsTheEarlyStandbyOffForTheRestOfTheRun() {
        val controller = controller()
        controller.playing("1", playlist("1", "2", "3"))

        // While the run is healthy the next track is readied beside the playing one
        engine.prepareNextTrackEvent.tryEmit(Unit)
        controller.settle()
        assertEquals("the next track is readied beside the playing one", listOf("2"), engine.prepared)
        engine.calls.clear()

        engine.fail("1", 5_000L, PlaybackController.STUCK_OUTPUT)
        controller.settle()
        assertTrue("the readied next track is dropped", "dropStandby" in engine.names)

        // On to the next track: nothing is readied beside it any more
        engine.sounds()
        controller.playNext()
        controller.settle()
        assertEquals("2", controller.currentTrack.value?.idString)
        assertEquals("no early standby for the rest of the run", emptyList<String?>(), engine.prepared)
    }

    /** History's tap: the track goes right after the cursor and plays; the queue goes on after it (D35). */
    @Test
    fun playNowKeepQueuePutsTheTrackAfterTheCursorAndPlaysIt() {
        val controller = controller()
        controller.playing("1", playlist("1", "2", "3"))
        val nine = playlist("9").single()

        controller.playNowKeepQueue(nine)
        controller.settle()

        assertEquals(listOf("1", "9", "2", "3"), controller.currentPlaylist.value.ids)
        assertEquals(listOf("9"), engine.played.map { it.trackId })
        assertEquals("9", controller.currentTrack.value?.idString)
        assertEquals("the queue goes on after it", listOf("2"), engine.prepared)
    }

    /** With no queue at all the track starts one of its own, as "play next" does. */
    @Test
    fun playNowKeepQueueWithoutAQueueStartsOne() {
        val controller = controller()
        val nine = playlist("9").single()

        controller.playNowKeepQueue(nine)
        controller.settle()

        assertEquals(listOf("9"), controller.currentPlaylist.value.ids)
        assertEquals(listOf("9"), engine.played.map { it.trackId })
    }

    /** The history records what sounded, with its queue; a track skipped before it sounded is not listed. */
    @Test
    fun theHistoryListsTheTracksThatSoundedWithTheirQueue() {
        val controller = controller()
        controller.playing("1", playlist("1", "2", "3"))
        controller.playNext()
        controller.settle()
        controller.playNext() // "2" never rendered audio
        controller.settle()
        engine.sounds()

        val rows = await {
            history.awaitIdle()
            db.historyDao().recent(10).first()
        }
        assertEquals(listOf("3", "1"), rows.map { it.trackId })
        assertEquals(context.getString(R.string.queue_main_name), rows[0].queueName)
        assertEquals(controller.activeQueue.value?.queue?.id, rows[0].queueId)
    }

    @Test
    fun nextToAWarmTrackStartsAtOnceEvenWhenItsLinkIsNoLongerCached() {
        // 2026-11-15 head-unit log: "skip→request 1257 ms" after a skip to a warm next track — the old track
        // faded out at once, then the controller fetched the link again before handing the track to the engine
        val controller = controller()
        controller.playing("1", playlist("1", "2", "3"))
        engine.warm += "2"
        resolver.pending = CompletableDeferred() // a slow link: never answers in this test
        resolver.fetched.clear()

        controller.playNext()
        controller.settle()

        assertEquals("the engine gets the warm track without waiting for a link", listOf("2"), engine.played.map { it.trackId })
        assertFalse("no link fetch for the warm track", "2" in resolver.fetched)
        resolver.pending!!.complete(Unit)
    }

    @Test
    fun nextFadesTheOldTrackOutAndPreviousGoesBackOnlyWithinTheFirstThreeSeconds() {
        val controller = controller()
        controller.playing("1", playlist("1", "2", "3"))
        engine.warm += "2"

        controller.playNext()
        controller.settle()

        assertEquals("the next track is ready: the old one fades out now", "fadeOutForSkip", engine.names.first())
        assertEquals(listOf("2"), engine.played.map { it.trackId })
        assertFalse(engine.played.single().pick)

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
    fun nextToATrackStillToLoadKeepsTheOldOnePlayingUntilItIsReady() {
        val controller = controller()
        controller.playing("1", playlist("1", "2", "3"))

        controller.playNext()
        controller.settle()

        assertFalse("no fade into silence while the next one loads", "fadeOutForSkip" in engine.names)
        assertEquals(listOf(Call("playTrack", "2", resolver.url("2"), 0L, pick = true)), engine.played)
    }

    @Test
    fun aTrackPickedWhileOneSoundsLoadsFirstAndLeavesTheCurrentOneAlone() {
        val controller = controller()
        val list = playlist("1", "2", "3")
        controller.playing("1", list)

        // The car's next-track artwork, a queue row, a list: the user picks a track while one plays
        controller.playTrack("3", list, PlaylistSource.LIBRARY)
        controller.settle()

        assertFalse("the current track is not touched before the pick is ready", "fadeOutForSkip" in engine.names)
        assertEquals(listOf(Call("playTrack", "3", resolver.url("3"), 0L, pick = true)), engine.played)
    }

    @Test
    fun aPickThatFailsGoesBackToTheTrackThatPlaysOn() {
        val controller = controller()
        val list = playlist("1", "2", "3")
        controller.playing("1", list)
        controller.playTrack("3", list, PlaylistSource.LIBRARY)
        controller.settle()
        engine.calls.clear()

        // The engine gave the pick up and plays "1" on
        engine.currentTrackId.value = "1"
        check(engine.playbackErrorEvent.tryEmit(PlaybackFailure("3", 0L, PlaybackFailure.PICK_FAILED)))
        controller.settle()

        assertEquals("1", controller.currentTrack.value?.idString)
        assertTrue("no retry, no skip", engine.played.isEmpty())
    }

    @Test
    fun aTrackPickedWhilePausedIsAnOrdinaryStart() {
        val controller = controller()
        val list = playlist("1", "2", "3")
        controller.playing("1", list)
        engine.isPlaying.value = false

        controller.playTrack("2", list, PlaylistSource.LIBRARY)
        controller.settle()

        assertFalse("fadeOutForSkip" in engine.names)
        assertEquals(listOf(Call("playTrack", "2", resolver.url("2"), 0L, pick = false)), engine.played)
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
        await { store.save(PlaybackSnapshot(userFolder = "driver", source = "LIBRARY", queue = queue.map { it.toYandexTrack() }, currentTrackId = "2", positionMs = 7_000L)) }

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

    /** The provider says the link is gone (HTTP 404): no second try of the same track, the next one plays. */
    @Test
    fun aTrackTheServerSaysIsGoneIsSkippedWithoutARetry() {
        val controller = controller()
        controller.playTrack("1", playlist("1", "2", "3"), PlaylistSource.LIBRARY)
        controller.settle()
        engine.calls.clear()

        engine.fail("1", positionMs = 30_000, httpCode = 404)
        controller.settle()

        assertEquals(listOf("2"), engine.played.map { it.trackId })
    }

    /** A refused session (Jellyfin 401) stops playback with a message: never a retry, never a skip loop. */
    @Test
    fun aRefusedSessionStopsPlaybackInsteadOfSkipping() {
        val (controller, home, jellyfinTracks) = jellyfinSetup("controller_jellyfin_fatal") {
            if (it == 401) org.tegro.yamp.domain.provider.MidTrackAction.FATAL else org.tegro.yamp.domain.provider.MidTrackAction.RESTART
        }
        controller.playInAccount(home.id, jellyfinTracks, "j1")
        controller.settle()
        engine.calls.clear()

        engine.fail("j1", positionMs = 30_000, httpCode = 401)
        controller.settle()

        assertEquals("no retry, no next track", emptyList<String>(), engine.played.map { it.trackId })
        assertTrue("stopped", "stop" in engine.names)
        assertEquals("the track stays on screen", "j1", controller.currentTrack.value?.idString)
    }

    @Test
    fun aStreamThatBrokeOnTheStandbyIsAskedAfreshNextTime() {
        val controller = controller()
        controller.playing("1", playlist("1", "2"))

        // The engine never resolves (D74): it reports the spent link and the controller forgets it
        engine.staleStreamEvent.tryEmit("2")
        controller.settle()

        assertEquals(listOf("2"), resolver.evicted)
    }

    @Test
    fun aDownloadedTrackWhoseCopyIsGoneStreamsInstead() {
        val controller = controller()
        val queue = playlist("1", "2")
        resolver.copies += "1" // counted as downloaded, but its file is gone (the card was taken out)

        controller.playTrack("1", queue, PlaylistSource.LIBRARY)
        controller.settle()

        // The controller resolves (the engine used to): the network link, not an empty start
        assertEquals(listOf(Call("playTrack", "1", resolver.url("1"), 0L)), engine.played)
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
    fun undoingADislikeOfAQueuedTrackPutsItBackInItsPlace() {
        val controller = controller()
        controller.playing("1", playlist("1", "2", "3", "4"))

        val undo = controller.onTrackDisliked("3")
        controller.settle()
        assertEquals(listOf("1", "2", "4"), controller.currentPlaylist.value.ids)

        controller.undoDislike(undo)
        controller.settle()
        assertEquals(listOf("1", "2", "3", "4"), controller.currentPlaylist.value.ids)
        assertEquals("the playing track is not touched", "1", controller.currentTrack.value?.idString)
    }

    @Test
    fun undoingADislikeOfThePlayingTrackBringsItBackAndPlaysItAgain() {
        val controller = controller()
        controller.playing("2", playlist("1", "2", "3", "4"))

        val undo = controller.onTrackDisliked("2")
        controller.settle()
        assertEquals("the next one plays", "3", controller.currentTrack.value?.idString)
        assertEquals(listOf("1", "3", "4"), controller.currentPlaylist.value.ids)

        controller.undoDislike(undo)
        controller.settle()
        assertEquals("back in its place", listOf("1", "2", "3", "4"), controller.currentPlaylist.value.ids)
        assertEquals("and current again", "2", controller.currentTrack.value?.idString)
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
    fun anAlbumPlayedNextGoesRightAfterTheCurrentTrackInOrderAndAddedToEndGoesLast() {
        // Album / artist actions: "Play next" and "Add to end" take the whole list in one queue operation
        val controller = controller()
        controller.playing("1", playlist("1", "2", "3"))

        controller.playNextTracks(listOf(track("7"), track("8")))
        controller.settle()
        assertEquals(listOf("1", "7", "8", "2", "3"), controller.currentPlaylist.value.ids)

        controller.addToEnd(listOf(track("20"), track("21")))
        controller.settle()
        assertEquals(listOf("1", "7", "8", "2", "3", "20", "21"), controller.currentPlaylist.value.ids)
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

    /** The user's pause, play and seek are the smooth ones; a focus loss, a call and "no data" pause at once. */
    @Test
    fun theUsersPausePlayAndSeekAreSmoothAFocusLossIsNot() {
        val controller = controller()
        controller.playing("1", playlist("1", "2"))
        controller.settle()
        engine.smoothCalls.clear()

        controller.pause()
        controller.play()
        controller.seekTo(42_000L)
        controller.pause(reason = "audio focus lost")
        controller.settle()

        assertEquals(listOf("pause", "resume", "seek"), engine.smoothCalls.toList())
        assertEquals("the focus loss used the plain pause", 2, engine.names.count { it == "pause" })
    }

    /**
     * Canvas 5e: a converted stream before its disk copy is complete can't seek — a seek is refused with the message
     * (the copy's share), once per attempt, not a queue of them; once the copy is there it seeks again.
     */
    @Test
    fun aSeekWhileTheCopyIsNotCompleteIsRefusedWithTheShareLoaded() {
        val controller = controller()
        controller.playing("1", playlist("1", "2"))
        controller.settle()
        val messages = java.util.concurrent.CopyOnWriteArrayList<String>()
        val collecting = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch { controller.messages.collect { messages += it } }
        engine.calls.clear()

        engine.seekLock.value = 0.63f
        controller.seekTo(42_000L)
        controller.seekTo(50_000L)
        controller.settle()

        assertTrue("the position stays", engine.names.none { it == "seekTo" })
        assertEquals("one message for the burst", listOf(ApplicationProvider.getApplicationContext<Application>().getString(org.tegro.yamp.R.string.seek_locked_message, 63)), messages.toList())

        engine.seekLock.value = null
        controller.seekTo(42_000L)
        controller.settle()
        assertEquals(listOf(Call("seekTo", positionMs = 42_000L)), engine.calls.filter { it.name == "seekTo" })
        collecting.cancel()
    }

    /** Play with nothing loaded (a restored session) starts the track rising, not at full volume. */
    @Test
    fun playWithNothingLoadedAsksTheStartToRise() {
        val controller = controller()
        controller.playing("1", playlist("1", "2"))
        controller.settle()
        engine.currentTrackId.value = null
        engine.riseOnNextStartCalls = 0

        controller.play()
        controller.settle()

        assertEquals(1, engine.riseOnNextStartCalls)
    }

    /** The user's empty queue is never swapped for My Wave by a Play or Next (found on the head unit). */
    @Test
    fun playOnAnEmptyOwnQueueStaysThereInsteadOfStartingTheWave() {
        val manager = manager()
        val controller = controller(queueManager = manager)
        controller.playing("1", playlist("1", "2"))
        val empty = await { manager.create("RC") }
        controller.pause()
        controller.switchQueue(empty.id)
        controller.settle()
        engine.calls.clear()

        controller.play()
        controller.playNext()
        controller.settle()

        assertEquals("nothing played", emptyList<String>(), engine.played.map { it.trackId })
        assertEquals("still the empty queue", empty.id, controller.activeQueue.value?.queue?.id)
        assertEquals(org.tegro.yamp.domain.queue.QueueKind.STATIC, controller.activeQueue.value?.queue?.kind)
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

    // ---- radios (docs/phase-2-radio.md §4)

    private fun radioSeed(id: String) = RadioSeed(MediaRef(MediaRef.PROVIDER_YANDEX, MediaRef.KIND_TRACK, id), "Track $id")

    private fun PlaybackController.awaitPlaylist(size: Int) =
        await { currentPlaylist.first { it.size >= size } }.also { settle() }

    private val rotorRequests get() = requests.filter { it.contains("rotor/station/track:") }

    /** A radio by the playing track: it plays on, and the station's tracks follow it in a new RADIO queue. */
    @Test
    fun aRadioByThePlayingTrackPlaysItOnAndTheStationFillsTheQueue() {
        val controller = controller()
        val library = playlist("1", "2", "3")
        controller.playing("1", library)
        stations["track:1"] = ArrayDeque(listOf(listOf("r1", "r2", "r3"), listOf("r4", "r5")))

        controller.startRadio(radioSeed("1"), RadioTarget.New, keepHistory = false, track = library[0])
        controller.awaitPlaylist(4)

        val radio = controller.queues.value.single { it.queue.kind == QueueKind.RADIO }
        assertTrue(radio.isActive)
        assertEquals("Radio: Track 1", radio.queue.name)
        assertEquals("yandex:track:1", radio.queue.seed)
        assertEquals("1", controller.currentPlaylist.value.first().idString)
        assertEquals("the seed track plays on, nothing restarts", emptyList<String>(), engine.played.map { it.trackId })
        assertTrue(controller.currentPlaylist.value.ids.containsAll(listOf("r1", "r2", "r3")))
    }

    /** Skipping in a radio tells its own station; an inactive radio is never asked for anything. */
    @Test
    fun aRadioSendsFeedbackToItsStationAndAnInactiveOneMakesNoRequests() {
        val controller = controller()
        val library = playlist("1", "2", "3")
        controller.playing("1", library)
        val libraryQueue = controller.queues.value.single { it.queue.kind == QueueKind.STATIC }.queue.id
        stations["track:1"] = ArrayDeque(List(5) { n -> List(5) { "r$n$it" } })
        controller.startRadio(radioSeed("1"), RadioTarget.New, keepHistory = false, track = library[0])
        controller.awaitPlaylist(4)

        controller.playNext()
        controller.settle()
        await { while (rotorRequests.none { it == "POST rotor/station/track:1/feedback" }) kotlinx.coroutines.delay(10) }

        controller.switchQueue(libraryQueue)
        controller.settle()
        requests.clear()
        controller.playNext()
        controller.settle()
        controller.playNext()
        controller.settle()
        await { kotlinx.coroutines.delay(200) }
        assertEquals("an inactive radio does nothing", emptyList<String>(), rotorRequests)
    }

    /** Re-seeding a radio keeps the queue and drops its tracks; the name follows the new seed (D51). */
    @Test
    fun reSeedingARadioReplacesItsTracksInTheSameQueue() {
        val controller = controller()
        val library = playlist("1", "2", "3")
        controller.playing("1", library)
        stations["track:1"] = ArrayDeque(listOf(listOf("r1", "r2", "r3")))
        controller.startRadio(radioSeed("1"), RadioTarget.New, keepHistory = false, track = library[0])
        controller.awaitPlaylist(4)
        val radioId = controller.queues.value.single { it.queue.kind == QueueKind.RADIO }.queue.id
        stations["track:2"] = ArrayDeque(listOf(listOf("s1", "s2", "s3")))

        controller.startRadio(radioSeed("2"), RadioTarget.Replace(radioId), keepHistory = true, track = library[1])
        controller.settle()
        await { controller.currentPlaylist.first { "s1" in it.ids } }
        controller.settle()

        val radios = controller.queues.value.filter { it.queue.kind == QueueKind.RADIO }
        assertEquals(1, radios.size)
        assertEquals(radioId, radios.single().queue.id)
        assertEquals("Radio: Track 2", radios.single().queue.name)
        assertTrue(radios.single().queue.keepHistory)
        assertEquals("2", controller.currentPlaylist.value.first().idString)
        assertFalse("the old radio's tracks are gone", controller.currentPlaylist.value.ids.any { it.startsWith("r") })
    }

    // region Phase 2a: queues bound to accounts (D56, D57, D61, D63)

    private val second = org.tegro.yamp.domain.model.UserAccount(uid = "2002", login = "rock", token = "token2")

    /** Every account's calls reach the test server; which account asked is written down. */
    private inner class RecordingClients : org.tegro.yamp.data.network.YandexClients {
        val asked = java.util.concurrent.CopyOnWriteArrayList<String?>()
        override fun api(account: org.tegro.yamp.domain.account.AccountId?): YandexMusicApi = api.also { asked += account?.value }
        override fun current(): YandexMusicApi = api
        override fun http(account: org.tegro.yamp.domain.account.AccountId?): okhttp3.OkHttpClient = client
        override fun evictCaches() = Unit
    }

    /** A queue made with no account given belongs to the account the user is in. */
    @Test
    fun aNewQueueIsBoundToTheAccountTheUserIsIn() {
        val controller = controller()
        controller.createQueue("Road")
        controller.settle()

        assertEquals("yandex:1001", controller.queues.value.single { it.queue.name == "Road" }.queue.account)
    }

    /** "New queue" with an account chosen in its selector binds the queue to that account (D57). */
    @Test
    fun aNewQueueTakesTheAccountChosenForIt() {
        auth.allAccounts.value = listOf(auth.activeAccount.value!!, second)
        val controller = controller()
        controller.createQueue("Rock", account = "yandex:2002")
        controller.createQueueWith("Rock 2", playlist("1"), account = "yandex:2002")
        controller.settle()

        assertEquals("yandex:2002", controller.queues.value.single { it.queue.name == "Rock" }.queue.account)
        assertEquals("yandex:2002", controller.queues.value.single { it.queue.name == "Rock 2" }.queue.account)
    }

    /** "New radio" on the account chosen in the picker: the radio belongs to it, whatever plays now (D57). */
    @Test
    fun aNewRadioTakesTheAccountChosenForIt() {
        auth.allAccounts.value = listOf(auth.activeAccount.value!!, second)
        val controller = controller()
        val library = playlist("1", "2", "3")
        controller.playing("1", library)
        stations["track:1"] = ArrayDeque(listOf(listOf("r1", "r2", "r3")))

        controller.startRadio(radioSeed("1"), RadioTarget.New, keepHistory = false, track = library[0], account = "yandex:2002")
        controller.settle()

        assertEquals("yandex:2002", controller.queues.value.single { it.queue.kind == QueueKind.RADIO }.queue.account)
    }

    /** Making a queue of another account active takes the user into that account; playback is not reset. */
    @Test
    fun switchingToAQueueOfAnotherAccountSwitchesTheContext() {
        auth.allAccounts.value = listOf(auth.activeAccount.value!!, second)
        val manager = manager()
        val controller = controller(queueManager = manager)
        controller.playing("1", playlist("1", "2"))
        val rock = await { manager.create("Rock", account = "yandex:2002") }

        controller.switchQueue(rock.id)
        controller.settle()

        assertEquals("2002", auth.activeAccount.value?.uid)
        controller.switchQueue(controller.queues.value.single { it.queue.account == "yandex:1001" && it.queue.name != "Rock" }.queue.id)
        controller.settle()
        assertEquals("1001", auth.activeAccount.value?.uid)
    }

    /** A radio refills under its own account, even when another account's queue was active before it. */
    @Test
    fun aRadioRefillsUnderItsOwnAccount() {
        auth.allAccounts.value = listOf(auth.activeAccount.value!!, second)
        val clients = RecordingClients()
        val manager = manager()
        val controller = controller(queueManager = manager, clients = clients)
        val library = playlist("1", "2", "3")
        controller.playing("1", library)
        stations["track:9"] = ArrayDeque(listOf(listOf("r1", "r2", "r3"), listOf("r4", "r5")))
        val radio = await { manager.startRadio(RadioTarget.New, "yandex:track:9", "Radio: Track 9", false, playlist("9"), account = "yandex:2002") }
        clients.asked.clear()

        controller.switchQueue(radio.id, play = true)
        controller.awaitPlaylist(4)

        assertTrue(rotorRequests.any { it.contains("rotor/station/track:9/tracks") })
        assertTrue("the radio asks as its account", clients.asked.contains("yandex:2002"))
        assertFalse("never as the account in force before it", clients.asked.contains("yandex:1001"))
    }

    /** Re-seeding a radio keeps its account (D61). */
    @Test
    fun reSeedingARadioKeepsItsAccount() {
        auth.allAccounts.value = listOf(auth.activeAccount.value!!, second)
        val manager = manager()
        controller(queueManager = manager)
        val radio = await { manager.startRadio(RadioTarget.New, "yandex:track:1", "Radio: Track 1", false, emptyList(), account = "yandex:2002") }

        val reSeeded = await { manager.startRadio(RadioTarget.Replace(radio.id), "yandex:track:2", "Radio: Track 2", false, emptyList(), account = "yandex:1001") }

        assertEquals("yandex:2002", reSeeded.account)
    }

    /** Signing in gives the account its My Wave queue and takes the user there; signing in again reuses it. */
    @Test
    fun enteringAnAccountMakesItsMyWaveQueueOnceAndActivatesIt() {
        auth.allAccounts.value = listOf(auth.activeAccount.value!!, second)
        val controller = controller()
        controller.playing("1", playlist("1", "2"))
        val account = org.tegro.yamp.domain.account.AccountId.yandex("2002")

        controller.ensureWaveQueue(account)
        controller.enterAccount(account)
        controller.settle()
        controller.enterAccount(account)
        controller.settle()

        val waves = controller.queues.value.filter { it.queue.kind == QueueKind.WAVE && it.queue.account == "yandex:2002" }
        assertEquals(1, waves.size)
        assertTrue(waves.single().isActive)
        assertEquals("2002", auth.activeAccount.value?.uid)
    }

    // endregion

    // region Sign-out (Phase 2a step 7, D62)

    private val anna = org.tegro.yamp.domain.account.AccountId.yandex("1001")
    private val rockId = org.tegro.yamp.domain.account.AccountId.yandex("2002")

    /** Playing a queue of the second account at 12 s, with both accounts' My Wave queues made. */
    private fun PlaybackController.playingRock(manager: QueueManager): Long {
        playing("1", playlist("1", "2"))
        ensureWaveQueue(anna)
        ensureWaveQueue(rockId)
        settle()
        val rock = await { manager.create("Rock", account = rockId.value) }
        addToQueue(rock.id, playlist("7", "8"))
        settle()
        switchQueue(rock.id, play = true)
        settle()
        engine.sounds()
        engine.progress.value = 12_000
        engine.calls.clear()
        return rock.id
    }

    @Test
    fun signingOutStopsTheAccountsQueueKeepsItAndMovesToTheDefaultAccountsMyWave() {
        auth.allAccounts.value = listOf(auth.activeAccount.value!!, second)
        val manager = manager()
        val controller = controller(queueManager = manager)
        val rock = controller.playingRock(manager)

        controller.leaveAccount(rockId, fallback = anna)
        controller.settle()

        assertTrue("the engine stops", engine.calls.contains(Call("stop")))
        assertFalse(controller.isPlaying.value)
        val now = controller.activeQueue.value!!.queue
        assertEquals("the default account's My Wave, as the grouped switcher lists it first", QueueKind.WAVE, now.kind)
        assertEquals(anna.value, now.account)
        val kept = await { db.queueDao().queue(rock) }!!
        assertEquals("the queue is kept with its position", 12_000L, kept.positionMs)
        assertEquals(rockId.value, kept.account)
        assertEquals(2, controller.queues.value.single { it.queue.id == rock }.size)
        assertEquals("its My Wave is kept too", 1, controller.queues.value.count { it.queue.kind == QueueKind.WAVE && it.queue.account == rockId.value })
    }

    @Test
    fun aRemovedAccountsQueueSavedAsActiveGivesWayOnTheNextStart() {
        val annaAccount = auth.activeAccount.value!!
        auth.allAccounts.value = listOf(annaAccount, second)
        val first = controller(queueManager = manager())
        val rock = first.playingRock(manager())
        first.persistNow()
        first.settle()
        // The second account is gone by the next start (signed out elsewhere, or its sign-out was cut short)
        auth.allAccounts.value = listOf(annaAccount)
        auth.activeAccount.value = annaAccount

        val restarted = controller(FakePlayerRepository(context))

        val now = await { restarted.activeQueue.first { it?.queue?.account == anna.value } }!!.queue
        assertEquals("the default account's My Wave, as on a sign-out", QueueKind.WAVE, now.kind)
        assertFalse(restarted.isPlaying.value)
        val kept = await { db.queueDao().queue(rock) }!!
        assertEquals("the removed account's queue is kept for its re-sign-in", rockId.value, kept.account)
    }

    @Test
    fun theLastAccountSigningOutLeavesNoQueueActiveAndDeletesNone() {
        val manager = manager()
        val controller = controller(queueManager = manager)
        controller.playing("1", playlist("1", "2"))
        val main = controller.activeQueue.value!!.queue.id

        controller.leaveAccount(anna, fallback = null)
        controller.settle()

        assertNull(controller.activeQueue.value)
        assertEquals(2, controller.queues.value.single { it.queue.id == main }.size)
    }

    @Test
    fun anotherAccountsQueuePlaysOnWhenAnAccountSignsOut() {
        auth.allAccounts.value = listOf(auth.activeAccount.value!!, second)
        val controller = controller()
        controller.playing("1", playlist("1", "2"))
        val main = controller.activeQueue.value!!.queue.id

        controller.leaveAccount(rockId, fallback = anna)
        controller.settle()

        assertEquals(main, controller.activeQueue.value?.queue?.id)
        assertFalse(engine.calls.contains(Call("stop")))
        assertTrue(controller.isPlaying.value)
    }

    @Test
    fun signingBackInBringsTheQueuesBackAtTheirPositionsWithoutANewMyWave() {
        auth.allAccounts.value = listOf(auth.activeAccount.value!!, second)
        val manager = manager()
        val controller = controller(queueManager = manager)
        val rock = controller.playingRock(manager)
        controller.leaveAccount(rockId, fallback = anna)
        controller.settle()

        controller.ensureWaveQueue(rockId)
        controller.switchQueue(rock)
        controller.settle()

        assertEquals(1, controller.queues.value.count { it.queue.kind == QueueKind.WAVE && it.queue.account == rockId.value })
        assertEquals("7", controller.currentTrack.value?.idString)
        assertEquals(12_000L, await { controller.progress.first { it == 12_000L } })
    }

    // endregion

    @Test
    fun whileTheAudioRouterRoutesTransitionsAreHardCuts() {
        val routed = kotlinx.coroutines.flow.MutableStateFlow(false)
        val router = object : AudioRouterWatch {
            override val installed = true
            override val active = routed
        }
        val journal = PlaybackJournal(now = { 0L })
        controller(journal = journal, audioRouter = router)
        await { }
        assertEquals("the user's crossfade applies", 3_000L, engine.crossfadeMs)
        routed.value = true
        await { }
        assertEquals("hard cuts while routed", 0L, engine.crossfadeMs)
        assertTrue(journal.state.value.events.any { it.kind == PlaybackJournal.Kind.ROUTER && it.detail == "active → hard cuts" })
        routed.value = false
        await { }
        assertEquals("back to the user's crossfade", 3_000L, engine.crossfadeMs)
    }
}
