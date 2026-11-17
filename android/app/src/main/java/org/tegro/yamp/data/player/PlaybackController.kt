package org.tegro.yamp.data.player

import org.tegro.yamp.data.provider.yandex.toTrack
import org.tegro.yamp.domain.media.Track
import org.tegro.yamp.data.provider.yandex.toYandexTracks
import kotlinx.coroutines.withContext
import org.tegro.yamp.data.account.AccountContext
import org.tegro.yamp.data.account.SignOutPlan
import org.tegro.yamp.domain.account.AccountId
import org.tegro.yamp.domain.account.accountId
import org.tegro.yamp.domain.model.WaveTuning
import kotlinx.coroutines.flow.distinctUntilChanged
import org.tegro.yamp.utils.prefetchCovers
import org.tegro.yamp.utils.CoverSize
import org.tegro.yamp.data.provider.yandex.reserve.RoadReserve
import org.tegro.yamp.data.provider.yandex.reserve.ReservePolicy
import org.tegro.yamp.data.provider.yandex.reserve.ReserveKeeper
import org.tegro.yamp.utils.toCoverUrl
import org.tegro.yamp.R
import androidx.core.net.toUri
import android.content.Context
import android.util.Log
import android.widget.Toast
import org.tegro.yamp.data.cache.CacheDao
import org.tegro.yamp.data.cache.CacheDatabase
import org.tegro.yamp.data.provider.yandex.cache.PlaylistCacheManager
import org.tegro.yamp.data.cache.ValueEntity
import org.tegro.yamp.data.network.TrackDownloadManager
import org.tegro.yamp.data.network.TrackUrlResolver
import org.tegro.yamp.data.network.YandexClients
import org.tegro.yamp.data.provider.yandex.network.YandexMusicApi
import org.tegro.yamp.data.queue.ActiveQueue
import org.tegro.yamp.data.provider.yandex.queue.LegacySessionImport
import org.tegro.yamp.data.queue.QueueManager
import org.tegro.yamp.data.provider.yandex.queue.RotorStationSource
import org.tegro.yamp.data.provider.yandex.queue.RotorStations
import org.tegro.yamp.data.provider.radio.FmRadio
import org.tegro.yamp.domain.model.RadioStation
import org.tegro.yamp.domain.queue.QueueKind
import org.tegro.yamp.domain.queue.QueuePolicy
import org.tegro.yamp.domain.queue.QueuePolicy.Advance
import org.tegro.yamp.domain.queue.QueueSummary
import org.tegro.yamp.domain.queue.MediaRef
import org.tegro.yamp.domain.queue.RadioSeed
import org.tegro.yamp.domain.provider.ResolvedStream
import org.tegro.yamp.domain.provider.getOrNull
import org.tegro.yamp.domain.queue.RadioTarget
import org.tegro.yamp.domain.queue.RepeatMode
import org.tegro.yamp.domain.queue.TrackRef
import org.tegro.yamp.domain.queue.fitsQueueOf
import org.tegro.yamp.domain.repository.AuthRepository
import org.tegro.yamp.domain.repository.PlaybackFailure
import org.tegro.yamp.domain.repository.PlayerRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import retrofit2.HttpException
import java.io.IOException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.tegro.yamp.domain.player.NextTrack
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.plus
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import org.tegro.yamp.data.provider.yandex.wave.WaveQueue
import org.tegro.yamp.data.provider.yandex.wave.RecentlyPlayed

/**
 * Makes every playback decision (next/previous, My Wave refill, error recovery) over the queues that
 * [QueueManager] owns: the controller reads the active queue and asks the manager for changes, it never holds
 * a queue of its own (docs/phase-1-queues.md §4). It is a process-wide singleton, so notification, headset and
 * track-end events keep working after the Activity and its ViewModel are destroyed (e.g. the task is swiped away).
 */
@Singleton
class PlaybackController @Inject internal constructor(
    @param:ApplicationContext private val context: Context,
    private val clients: YandexClients,
    private val authRepository: AuthRepository,
    private val playerRepository: PlayerRepository,
    private val downloadManager: TrackDownloadManager,
    private val preloadManager: TrackUrlResolver,
    private val cacheManager: PlaylistCacheManager,
    private val fmRadio: FmRadio,
    private val queueManager: QueueManager,
    private val legacyImport: LegacySessionImport,
    private val cacheDao: CacheDao,
    private val settingsRepository: org.tegro.yamp.domain.repository.SettingsRepository,
    private val networkMonitor: org.tegro.yamp.data.network.NetworkMonitor,
    private val reserve: RoadReserve,
    private val journal: PlaybackJournal,
    private val diagnosticLog: org.tegro.yamp.data.diagnostics.DiagnosticLog,
    private val history: org.tegro.yamp.data.history.ListeningHistory,
    private val accountContext: AccountContext,
    /** Streams are resolved here, never in the engine (D74): it plays what it is handed. */
    private val streams: org.tegro.yamp.data.provider.StreamResolvers,
    /** The next track's whole file is fetched to disk ahead of time (D86). */
    private val nextFetch: NextTrackFetch
) {
    /** The account context's API (the active queue's account, D56): My Wave and its tuning. */
    private val api: YandexMusicApi get() = clients.api(contextAccount)

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "Unhandled playback coroutine error", e)
        }
    )

    /**
     * The queue operations run one after another, in call order, so each one reads the active queue as the
     * one before left it (two quick presses on "next" move on twice). The manager's calls suspend; a user
     * action is one operation.
     */
    private var lastQueueOp: Job? = null

    /** An FM station on air (D41): a fake track beside the active queue, which stays as it is meanwhile. */
    private val liveStation = MutableStateFlow<Track?>(null)

    /** The active queue as the manager holds it right now; the operations read this, never the derived flows. */
    private val active: ActiveQueue? get() = queueManager.active.value

    /** Every queue for the switcher, and the active one with its entries. */
    val queues: StateFlow<List<QueueSummary>> = queueManager.queues
    val activeQueue: StateFlow<ActiveQueue?> = queueManager.active

    /**
     * The active queue's tracks, its cursor track (or the station on air) and where the track comes from, as the
     * callers have always read them. Mirrored from the manager's state the moment it changes (on the writing
     * thread, undispatched), so nothing that runs after a queue operation reads a value the mirror has not reached.
     */
    private val _currentPlaylist = MutableStateFlow<List<Track>>(emptyList())
    /** The active queue's tracks: the screens, Android Auto, the session and the tests read it. */
    val currentPlaylist: StateFlow<List<Track>> = _currentPlaylist.asStateFlow()

    private val _currentTrack = MutableStateFlow<Track?>(null)
    /** The cursor track (or the station on air); see [currentPlaylist]. */
    val currentTrack: StateFlow<Track?> = _currentTrack.asStateFlow()

    private val _currentSource = MutableStateFlow(PlaylistSource.NONE)
    val currentSource: StateFlow<PlaylistSource> = _currentSource.asStateFlow()

    init {
        queueManager.active.onEach { refreshDerivedState() }.launchIn(scope + Dispatchers.Unconfined)
        liveStation.onEach { refreshDerivedState() }.launchIn(scope + Dispatchers.Unconfined)
    }

    /** Playback was requested but the stream URL is still being resolved. */
    private val _isStarting = MutableStateFlow(false)
    val isStarting: StateFlow<Boolean> = _isStarting.asStateFlow()

    private val sleep = SleepTimer(
        scope = scope,
        fadeOutAndPause = { playerRepository.fadeOutAndPause(it) },
        onFinished = { persistSession() }
    )
    val sleepTimer: StateFlow<SleepTimerState?> get() = sleep.state

    /** Real stream quality of the current track ("MP3 · 320 кбит/с"), null for radio or while unknown. */
    private val _streamQuality = MutableStateFlow<String?>(null)
    val streamQuality: StateFlow<String?> = _streamQuality.asStateFlow()

    /** Position of a restored (not yet loaded) session, shown until the player takes over. */
    private val restoredPositionMs = MutableStateFlow(0L)

    /** Play/Pause icon state: true while playing, buffering, or resolving the next stream. */
    val isPlaying: StateFlow<Boolean> = combine(playerRepository.isPlaying, _isStarting) { playing, starting ->
        playing || starting
    }.stateIn(scope, SharingStarted.Eagerly, false)

    val isBuffering: StateFlow<Boolean> = combine(playerRepository.isBuffering, _isStarting, playerRepository.soundingTrackId) { buffering, starting, sounding ->
        // A start that waits behind a track still sounding (the crossfade's hold) is not "loading" on screen
        buffering || (starting && sounding == null)
    }.stateIn(scope, SharingStarted.Eagerly, false)

    /** The track whose whole file is on disk (a copy, or the stream cache had all of it): its bar is full. */
    private val onDiskTrackId = MutableStateFlow<String?>(null)

    /** The last next track whose whole file went to disk: when it starts, its bar is full from the first second. */
    @Volatile private var lastOnDiskId: String? = null

    /**
     * How far the current track is loaded, for the lighter part of the progress bars (D81). [Long.MAX_VALUE]
     * when its whole file is on disk (the bars clamp it to the duration).
     */
    val bufferedProgress: StateFlow<Long> =
        combine(playerRepository.bufferedProgress, onDiskTrackId, playerRepository.currentTrackId) { buffered, onDisk, current ->
            if (onDisk != null && onDisk == current) Long.MAX_VALUE else buffered
        }.stateIn(scope, SharingStarted.Eagerly, 0L)

    val progress: StateFlow<Long> = combine(
        playerRepository.progress,
        playerRepository.currentTrackId,
        restoredPositionMs
    ) { position, loadedId, restored ->
        if (loadedId == null) restored else position
    }.stateIn(scope, SharingStarted.Eagerly, 0L)

    private val recentPlayedTrackIds = RecentlyPlayed(RECENT_LIMIT)

    // Declared before the wave and the init blocks below: both may read them at once
    private val skipExplicit = settingsRepository.skipExplicit.stateIn(scope, SharingStarted.Eagerly, false)

    private val _waveSeed = MutableStateFlow<Track?>(null)
    /** The track a "wave by track" was started from; null while My Wave (or anything else) plays. */
    val waveSeed: StateFlow<Track?> = _waveSeed.asStateFlow()

    private fun setWaveSeed(seed: Track?) {
        _waveSeed.value = seed
    }

    private val _waveTuning = MutableStateFlow(WaveTuning())
    /** The current station's character, mood and language. */
    val waveTuning: StateFlow<WaveTuning> = _waveTuning.asStateFlow()

    private val _trackWaveStarted = kotlinx.coroutines.flow.MutableSharedFlow<Track>(extraBufferCapacity = 1)
    /** A wave by a track has just started: the screen offers to undo it. */
    val trackWaveStarted: kotlinx.coroutines.flow.Flow<Track> = _trackWaveStarted

    /** Where the music was when a wave by a track started: the queue, its cursor entry and the position. */
    private data class BeforeTrackWave(
        val queueId: Long?,
        val entryId: Long?,
        val positionMs: Long,
        val seed: Track?
    )

    private var beforeTrackWave: BeforeTrackWave? = null

    init {
        // Volume normalization: the gains of the queued tracks, applied as each one plays
        combine(currentPlaylist, settingsRepository.normalizeVolume) { queue, on ->
            if (!on) emptyMap() else queue.mapNotNull { track -> track.loudness?.integratedLufs?.let { track.idString to Loudness.gainFor(it) } }.toMap()
        }.distinctUntilChanged().onEach { playerRepository.setTrackGains(it) }.launchIn(scope)
    }

    /**
     * "Моя волна": the rotor fetching, its de-dup and the preview the wave page shows. What it fetches joins
     * the account's WAVE queue, which is the wave the user plays; the wave's own list stays for the preview.
     */
    private val wave = WaveQueue(
        apiOf = { api },
        cacheManager = cacheManager,
        preloadManager = preloadManager,
        scope = scope,
        recent = recentPlayedTrackIds,
        isOffline = ::isOffline,
        userFolder = { activeUserFolder },
        keep = { !(skipExplicit.value && it.isExplicit) },
        // Appended to the wave queue as it is now: tracks the user removed or moved stay that way
        onFresh = { fresh -> queueOp { queueManager.appendFetched(waveQueueId(), fresh.map { it.toTrack() }) } }
    )
    /**
     * The active radio's source (docs/phase-2-radio.md §4): made for the active RADIO queue when first needed,
     * dropped on a re-seed; a batch that lands after a switch or re-seed is discarded.
     */
    private var radio: RotorStationSource? = null
    private var radioQueueId: Long? = null
    private var radioFetch: Deferred<Boolean>? = null
    private val radioFetching = MutableStateFlow(false)

    val isFetchingMoreQueue: StateFlow<Boolean> = combine(wave.isFetching, radioFetching) { w, r -> w || r }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** What the wave will play when it is started; shown on the wave page while the wave is not playing. */
    val wavePreview: StateFlow<List<Track>> = wave.preview.map { list -> list.map { it.toTrack() } }
        .stateIn(scope, SharingStarted.Eagerly, wave.preview.value.map { it.toTrack() })
    private var libraryTracks: List<Track> = emptyList()

    private val link = LinkTracker(android.os.SystemClock::elapsedRealtime)
    private val _linkState = MutableStateFlow(LinkState.GOOD)
    /** How the music gets through right now: the "Слабая связь" and "Нет связи" labels. */
    val linkState: StateFlow<LinkState> = _linkState.asStateFlow()

    /** The track now coming from a stream: only its sound proves the link is good again. */
    private var streamingTrackId: String? = null
    private var playingCopyId: String? = null
    /** Nothing on the device to play: the music continues by itself once the connection is back. */
    private var waitingForConnection = false
    /** What the music left for a mix of what is on the device, to go back to once the connection is good. */
    private var returnPoint: ReturnPoint? = null

    /** The queue and entry the music left; [wave] when it was My Wave that was asked for. */
    private class ReturnPoint(val queueId: Long?, val leftEntryId: Long?, val wave: Boolean)

    /** The app-made queue the offline mix plays in, so the queue the music left stays as it was (D35). */
    private var offlineMixQueueId: Long? = null

    private val reserveKeeper = ReserveKeeper(
        scope = scope,
        reserve = reserve,
        limitMb = settingsRepository.roadReserveMb,
        mayFill = combine(
            playerRepository.isPlaying,
            _linkState,
            currentTrack,
            authRepository.isAuthorized()
        ) { playing, state, track, signedIn ->
            playing && signedIn && state == LinkState.GOOD && track?.isRadio != true
        },
        resolve = { trackId -> preloadManager.resolveOrdinaryStream(trackId, playbackAccount) },
        // The reserve fills only while the playing track has sound in hand: the two downloads must not compete
        streamNeedsTheLine = {
            isBuffering.value || playerRepository.inTransition || progress.value < TRACK_SETTLE_MS ||
                playerRepository.bufferedAheadMs < RESERVE_HEADROOM_MS
        },
        upcoming = { upcomingTracks().toYandexTracks() },
        moreTracks = { wave.tracks.value },
        isDownloaded = preloadManager::hasPlayableCopy,
        fetchMore = { wave.fetchMoreAwait() },
        onStored = { prefetchCovers(context, listOf(it.toTrack()), listOf(CoverSize.Small, CoverSize.Medium, CoverSize.Large)) }
    )

    /** The track History has open as sounding, and since when (elapsed time), for its played time. */
    private var heard: String? = null
    private var heardSince = 0L

    private var currentPlayJob: Job? = null
    private var bufferingWatchdog: Job? = null
    private var stallWatchdog: Job? = null
    /** Incremented on every explicit start/stop so stale async callbacks can tell they were superseded. */
    private var startRequestId = 0L
    private var retriedTrackId: String? = null
    private var consecutiveFailedTracks = 0
    /** Completes once the saved session (if any) has been loaded from the queue store. */
    private val sessionRestored = kotlinx.coroutines.CompletableDeferred<Unit>()

    /** The account the user is in: the active queue's (D56), else the active account. */
    private val contextAccount: AccountId? get() = accountContext.current()

    private val activeUserFolder: String
        get() = contextAccount?.value ?: "default"

    /**
     * The account the playing track's link is resolved under (docs/phase-2a-accounts.md §4): the playing queue's
     * — the active one — while its account is signed in.
     */
    private val playbackAccount: AccountId?
        get() = jellyfinQueueAccount() ?: contextAccount

    /** [playbackAccount] for the Jellyfin play reports (step 26). */
    val playingAccount: AccountId? get() = playbackAccount

    /**
     * The active queue's account when it is a signed-in Jellyfin account (step 23): its tracks resolve through
     * its own provider, never through the Yandex context (D84). Yandex queues keep the context rule above.
     */
    private fun jellyfinQueueAccount(): AccountId? = active?.queue?.account
        ?.let(::AccountId)
        ?.takeIf { it.provider == org.tegro.yamp.domain.account.Provider.JELLYFIN && accountContext.signedInAnyProvider(it.value) }

    /** Whether [track] may go into the queue of [account] — a queue holds its own account's tracks (D84). */
    private fun fits(track: Track, account: String?): Boolean = track.ref.fitsQueueOf(account?.let(::AccountId))

    /** [tracks] that may go into the active queue (D84); all of them while there is no queue. */
    private fun fittingActive(tracks: List<Track>): List<Track> =
        active?.queue?.let { queue -> tracks.filter { fits(it, queue.account) } } ?: tracks

    /** Whether [account] is a Yandex account (or the Yandex context): the service-only helpers apply to it. */
    private fun isYandex(account: AccountId?): Boolean = account?.provider != org.tegro.yamp.domain.account.Provider.JELLYFIN

    /** The ref of a track the engine names by id: from the active queue (any provider), else a Yandex track. */
    private fun refOf(trackId: String): TrackRef =
        active?.tracks?.firstOrNull { it.idString == trackId }?.ref ?: TrackRef.yandex(trackId)

    /** The account a queue made now is bound to (D57): the one the user is in. */
    private val accountForNew: String? get() = contextAccount?.value

    private val mainName: String get() = context.getString(R.string.queue_main_name)
    private val waveName: String get() = context.getString(R.string.queue_my_wave_name)

    private val _nextTrack = MutableStateFlow<NextTrack>(NextTrack.None)

    /** The next track's state (D80): what the Next button shows — loading, ready (warm or on disk), failed. */
    val nextTrack: StateFlow<NextTrack> = _nextTrack.asStateFlow()

    init {
        playerRepository.trackEndedEvent.onEach { onTrackEnded() }.launchIn(scope)
        watchTheNextTrack()
        // A queue of another account became active: the user is now in that account (D56)
        queueManager.active.map { it?.queue?.account }.distinctUntilChanged().onEach { followQueueAccount(it) }.launchIn(scope)
        playerRepository.prepareNextTrackEvent.onEach { prepareNextTrackInQueue() }.launchIn(scope)
        playerRepository.skipNextEvent.onEach { playNext() }.launchIn(scope)
        playerRepository.skipPrevEvent.onEach { playPrev() }.launchIn(scope)
        playerRepository.playbackErrorEvent.onEach { onPlaybackFailure(it) }.launchIn(scope)
        // A player failed on a stream (a standby, a fading tail): its link is spent, the next start asks afresh
        playerRepository.staleStreamEvent.onEach { streams.forAccount(playbackAccount).invalidate(refOf(it)) }.launchIn(scope)
        settingsRepository.crossfadeSeconds
            .onEach { playerRepository.setCrossfadeDuration(it * 1000L) }
            .launchIn(scope)

        // The track that actually renders audio: it resets error recovery, and it is what History records (D46) —
        // a track skipped before it sounded is not listed, and the time it sounded is what is counted
        combine(playerRepository.soundingTrackId, playerRepository.isBuffering, playerRepository.currentTrackId) { sounding, buffering, current ->
            sounding?.takeIf { it == current && !buffering }
        }
            .distinctUntilChanged()
            .onEach { audible ->
                if (heard != null) {
                    history.stopped(android.os.SystemClock.elapsedRealtime() - heardSince)
                    heard = null
                }
                if (audible == null) return@onEach
                _isStarting.value = false
                consecutiveFailedTracks = 0
                retriedTrackId = null
                val track = currentTrackNow()
                if (streamingTrackId != null && streamingTrackId == track?.idString) updateLink { onStreamAudible() }
                if (track != null && track.idString == audible && !track.isRadio) {
                    heard = audible
                    heardSince = android.os.SystemClock.elapsedRealtime()
                    history.started(track, active?.queue)
                }
            }
            .launchIn(scope)

        // The player stopped wanting to play with a track still loaded (focus loss during a call, a Bluetooth
        // unplug, a manual pause): no start is in flight any more. Clearing this flag stops the "loading" spinner
        // from sticking and lets play()/resume() run again afterwards (it early-returns while a start is pending).
        playerRepository.isPlaying
            .onEach { wantsToPlay ->
                if (!wantsToPlay && playerRepository.currentTrackId.value != null) _isStarting.value = false
            }
            .launchIn(scope)

        // Watchdog: a stream that never finishes buffering (a dead link that reports no error) would spin forever.
        // After a timeout, treat it as a failure so the usual retry/skip recovery kicks in instead of a stuck UI.
        isBuffering
            .onEach { buffering ->
                bufferingWatchdog?.cancel()
                stallWatchdog?.cancel()
                if (buffering) {
                    val trackId = currentTrackNow()?.idString
                    stallWatchdog = scope.launch {
                        delay(LINK_STALL_MS)
                        val track = currentTrackNow()
                        if (isBuffering.value && track != null && track.idString == trackId && !track.isRadio) onStreamStalled(track)
                    }
                    // Slowness never skips (D67): the load goes on while data arrives; only no data at all for
                    // NO_DATA_TIMEOUT_MS pauses playback, so the user hears and sees that it stopped
                    bufferingWatchdog = scope.launch {
                        var stillFor = 0L
                        var lastBuffered = playerRepository.bufferedPositionMs()
                        while (true) {
                            delay(NO_DATA_CHECK_MS)
                            if (!isBuffering.value || trackId == null || currentTrackNow()?.idString != trackId) return@launch
                            val buffered = playerRepository.bufferedPositionMs()
                            if (buffered > lastBuffered) {
                                lastBuffered = buffered
                                stillFor = 0L
                            } else {
                                stillFor += NO_DATA_CHECK_MS
                            }
                            if (stillFor >= org.tegro.yamp.data.repository.PlayerRepositoryImpl.NO_DATA_TIMEOUT_MS) {
                                Log.w(TAG, "No data for $trackId in ${stillFor / 1000} s: pausing")
                                pauseForNoData()
                                return@launch
                            }
                        }
                    }
                }
            }
            .launchIn(scope)

        keepWaveShelfFilled()
        keepRadioShelfFilled()
        watchTheLink()
        reserveKeeper.start()

        // Persist the position while playing so a killed process resumes close to where it stopped.
        scope.launch {
            while (true) {
                delay(SNAPSHOT_INTERVAL_MS)
                if (playerRepository.isPlaying.value) persistSession()
            }
        }

        // The first queue operation: everything the user asks for waits for the restore
        queueOp {
            try {
                loadUserState(activeUserFolder, restoreSession = true)
            } finally {
                sessionRestored.complete(Unit)
            }
        }
    }

    // region Public API

    fun updateLibrary(tracks: List<Track>) {
        libraryTracks = tracks
    }

    /**
     * Plays [trackId] out of [playlist]. The same list as the active queue (the queue sheet's rows) moves the
     * cursor to its first entry with that track; any other list replaces the queue (E5). Kept for the screens
     * that still hand in lists; the queue sheet will pass entry ids to [playEntry].
     */
    fun playTrack(
        trackId: String,
        playlist: List<Track>,
        source: PlaylistSource? = null,
        crossfade: Boolean = true,
        startPositionMs: Long = 0L
    ) {
        val tapped = playlist.firstOrNull { it.idString == trackId } ?: return
        // A merged list (Search, step 24) mixes services: a Jellyfin track plays in its own account's queue (D84)
        accountContext.accountOf(tapped.ref)?.takeIf { active == null || !fits(tapped, active?.queue?.account) }?.let { account ->
            playInAccount(account, playlist.filter { it.ref.provider == tapped.ref.provider }, trackId)
            return
        }
        val own = playlist.filter { it.ref.provider == tapped.ref.provider }
        if (own.size != playlist.size) {
            playTrack(trackId, own, source, crossfade, startPositionMs)
            return
        }
        dropFailedNextLink(trackId)
        // A context chosen on purpose: there is nothing to go back to any more
        if (source != null) returnPoint = null
        pickOp(playlist.firstOrNull { it.idString == trackId }?.title) {
            val queue = active
            if (queue != null && queue.tracks.map { it.idString } == playlist.map { it.idString }) {
                val entry = queue.entries.firstOrNull { it.ref.id == trackId } ?: return@pickOp
                startEntry(entry.entryId, crossfade, startPositionMs)
            } else {
                playFromNow(playlist, source?.name ?: queue?.queue?.origin, trackId, crossfade, startPositionMs)
            }
        }
    }

    /** Replaces the active queue with [tracks] (E5) and plays [startId], or the first one. */
    fun playFrom(tracks: List<Track>, source: PlaylistSource, startId: String?) {
        if (tracks.isEmpty()) return
        returnPoint = null
        pickOp((tracks.firstOrNull { it.idString == startId } ?: tracks.first()).title) {
            playFromNow(tracks, source.name, startId, crossfade = true, startPositionMs = 0L)
        }
    }

    /**
     * Plays [tracks] of [account] (a Jellyfin page, step 23) from [startId] in that account's own queue (D84): the
     * active queue when it is the account's, else its latest own queue, else its default one. Never another
     * account's queue, so the tracks resolve through their own provider.
     */
    fun playInAccount(account: AccountId, tracks: List<Track>, startId: String?) {
        if (tracks.isEmpty()) return
        returnPoint = null
        val start = tracks.firstOrNull { it.idString == startId } ?: tracks.first()
        pickOp(start.title) {
            val key = account.value
            val current = active?.queue
            val target = current?.takeIf { it.account == key && it.kind == QueueKind.STATIC }?.id
                ?: queueManager.queues.value.map { it.queue }
                    .filter { it.account == key && it.kind == QueueKind.STATIC }
                    .maxByOrNull { it.updatedAt }?.id
                ?: queueManager.defaultQueue(key, mainName).id
            if (current?.id != target) queueManager.switchTo(target, progress.value.takeIf { liveStation.value == null })
            val queue = queueManager.replaceActive(tracks, PlaylistSource.SEARCH.name, start.ref, nameIfNew = mainName, accountIfNew = key)
            val entry = queue.cursorEntry ?: return@pickOp
            startEntry(entry.entryId, crossfade = true, startPositionMs = 0L)
        }
    }

    /** Moves the cursor to [entryId] of the active queue and plays it: the queue sheet's row, duplicates included. */
    fun playEntry(entryId: Long) {
        returnPoint = null
        pickOp(active?.let { q -> q.tracks.getOrNull(q.entries.indexOfFirst { it.entryId == entryId })?.title }) {
            startEntry(entryId, crossfade = true, startPositionMs = 0L)
        }
    }

    /**
     * "Play now, keep my queue" (History's tap): [track] goes right after the cursor of the active queue and
     * plays; the queue goes on after it. Never replaces a queue (D35) — with none, it starts a new one.
     */
    fun playNowKeepQueue(track: Track) {
        // A track of another account (History's Jellyfin row while a Yandex queue plays): its own queue (D84)
        if (active != null && !fits(track, active?.queue?.account)) {
            accountContext.accountOf(track.ref)?.let { playInAccount(it, listOf(track), track.idString) }
                ?: playTrack(track.idString, listOf(track), PlaylistSource.LIBRARY)
            return
        }
        returnPoint = null
        pickOp(track.title) {
            val queue = active
            if (queue == null) {
                playFromNow(listOf(track), PlaylistSource.LIBRARY.name, track.idString, crossfade = true, startPositionMs = 0L)
                return@pickOp
            }
            queueManager.insertAfterCursor(listOf(track))
            val entry = active?.entries?.getOrNull(queue.cursorIndex + 1) ?: return@pickOp
            startEntry(entry.entryId, crossfade = true, startPositionMs = 0L)
        }
    }

    /** Starts the wave from a track of its preview (the wave page's "next up" shelf). */
    fun playWaveFrom(trackId: String) {
        if (wave.tracks.value.none { it.idString == trackId }) {
            playMyVibe()
            return
        }
        returnPoint = null
        pickOp(wave.tracks.value.firstOrNull { it.idString == trackId }?.title) {
            playWaveFromNow(trackId)
        }
    }

    /** Returns once the saved session (track, queue, position) is back, so "continue" has something to continue. */
    suspend fun awaitRestoredSession() = sessionRestored.await()

    /**
     * Ends every coroutine of the controller and waits for them. The app never calls it (the singleton lives as
     * long as the process); the tests do, so a controller of one test leaves nothing running into the next.
     */
    suspend fun shutdown() = scope.coroutineContext.job.cancelAndJoin()

    /** Waits for the queue operations asked for so far. Like [shutdown], only the tests need it. */
    suspend fun awaitQueueIdle() {
        lastQueueOp?.join()
    }

    fun playMyVibe() {
        returnPoint = null
        pickOp("My Wave") {
            playMyVibeNow()
        }
    }

    fun playAllLikedTracks(shuffle: Boolean = false) {
        val playlist = if (shuffle) libraryTracks.shuffled() else libraryTracks
        if (playlist.isNotEmpty()) playFrom(playlist, PlaylistSource.LIBRARY, playlist.first().idString)
    }

    fun playHits(hits: List<Track>, shuffle: Boolean = true) {
        val hitsList = if (shuffle) hits.shuffled() else hits
        if (hitsList.isEmpty()) return
        val trackToPlay = hitsList.firstOrNull { it.idString !in recentPlayedTrackIds } ?: hitsList.first()
        playFrom(hitsList, PlaylistSource.TRENDS, trackToPlay.idString)
    }

    /** A station goes on air beside the active queue, which stays as it is; anything played from a queue ends it. */
    fun playRadioStation(station: RadioStation) {
        returnPoint = null
        pickOp(station.name) {
            val radioTrack = radioTrackFor(station)
            liveStation.value = radioTrack
            restoredPositionMs.value = 0L
            waitingForConnection = false
            scope.launch { cacheDao.putValue(ValueEntity(CacheDatabase.SHARED, LIVE_STATION, station.id)) }
            resolveAndPlay(radioTrack, crossfade = true, startPositionMs = 0L, pickAt = takePick())
        }
    }

    // region Queues: switching, modes, adding

    /**
     * Makes [id] the active queue: shown paused at its saved track and position; keeps playing if the player
     * was, or starts it when [play] asks (a queue picked in Android Auto).
     */
    fun switchQueue(id: Long, play: Boolean = false) = playOp { switchQueueNow(id, play) }

    private suspend fun switchQueueNow(id: Long, play: Boolean) {
        if (active?.queue?.id == id) {
            if (play && !isPlaying.value) this.play()
            return
        }
        val wasPlaying = isPlaying.value || play
        val position = progress.value.takeIf { liveStation.value == null }
        currentPlayJob?.cancel()
        _isStarting.value = false
        queueManager.switchTo(id, position)
        playerRepository.stop()
        leaveStation()
        val target = active ?: return
        restoredPositionMs.value = target.queue.positionMs
        followWaveStation(target)
        val entry = target.cursorEntry ?: target.entries.firstOrNull() ?: return
        if (wasPlaying) startEntry(entry.entryId, crossfade = false, startPositionMs = if (entry == target.cursorEntry) target.queue.positionMs else 0L)
    }

    /** Shuffle keeps the current track where it is and can be switched off again. */
    fun setShuffle(on: Boolean) = queueOp { keepingStandby { queueManager.setShuffle(on) } }

    fun setRepeat(mode: RepeatMode) = queueOp { queueManager.setRepeat(mode) }

    /** "Add to queue…": to the end of any queue, the active one included. */
    fun addToQueue(queueId: Long, tracks: List<Track>) = queueOp {
        val account = queueManager.queues.value.firstOrNull { it.queue.id == queueId }?.queue?.account
        tracks.filter { fits(it, account) }.takeIf { it.isNotEmpty() }?.let { queueManager.appendTo(queueId, it) }
    }

    /** "Add to end of queue": the active one, created when there is none. */
    fun addToEnd(tracks: List<Track>) = queueOp {
        val fitting = fittingActive(tracks).ifEmpty { return@queueOp }
        val queueId = active?.queue?.id ?: queueManager.create(mainName, account = accountForNew).id.also { queueManager.switchTo(it, null) }
        queueManager.appendTo(queueId, fitting)
    }

    /** "Play next" for a list: right after the current track. */
    fun playNextTracks(tracks: List<Track>) = queueOp {
        if (active == null) playFromNow(tracks, null, null, crossfade = true, startPositionMs = 0L)
        else fittingActive(tracks).takeIf { it.isNotEmpty() }?.let { queueManager.insertAfterCursor(it) }
    }

    /** The switcher's edits, each one a queue operation in call order; nothing to wait for. */
    /** [account]: the one chosen in "New queue" (D57); null = the active queue's account. */
    fun createQueue(name: String, account: String? = null) = queueOp { queueManager.create(name, account = account ?: accountForNew) }

    /** A new queue that at once gets [tracks] ("New queue" in the "Add to queue…" picker). */
    fun createQueueWith(name: String, tracks: List<Track>, account: String? = null) = queueOp {
        val owner = account ?: accountForNew
        val fitting = tracks.filter { fits(it, owner) }.ifEmpty { return@queueOp }
        queueManager.appendTo(queueManager.create(name, account = owner).id, fitting)
    }

    fun renameQueue(queueId: Long, name: String) = queueOp { queueManager.rename(queueId, name) }

    /** Deleting the active queue: the next one becomes active, shown paused at its cursor (open question 5). */
    fun deleteQueue(queueId: Long) = playOp {
        val wasActive = active?.queue?.id == queueId
        if (wasActive) {
            currentPlayJob?.cancel()
            _isStarting.value = false
            playerRepository.stop()
            leaveStation()
        }
        queueManager.delete(queueId)
        if (wasActive) {
            val target = active ?: return@playOp
            restoredPositionMs.value = target.queue.positionMs
            followWaveStation(target)
        }
    }

    fun duplicateQueue(queueId: Long, name: String) = queueOp { queueManager.duplicate(queueId, name) }

    /** Clearing the active queue stops what plays from it; the queue stays, empty. */
    fun clearQueue(queueId: Long) = playOp {
        if (active?.queue?.id == queueId) {
            currentPlayJob?.cancel()
            _isStarting.value = false
            playerRepository.stop()
            restoredPositionMs.value = 0L
        }
        queueManager.clear(queueId)
    }

    fun reorderQueues(idsInOrder: List<Long>) = queueOp { queueManager.reorderQueues(idsInOrder) }

    fun removeEntry(entryId: Long) = queueOp { keepingStandby { queueManager.remove(entryId) } }

    /** Drag in the queue: [entryId] takes the place of [targetEntryId]. The current entry never moves. */
    fun moveEntry(entryId: Long, targetEntryId: Long) = queueOp {
        val cursorId = active?.cursorEntry?.entryId
        if (entryId == cursorId || targetEntryId == cursorId) return@queueOp
        keepingStandby { queueManager.move(entryId, targetEntryId) }
    }

    // endregion

    /**
     * Changes with every play, pause or stop the controller carries out. A resume after a focus loss (D66) only
     * happens while it still holds the value it had right after that loss's own pause: the user acted otherwise.
     */
    @Volatile
    var userIntentEpoch = 0L
        private set

    fun togglePlayPause() {
        if (isPlaying.value) pause() else play()
    }

    fun play() {
        userIntentEpoch++
        val current = currentTrackNow()
        if (current == null) {
            // A queue with entries but no cursor starts from its first one. An empty queue of the user's own stays
            // the active one (D35: never touched by the app) and says so; only no queue at all starts the wave.
            val queue = active
            val first = queue?.entries?.firstOrNull()
            when {
                first != null -> playEntry(first.entryId)
                queue != null && queue.queue.kind == QueueKind.STATIC -> showMessage(context.getString(R.string.msg_queue_empty, queue.queue.name))
                else -> playMyVibe()
            }
            return
        }
        // A start is already in flight (e.g. a second Play from a headset): don't restart it
        if (_isStarting.value) return
        if (playerRepository.currentTrackId.value == current.idString && playerRepository.resume()) return
        // Nothing loaded (restored session or stopped player): start the current track where it was left.
        resolveAndPlay(current, crossfade = false, startPositionMs = restoredPositionMs.value)
    }

    fun pause(reason: String = "user") {
        userIntentEpoch++
        journal.record(PlaybackJournal.Kind.PAUSE, currentTrackNow()?.title, reason)
        waitingForConnection = false
        // A start that hasn't produced sound yet is cancelled, also one still waiting for its queue operation
        startRequestId++
        currentPlayJob?.cancel()
        _isStarting.value = false
        playerRepository.pause()
        persistNow()
    }

    /** No data for a long while (D67): pause once, with the reason in the journal. Play continues the same load. */
    private fun pauseForNoData() {
        if (!playerRepository.isPlaying.value && !_isStarting.value) return
        pause(reason = "no data ${org.tegro.yamp.data.repository.PlayerRepositoryImpl.NO_DATA_TIMEOUT_MS / 1000} s")
    }

    fun seekTo(positionMs: Long) {
        if (playerRepository.currentTrackId.value == null) {
            restoredPositionMs.value = positionMs
        } else {
            playerRepository.seekTo(positionMs)
        }
    }

    fun playNext(isAuto: Boolean = false) {
        val tapAt = android.os.SystemClock.elapsedRealtime()
        if (!isAuto) journal.record(PlaybackJournal.Kind.TAP, currentTrackNow()?.title, "next")
        if (!isAuto) _nextTrack.value.trackId?.let { dropFailedNextLink(it) }
        queueOp {
            try {
                playNextNow(isAuto, tapAt)
            } finally {
                userPickAt = 0L // not taken: nothing started
            }
        }
    }

    /**
     * A tap on a failed next track — Next, or its artwork in the car — is its retry (D81): the spent link goes,
     * so the skip resolves it afresh (still load-first, D67).
     */
    private fun dropFailedNextLink(trackId: String) {
        val failed = _nextTrack.value as? NextTrack.Failed ?: return
        if (failed.trackId != trackId) return
        streams.forAccount(playbackAccount).invalidate(refOf(trackId))
        journal.record(PlaybackJournal.Kind.NEXT, null, "retry after: ${failed.reason}")
    }

    fun playPrev() {
        journal.record(PlaybackJournal.Kind.TAP, currentTrackNow()?.title, "prev")
        queueOp { playPrevNow() }
    }

    fun loadMoreQueue() {
        when (active?.queue?.kind) {
            QueueKind.WAVE -> wave.fetchMore()
            QueueKind.RADIO -> fetchRadio()
            else -> Unit
        }
    }

    fun reorderQueue(from: Int, to: Int) = queueOp {
        val queue = active ?: return@queueOp
        val entry = queue.entries.getOrNull(from) ?: return@queueOp
        val target = queue.entries.getOrNull(to) ?: return@queueOp
        keepingStandby { queueManager.move(entry.entryId, target.entryId) }
    }

    /** Puts [track] right after the current one; with nothing (or a station) playing it just starts. */
    fun enqueueNext(track: Track) = queueOp {
        // Never into another account's queue (D84); the menu hides "Play next" then
        if (active != null && !fits(track, active?.queue?.account)) return@queueOp
        val queue = active
        if (queue == null) {
            playFromNow(listOf(track), null, track.idString, crossfade = true, startPositionMs = 0L)
            return@queueOp
        }
        val current = currentTrackNow()
        if (current == null || current.isRadio) {
            queueManager.insertAfterCursor(listOf(track))
            active?.entries?.getOrNull(queue.cursorIndex + 1)?.let { startEntry(it.entryId, crossfade = true, startPositionMs = 0L) }
            return@queueOp
        }
        if (current.idString == track.idString) return@queueOp
        queueManager.insertAfterCursor(listOf(track))
    }

    /** Removes the first entry with [trackId] after the cursor, else any other than the cursor's. */
    fun removeFromQueue(trackId: String) = queueOp {
        val queue = active ?: return@queueOp
        val entry = queue.entries.drop(queue.cursorIndex + 1).firstOrNull { it.ref.id == trackId }
            ?: queue.entries.firstOrNull { it.ref.id == trackId && it.entryId != queue.cursorEntry?.entryId }
            ?: return@queueOp
        keepingStandby { queueManager.remove(entry.entryId) }
    }

    /** Drag in the queue: [trackId] takes the place of [targetId]. The current track never moves. */
    fun moveInQueue(trackId: String, targetId: String) = queueOp {
        val queue = active ?: return@queueOp
        val currentId = queue.cursorTrack?.idString
        if (trackId == currentId || targetId == currentId) return@queueOp
        val entry = queue.entries.firstOrNull { it.ref.id == trackId } ?: return@queueOp
        val target = queue.entries.firstOrNull { it.ref.id == targetId } ?: return@queueOp
        keepingStandby { queueManager.move(entry.entryId, target.entryId) }
    }

    /** Runs a queue change; when what follows the cursor changed, that entry's track is prepared instead. */
    private suspend fun keepingStandby(change: suspend () -> Unit) {
        val before = active
        change()
        val after = active ?: return
        if (liveStation.value != null || after.cursorTrack == null) return
        val nextBefore = before?.entries?.getOrNull(before.cursorIndex + 1)
        val nextAfter = after.entries.getOrNull(after.cursorIndex + 1) ?: return
        if (nextAfter.entryId != nextBefore?.entryId) prepareStandby(after.tracks[after.cursorIndex + 1])
    }

    /** Dislike behaves like Yandex Music: the current track is skipped right away and leaves the queue. */
    fun onTrackDisliked(trackId: String) {
        wave.remove(trackId)
        queueOp {
            if (currentTrackNow()?.idString == trackId) playNextNow(isAuto = false)
            keepingStandby {
                active?.entries?.filter { it.ref.id == trackId }?.forEach { queueManager.remove(it.entryId) }
            }
        }
    }

    /** Stops the engine; the queue stays, its position goes back to the start (nothing is deleted, D35). */
    fun stopAndClear() {
        userIntentEpoch++
        startRequestId++
        currentPlayJob?.cancel()
        _isStarting.value = false
        playerRepository.stop()
        restoredPositionMs.value = 0L
        waitingForConnection = false
        returnPoint = null
        leaveStation()
        queueOp { queueManager.savePosition(0L) }
    }

    private val _accountEntered = MutableSharedFlow<AccountId>(extraBufferCapacity = 4)

    /** The account whose wave state and lists are loaded; null until the first active queue is seen. */
    private var followedAccount: String? = null

    /** The user moved into another signed-in account (a queue of it became active): its lists are to be shown. */
    val accountEntered: SharedFlow<AccountId> = _accountEntered.asSharedFlow()

    /**
     * Takes the user into [account] (D56): its My Wave queue becomes active, paused — unless a queue of it already
     * is. The account switcher and a fresh sign-in land here; there is no switch of an account on its own.
     */
    fun enterAccount(account: AccountId, onlyWhenNoQueue: Boolean = false) = playOp {
        if (onlyWhenNoQueue && active != null) return@playOp
        val waveId = queueManager.waveQueue(account.value, waveName).id
        if (active?.queue?.account == account.value) followQueueAccount(account.value) else switchQueueNow(waveId, play = false)
    }

    /** A Jellyfin account's default queue exists from sign-in on (D83); an account with queues already keeps them. */
    fun ensureDefaultQueue(account: AccountId, name: String) = queueOp { queueManager.defaultQueue(account.value, name) }

    /** The account's My Wave queue exists from sign-in on (D63); an existing one (same uid) is kept. */
    fun ensureWaveQueue(account: AccountId) = queueOp { queueManager.waveQueue(account.value, waveName) }

    /**
     * The active queue's account is the one the user is in: the active account follows it, so the screens show
     * that account's likes and playlists, and the wave state is that account's. Playback is not touched.
     */
    private fun followQueueAccount(account: String?) {
        val target = accountContext.signedIn(account) ?: return
        val user = authRepository.getAllAccounts().firstOrNull { it.accountId() == target } ?: return
        val previous = followedAccount
        followedAccount = target.value
        val activeUid = authRepository.getActiveAccount()?.uid
        // The restored session already is in the active account; the same account again changes nothing
        if (previous == null && activeUid == user.uid || previous == target.value) return
        if (activeUid != user.uid) authRepository.switchAccount(user.uid)
        recentPlayedTrackIds.clear()
        wave.switchTo(WaveQueue.MY_WAVE)
        setWaveSeed(null)
        _waveTuning.value = WaveTuning()
        libraryTracks = emptyList()
        queueOp {
            loadUserState(target.value, restoreSession = false)
            active?.let { followWaveStation(it) }
        }
        _accountEntered.tryEmit(target)
    }

    /**
     * Sign-out of [account] (D62): its queues stay as they are — entries, track and position — hidden until the same
     * account signs in again; nothing of them plays. When the active queue is one of them, the engine stops and the
     * first queue of [fallback] (its My Wave first, as the grouped switcher lists it) becomes active, paused; with no
     * account left, none. A queue of another account playing meanwhile is not touched.
     */
    fun leaveAccount(account: AccountId, fallback: AccountId?) = playOp {
        if (active?.queue?.account != account.value) return@playOp
        val position = progress.value.takeIf { liveStation.value == null }
        startRequestId++
        currentPlayJob?.cancel()
        _isStarting.value = false
        playerRepository.stop()
        waitingForConnection = false
        returnPoint = null
        leaveStation()
        // The reserve was saved under the leaving account's subscription
        reserve.clear()
        // A later sign-in to this account loads its state again, even as the first account entered
        followedAccount = null
        recentPlayedTrackIds.clear()
        wave.switchTo(WaveQueue.MY_WAVE)
        setWaveSeed(null)
        _waveTuning.value = WaveTuning()
        libraryTracks = emptyList()
        enterFallback(fallback, position)
    }

    /**
     * Makes the first queue of [fallback] (its My Wave first, as the grouped switcher lists it) active, paused; with
     * no account, none. [position] is saved on the queue being left.
     */
    private suspend fun enterFallback(fallback: AccountId?, position: Long?) {
        if (fallback == null) {
            queueManager.leave(position)
            restoredPositionMs.value = 0L
            return
        }
        val target = queueManager.queues.value
            .filter { it.queue.account == fallback.value }
            .sortedWith(compareBy<QueueSummary>({ it.queue.kind != QueueKind.WAVE }, { it.queue.sortOrder }))
            .firstOrNull()?.queue?.id
            ?: queueManager.waveQueue(fallback.value, waveName).id
        queueManager.switchTo(target, position)
        val now = active ?: return
        restoredPositionMs.value = now.queue.positionMs
        followWaveStation(now)
    }

    /**
     * The restored active queue belongs to an account that is no longer signed in (D62: its queues wait hidden):
     * the same fallback as a sign-out, so a removed account's queue never comes back as the playing one.
     */
    private suspend fun leaveStrandedQueue() {
        val account = active?.queue?.account ?: return
        if (accountContext.signedInAnyProvider(account)) return
        val fallback = SignOutPlan.fallback(authRepository.getAllAccounts(), authRepository.getDefaultAccountUid())
        enterFallback(fallback, position = null)
    }

    /** A queue as stored, without making it active: a removed account's queue looked at (D62). */
    suspend fun peekQueue(queueId: Long): ActiveQueue? = queueManager.peek(queueId)

    fun clearWaveAndHistory() {
        wave.clear()
        recentPlayedTrackIds.clear()
        queueOp { queueManager.clear(waveQueueId()) }
    }

    /** Silent My Wave refresh on launch: append fresh unplayed tracks and warm their URLs. */
    fun primeWave() {
        wave.fetchMore()
        loadWaveTuning()
    }

    // region Wave tuning and a wave by a track

    private fun loadWaveTuning() {
        val station = wave.station
        if (authRepository.getToken().isNullOrEmpty() || isOffline()) return
        scope.launch(Dispatchers.IO) {
            val settings = runCatching { api.getStationInfo(station).result?.firstOrNull()?.settings2 }
                .onFailure { Log.w(TAG, "Wave settings not loaded", it) }
                .getOrNull() ?: return@launch
            if (wave.station == station) _waveTuning.value = WaveTuning.fromWire(settings.diversity, settings.moodEnergy, settings.language)
        }
    }

    /**
     * Saves the station's settings on the server, then the wave goes on with tracks that fit them: the
     * current track plays on, what comes after it is fetched anew.
     */
    fun setWaveTuning(tuning: WaveTuning) {
        val before = _waveTuning.value
        if (tuning == before) return
        _waveTuning.value = tuning
        val station = wave.station
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    api.setStationSettings(
                        station,
                        kotlinx.serialization.json.buildJsonObject {
                            put("diversity", kotlinx.serialization.json.JsonPrimitive(tuning.diversity.wire))
                            put("moodEnergy", kotlinx.serialization.json.JsonPrimitive(tuning.mood.wire))
                            put("language", kotlinx.serialization.json.JsonPrimitive(tuning.language.wire))
                            put("type", kotlinx.serialization.json.JsonPrimitive("rotor"))
                        }
                    )
                }.onFailure { Log.w(TAG, "Wave settings not saved", it) }.isSuccess
            }
            if (!saved) {
                if (_waveTuning.value == tuning) _waveTuning.value = before
                showMessage(context.getString(R.string.wave_tuning_failed))
                return@launch
            }
            if (wave.station == station) restartWaveAfterCurrent()
        }
    }

    /** The wave starts over after the current track: same station, fresh tracks. */
    private fun restartWaveAfterCurrent() {
        wave.switchTo(wave.station)
        queueOp {
            val queue = active
            if (queue?.queue?.kind == QueueKind.WAVE && liveStation.value == null) {
                queueManager.restartDynamic(queue.queue.id, queue.queue.seed, keepUpToCursor = true, tracks = emptyList())
                dropNext()
            }
            wave.fetchMore()
        }
    }

    /**
     * A wave by [track]: the track plays (on, if it already does) and similar ones follow, inside the WAVE
     * queue (D40). What played before is kept, so [undoTrackWave] can go back to it.
     */
    fun startTrackWave(track: Track) {
        if (track.isRadio || isOffline()) {
            if (isOffline()) showMessage(context.getString(R.string.msg_offline_no_downloads))
            return
        }
        returnPoint = null
        playOp { startTrackWaveNow(track) }
    }

    private suspend fun startTrackWaveNow(track: Track) {
        val left = active
        val current = currentTrackNow()
        beforeTrackWave = BeforeTrackWave(left?.queue?.id, left?.cursorEntry?.entryId, progress.value, _waveSeed.value)
        wave.switchTo(WaveQueue.trackStation(track.idString))
        setWaveSeed(track)
        _waveTuning.value = WaveTuning()
        val waveId = waveQueueId()
        if (left?.queue?.id != waveId) queueManager.switchTo(waveId, progress.value.takeIf { left != null && liveStation.value == null })
        val playsOn = liveStation.value == null && current?.idString == track.idString
        // The wave queue keeps what it played up to its cursor; the seed track follows unless it is that cursor
        val seedIsCursor = playsOn && active?.cursorTrack?.idString == track.idString
        queueManager.restartDynamic(waveId, seed = track.idString, keepUpToCursor = true, tracks = if (seedIsCursor) emptyList() else listOf(track))
        val target = active?.entries?.lastOrNull() ?: return
        if (playsOn) {
            queueManager.moveCursor(target.entryId, progress.value)
            dropNext()
            wave.fetchMore()
        } else {
            startEntry(target.entryId, crossfade = true, startPositionMs = 0L)
        }
        loadWaveTuning()
        _trackWaveStarted.tryEmit(track)
    }

    /** Back to My Wave; the current track plays on. */
    fun returnToMyWave() {
        if (_waveSeed.value == null) return
        setWaveSeed(null)
        beforeTrackWave = null
        queueOp {
            val current = currentTrackNow()?.takeIf { !it.isRadio }
            val saved = cacheManager.loadWaveQueue(activeUserFolder).filterNot { it.idString == current?.idString }
            wave.switchTo(WaveQueue.MY_WAVE, saved)
            _waveTuning.value = WaveTuning()
            loadWaveTuning()
            val waveId = waveQueueId()
            queueManager.restartDynamic(waveId, seed = null, keepUpToCursor = true, tracks = saved.map { it.toTrack() })
            if (active?.queue?.id == waveId && liveStation.value == null) {
                dropNext()
                prepareNextTrackInQueue()
            }
            if (saved.size < 10) wave.fetchMore()
            persistSession()
        }
    }

    /** "Undo" right after a wave by a track started: what played before comes back. */
    fun undoTrackWave() {
        val before = beforeTrackWave ?: return
        beforeTrackWave = null
        if (before.seed != null) {
            // It was a wave by another track: go back to that one, with nothing further to undo
            startTrackWave(before.seed)
            queueOp { beforeTrackWave = null }
            return
        }
        setWaveSeed(null)
        playOp { undoTrackWaveNow(before) }
    }

    private suspend fun undoTrackWaveNow(before: BeforeTrackWave) {
        val current = currentTrackNow()
        val waveId = waveQueueId()
        wave.switchTo(WaveQueue.MY_WAVE, cacheManager.loadWaveQueue(activeUserFolder))
        _waveTuning.value = WaveTuning()
        loadWaveTuning()
        queueManager.restartDynamic(waveId, seed = null, keepUpToCursor = true, tracks = wave.tracks.value.map { it.toTrack() })
        val leftQueue = before.queueId
        if (leftQueue != null && leftQueue != waveId && active?.queue?.id == waveId) queueManager.switchTo(leftQueue, progress.value)
        val queue = active ?: return
        val previous = before.entryId?.let { id -> queue.entries.firstOrNull { it.entryId == id } }
        if (previous != null && previous.ref.id != current?.idString) {
            // Started from a menu on another track: the track that played before comes back where it was
            startEntry(previous.entryId, crossfade = true, startPositionMs = before.positionMs)
        } else {
            if (previous != null) queueManager.moveCursor(previous.entryId, progress.value)
            dropNext()
            prepareNextTrackInQueue()
            persistSession()
        }
    }

    /**
     * "Start radio" (D51): [seed]'s radio in a new queue or an existing radio re-seeded, then played — the seed
     * track first (on, if it already sounds), for an album or artist seed the first fetched track.
     */
    /** [account]: a new radio's account from the picker's selector (D57); null = the active queue's account. */
    fun startRadio(seed: RadioSeed, target: RadioTarget, keepHistory: Boolean, track: Track? = null, account: String? = null) {
        if (isOffline()) {
            showMessage(context.getString(R.string.msg_offline_no_downloads))
            return
        }
        returnPoint = null
        playOp { startRadioNow(seed, target, keepHistory, track?.takeIf { seed.ref.kind == MediaRef.KIND_TRACK && !it.isRadio }, account) }
    }

    /** [startRadio] with the seed track as a screen showed it. */
    fun startRadioFrom(seed: RadioSeed, target: RadioTarget, keepHistory: Boolean, track: Track?, account: String? = null) =
        startRadio(seed, target, keepHistory, track, account)

    private suspend fun startRadioNow(seed: RadioSeed, target: RadioTarget, keepHistory: Boolean, track: Track?, account: String? = null) {
        val left = active
        val current = currentTrackNow()
        val playsOn = liveStation.value == null && track != null && current?.idString == track.idString
        dropRadioSource()
        val name = context.getString(R.string.radio_queue_name, seed.title)
        val radioQueue = queueManager.startRadio(target, seed.ref.value, name, keepHistory, listOfNotNull(track), account = account ?: accountForNew)
        if (left?.queue?.id != radioQueue.id) {
            queueManager.switchTo(radioQueue.id, progress.value.takeIf { left != null && liveStation.value == null })
        }
        showMessage(context.getString(R.string.radio_started, seed.title))
        val first = active?.entries?.firstOrNull()
        when {
            first != null && playsOn -> {
                queueManager.moveCursor(first.entryId, progress.value)
                dropNext()
                fetchRadio()
            }
            first != null -> startEntry(first.entryId, crossfade = true, startPositionMs = 0L)
            else -> awaitFirstRadioBatch(radioQueue.id)
        }
    }

    /** An album or artist radio starts empty: the loading state until its first batch, which then plays. */
    private fun awaitFirstRadioBatch(queueId: Long) {
        val request = ++startRequestId
        currentPlayJob?.cancel()
        playerRepository.stop()
        leaveStation()
        _isStarting.value = true
        fetchRadio { _ ->
            if (request != startRequestId) return@fetchRadio
            queueOp {
                if (request != startRequestId) return@queueOp
                val first = active?.takeIf { it.queue.id == queueId }?.entries?.firstOrNull()
                if (first != null) {
                    startEntry(first.entryId, crossfade = true, startPositionMs = 0L)
                } else {
                    _isStarting.value = false
                    showMessage(context.getString(R.string.msg_radio_load_failed))
                }
            }
        }
    }

    /** Keep on or off for a radio (D52): the flag only, the trim follows at the next refill. */
    fun setKeepHistory(queueId: Long, on: Boolean) = queueOp { queueManager.setKeepHistory(queueId, on) }

    // endregion

    fun setSleepTimer(minutes: Int) = sleep.start(minutes)

    fun setSleepAtEndOfTrack() {
        sleep.atEndOfTrack()
        // A next track already readied would play on (gapless, F4) before the timer stops: it goes
        dropNext()
    }

    fun cancelSleepTimer() = sleep.cancel()

    /** "Without explicit lyrics": such tracks are passed over on the way; a track the user picks still plays. */
    private fun skippedOnTheWay(track: Track): Boolean = skipExplicit.value && track.isExplicit

    private fun onTrackEnded() {
        // With crossfade the end is announced a few seconds early: the timer fades out over that tail
        if (sleep.takeEndOfTrack(org.tegro.yamp.data.repository.PlayerRepositoryImpl.CROSSFADE_DURATION_MS)) return
        playNext(isAuto = true)
    }

    fun persistNow() = queueOp { persistSession() }

    /**
     * Playback resumption (headset/Bluetooth Play or the system media card after the process died):
     * waits for the saved session, optionally starts it, and returns its metadata for the system UI.
     */
    suspend fun prepareResumption(startPlayback: Boolean): Pair<MediaItem, Long>? {
        sessionRestored.await()
        val track = currentTrackNow() ?: return null
        if (startPlayback) {
            play()
            withTimeoutOrNull(RESUMPTION_START_TIMEOUT_MS) { playerRepository.isPlaying.first { it } }
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artists.firstOrNull()?.name)
            .setArtworkUri(coverUrlFor(track)?.toUri())
            .build()
        val item = MediaItem.Builder()
            .setMediaId(track.idString)
            .setMediaMetadata(metadata)
            .build()
        return item to progress.value
    }

    // endregion

    // region Queue operations

    private fun queueOp(block: suspend () -> Unit) {
        val previous = lastQueueOp
        val job = scope.launch(start = CoroutineStart.LAZY) {
            previous?.join()
            block()
        }
        lastQueueOp = job
        job.start()
    }

    /**
     * When the user picked the track the running operation starts (elapsedRealtime), or 0. A pick does not touch
     * the sounding track until the picked one is ready; then the old one fades out quickly and the pick starts at
     * once — no crossfade (2026-11-14: the automatic crossfade held the old track at full volume for seconds with
     * both players decoding, heard as a stutter on a slow head unit). Taken by the first start ([takePick]).
     */
    private var userPickAt = 0L

    /** The user's pick for the start about to happen, once; with nothing sounding there is nothing to keep. */
    private fun takePick(): Long = userPickAt.also { userPickAt = 0L }.takeIf { playerRepository.isPlaying.value } ?: 0L

    /** A start the user asked for by picking a track: see [userPickAt]. */
    private fun pickOp(title: String? = null, block: suspend () -> Unit) {
        val tapAt = android.os.SystemClock.elapsedRealtime()
        journal.record(PlaybackJournal.Kind.TAP, title, "pick")
        playOp {
            userPickAt = tapAt
            pickFromEntryId = active?.cursorEntry?.entryId
            try {
                block()
            } finally {
                userPickAt = 0L
            }
        }
    }

    /** The entry that played before a pick, to go back to if the pick never gets ready. */
    private var pickFromEntryId: Long? = null

    /** A pick failed in the engine, which plays the previous track on: the screen and the queue go back to it. */
    private suspend fun revertFailedPick() {
        val back = pickFromEntryId?.takeIf { id -> active?.entries?.any { it.entryId == id } == true }
        pickFromEntryId = null
        if (back != null) queueManager.moveCursor(back, progress.value)
        _isStarting.value = false
        showMessage(context.getString(R.string.msg_playback_failed))
    }

    /** A request to start something: a later one, or a pause, supersedes it before it runs. */
    private fun playOp(block: suspend () -> Unit) {
        val request = ++startRequestId
        queueOp { if (request == startRequestId) block() }
    }

    private fun currentTrackNow(): Track? = liveStation.value ?: active?.cursorTrack

    /** The track last, so whoever waits for it finds the source and the list already there. */
    private fun refreshDerivedState() {
        val queue = active
        val station = liveStation.value
        _currentSource.value = sourceOf(queue, station)
        _currentPlaylist.value = queue?.tracks.orEmpty()
        _currentTrack.value = station ?: queue?.cursorTrack
    }

    private fun sourceOf(queue: ActiveQueue?, station: Track?): PlaylistSource {
        if (station != null) return PlaylistSource.RADIO
        if (queue == null || queue.cursorTrack == null) return PlaylistSource.NONE
        if (queue.queue.kind == QueueKind.WAVE) return PlaylistSource.VIBE
        // An own queue whose content did not come from one of the old sources (built with "Add to queue…") has
        // no source: the car lights no tile and shows the queue's name instead of "Liked"
        return PlaylistSource.entries.firstOrNull { it.name.equals(queue.queue.origin, ignoreCase = true) } ?: PlaylistSource.NONE
    }

    /** The account's My Wave queue, created empty on first use. */
    private suspend fun waveQueueId(): Long = queueManager.waveQueue(activeUserFolder, waveName).id

    /** Replaces the active queue with [tracks] and plays [startId] (or the first); My Wave is never replaced. */
    private suspend fun playFromNow(tracks: List<Track>, origin: String?, startId: String?, crossfade: Boolean, startPositionMs: Long) {
        val left = active
        // Yandex tracks never go into a Jellyfin account's queue (D84): they go to the context account's own queue
        val foreign = jellyfinQueueAccount() != null && tracks.none { it.ref.provider.startsWith(JELLYFIN_PROVIDER) }
        if (left?.queue?.kind == QueueKind.WAVE || foreign) {
            // The wave is not editable: the list goes to the user's latest own queue, or a new one
            val target = queueManager.queues.value
                .filter { it.queue.kind == QueueKind.STATIC && (!foreign || it.queue.account == accountForNew) }
                .maxByOrNull { it.queue.updatedAt }?.queue?.id
                ?: queueManager.create(mainName, account = accountForNew).id
            queueManager.switchTo(target, progress.value.takeIf { liveStation.value == null })
        }
        val queue = queueManager.replaceActive(tracks, origin, startId?.let { TrackRef.yandex(it) }, nameIfNew = mainName, accountIfNew = accountForNew)
        val entry = queue.cursorEntry ?: return
        startEntry(entry.entryId, crossfade, startPositionMs)
    }

    /** Moves the cursor to [entryId] and starts its track: the one place a queue entry is played from. */
    private suspend fun startEntry(entryId: Long, crossfade: Boolean, startPositionMs: Long) {
        val request = ++startRequestId
        val pickAt = takePick()
        waitingForConnection = false
        queueManager.moveCursor(entryId, startPositionMs)
        if (request != startRequestId) return // paused or superseded while the cursor was being written
        val queue = active ?: return
        val index = queue.indexOfEntry(entryId)
        val track = queue.tracks.getOrNull(index) ?: return
        leaveStation()
        restoredPositionMs.value = startPositionMs

        val trackId = track.idString
        recordTrackPlayed(trackId)
        reserve.markPlayed(trackId)
        // The next track is about to change: a write of another track stops; this one's may finish (D86)
        if (nextFetch.writingTrackId != trackId) nextFetch.cancel()
        setNext(NextTrack.None)

        val upcoming = queue.tracks.drop(index + 1).take(3).map { it.idString }
        // The Yandex link prefetch; a Jellyfin queue's links come from its own resolver (step 23)
        if (upcoming.isNotEmpty() && isYandex(playbackAccount)) preloadManager.preloadTracks(upcoming, playbackAccount)

        if (queue.queue.kind == QueueKind.WAVE) {
            // Continuous queue auto-refill: fewer than 20 tracks left in My Wave
            if (queue.size - index <= 20) wave.fetchMore()
            sendRotorFeedback("trackStarted", trackId)
        }
        if (queue.queue.kind == QueueKind.RADIO) {
            if (queue.size - index <= 20) fetchRadio()
            sendRadioFeedback("trackStarted", trackId)
        }

        resolveAndPlay(track, crossfade, startPositionMs, pickAt)
        queue.tracks.getOrNull(index + 1)?.let { prepareStandbyWhenSettled(it) }
    }

    private suspend fun playNextNow(isAuto: Boolean, tapAt: Long = 0L) {
        val station = liveStation.value
        if (station != null) {
            if (!isAuto) playerRepository.fadeOutForSkip()
            stepRadioStation(station, +1)
            return
        }
        val queue = active
        val current = queue?.cursorTrack
        if (queue == null || current == null) {
            val first = queue?.entries?.firstOrNull()
            when {
                first != null -> startEntry(first.entryId, crossfade = true, startPositionMs = 0L)
                queue != null && queue.queue.kind == QueueKind.STATIC -> showMessage(context.getString(R.string.msg_queue_empty, queue.queue.name))
                else -> playAllLikedTracks()
            }
            return
        }
        // A manual skip to a warmed-up next track fades the old one out now and the next sounds at once (D36).
        // A next track still to load is a pick: the old one plays on until it is ready (2026-11-14), never
        // silence while it loads. Automatic transitions keep the user's crossfade.
        val pickAt = if (isAuto) 0L else (nextAdvance(queue, manual = true) as? Advance.To)
            ?.takeIf { playerRepository.isWarm(queue.tracks[it.index].idString) }
            ?.let { playerRepository.fadeOutForSkip(); 0L } ?: tapAt
        if (pickAt > 0L) pickFromEntryId = queue.cursorEntry?.entryId
        userPickAt = pickAt
        if (returnFromOfflineMixNow()) return

        val currentId = current.idString
        if (queue.queue.kind == QueueKind.WAVE) {
            val playedSeconds = progress.value / 1000.0
            sendRotorFeedback(if (isAuto) "trackFinished" else "skip", currentId, playedSeconds)
        }
        if (queue.queue.kind == QueueKind.RADIO) sendRadioFeedback(if (isAuto) "trackFinished" else "skip", currentId, progress.value)

        when (val advance = nextAdvance(queue, manual = !isAuto)) {
            is Advance.To -> startEntry(queue.entries[advance.index].entryId, crossfade = true, startPositionMs = 0L)
            Advance.FetchMore -> {
                if (isOffline()) {
                    skipToNextOnDeviceNow(current)
                    return
                }
                // End of the wave or radio: fetch more instead of stopping; the batch joins the queue before this runs
                val request = ++startRequestId
                val afterFetch: (Boolean) -> Unit = afterFetch@{ _ ->
                    if (request != startRequestId) return@afterFetch
                    queueOp {
                        if (request != startRequestId) return@queueOp
                        val updated = active ?: return@queueOp
                        val following = nextAdvance(updated, manual = !isAuto) as? Advance.To
                        userPickAt = pickAt
                        when {
                            following != null -> startEntry(updated.entries[following.index].entryId, crossfade = true, startPositionMs = 0L)
                            updated.entries.isNotEmpty() -> startEntry(updated.entries.first().entryId, crossfade = true, startPositionMs = 0L)
                            else -> playAllLikedTracks()
                        }
                        userPickAt = 0L
                    }
                }
                if (queue.queue.kind == QueueKind.RADIO) fetchRadio(afterFetch) else wave.fetchMore(afterFetch)
            }
            Advance.End -> {
                // Repeat off: the queue ends here, the track stays on screen and Play starts it over
                _isStarting.value = false
                playerRepository.stop()
                restoredPositionMs.value = 0L
            }
        }
    }

    private fun nextAdvance(queue: ActiveQueue, manual: Boolean): Advance =
        QueuePolicy.next(queue.cursorIndex, queue.size, queue.queue.kind, queue.queue.repeat, manual) { skippedOnTheWay(queue.tracks[it]) }

    private suspend fun playPrevNow() {
        val station = liveStation.value
        if (station != null) {
            playerRepository.fadeOutForSkip()
            stepRadioStation(station, -1)
            return
        }
        val queue = active ?: return
        if (queue.cursorTrack == null) return
        // Apple Music behaviour: "previous" after three seconds of playback restarts the current track
        val prevIndex = if (progress.value > PlaybackQueue.RESTART_ON_PREV_THRESHOLD_MS) null
        else QueuePolicy.previous(queue.cursorIndex) { skippedOnTheWay(queue.tracks[it]) }
        if (prevIndex == null) {
            seekTo(0)
        } else {
            playerRepository.fadeOutForSkip()
            startEntry(queue.entries[prevIndex].entryId, crossfade = true, startPositionMs = 0L)
        }
    }

    /** My Wave = the account's WAVE queue: switched to, continued after its cursor, fetched when empty. */
    private suspend fun playMyVibeNow() {
        if (isOffline()) {
            playOfflineMixNow(ReturnPoint(null, null, wave = true))
            return
        }
        val waveId = waveQueueId()
        if (active?.queue?.id != waveId) {
            queueManager.switchTo(waveId, progress.value.takeIf { active != null && liveStation.value == null })
            active?.let { followWaveStation(it) }
        }
        var queue = active ?: return
        if (queue.entries.isEmpty() && wave.tracks.value.isNotEmpty()) {
            // The wave's saved list (the preview) becomes the queue's content
            queueManager.appendFetched(waveId, wave.tracks.value.map { it.toTrack() })
            queue = active ?: return
        }
        val after = queue.entries.drop(queue.cursorIndex + 1)
        val start = after.firstOrNull { it.ref.id !in recentPlayedTrackIds } ?: after.firstOrNull() ?: queue.cursorEntry
        if (start != null) {
            startEntry(start.entryId, crossfade = true, startPositionMs = 0L)
            if (queue.size - queue.indexOfEntry(start.entryId) < 30) wave.fetchMore()
            return
        }

        // Nothing cached yet (first launch): show the loading state and start once tracks arrive.
        val request = ++startRequestId
        _isStarting.value = true
        wave.fetchMore { _ ->
            if (request != startRequestId) return@fetchMore
            queueOp {
                if (request != startRequestId) return@queueOp
                val first = active?.takeIf { it.queue.id == waveId }?.entries?.firstOrNull()
                if (first != null) {
                    startEntry(first.entryId, crossfade = true, startPositionMs = 0L)
                } else {
                    _isStarting.value = false
                    showMessage(context.getString(R.string.msg_wave_load_failed))
                }
            }
        }
    }

    private suspend fun playWaveFromNow(trackId: String) {
        val waveId = waveQueueId()
        if (active?.queue?.id != waveId) {
            queueManager.switchTo(waveId, progress.value.takeIf { active != null && liveStation.value == null })
            active?.let { followWaveStation(it) }
        }
        var queue = active ?: return
        var entry = queue.entries.drop(queue.cursorIndex + 1).firstOrNull { it.ref.id == trackId }
        if (entry == null) {
            // The preview's tracks join the queue, so the one tapped is there to play
            queueManager.appendFetched(waveId, wave.tracks.value.map { it.toTrack() })
            queue = active ?: return
            entry = queue.entries.drop(queue.cursorIndex + 1).firstOrNull { it.ref.id == trackId }
                ?: queue.entries.firstOrNull { it.ref.id == trackId }
        }
        if (entry != null) startEntry(entry.entryId, crossfade = true, startPositionMs = 0L) else playMyVibeNow()
    }

    /** The wave's station follows a WAVE queue: with a seed it is a wave by that track, otherwise My Wave. */
    private suspend fun followWaveStation(queue: ActiveQueue) {
        if (queue.queue.kind != QueueKind.WAVE) return
        val seed = queue.queue.seed
        val station = if (seed != null) WaveQueue.trackStation(seed) else WaveQueue.MY_WAVE
        if (wave.station != station) {
            wave.switchTo(station, if (seed != null) queue.tracks.drop(queue.cursorIndex + 1).toYandexTracks() else cacheManager.loadWaveQueue(activeUserFolder))
            _waveTuning.value = WaveTuning()
            loadWaveTuning()
        }
        setWaveSeed(seed?.let { id -> queue.tracks.firstOrNull { it.idString == id } })
    }

    private fun leaveStation() {
        if (liveStation.value == null) return
        liveStation.value = null
        scope.launch { cacheDao.deleteValue(CacheDatabase.SHARED, LIVE_STATION) }
    }

    // endregion

    private fun resolveAndPlay(track: Track, crossfade: Boolean, startPositionMs: Long, pickAt: Long = 0L) {
        currentPlayJob?.cancel()
        playerRepository.noteStartRequested()
        val trackId = track.idString
        val title = track.title
        val artist = track.artists.firstOrNull()?.name
        val coverUrl = coverUrlFor(track)
        _isStarting.value = true
        _streamQuality.value = null

        if (track.isRadio) {
            val station = stationFor(trackId)
            if (station == null) {
                _isStarting.value = false
                return
            }
            playerRepository.playTrack(trackId, ResolvedStream(station.streamUrl), title, artist, coverUrl, crossfade, 0L, pickAt)
            return
        }

        val ref = track.ref
        val downloaded = preloadManager.hasPlayableCopy(trackId)
        if (downloaded) {
            publishQuality(trackId)
            streamingTrackId = null
            playingCopyId = null
            // The resolver hands the copy (a disk check, off the main thread); a copy gone since (the card was
            // taken out) gives the stream instead of a failure (upstream Н2)
            currentPlayJob = scope.launch {
                val stream = streams.forAccount(playbackAccount).resolve(ref).getOrNull()
                playerRepository.playTrack(trackId, stream, title, artist, coverUrl, crossfade, startPositionMs, pickAt)
            }
            return
        }
        when (RoadPlan.originFor(currentLink(), link.mayTryStream(), reserve.has(trackId))) {
            TrackOrigin.UNAVAILABLE -> {
                _isStarting.value = false
                queueOp { skipToNextOnDeviceNow(track) }
                return
            }
            TrackOrigin.COPY -> {
                playCopy(track, crossfade, startPositionMs, pickAt)
                return
            }
            TrackOrigin.STREAM -> Unit
        }

        // Instant start when the URL is already resolved
        val account = playbackAccount
        val fast = streams.forAccount(account).cached(ref)
        if (fast != null) {
            playStreamOrEqualCopy(track, fast, crossfade, startPositionMs, pickAt)
            return
        }

        // The engine's standby already holds this track (the link cache may have dropped it since): no link to
        // fetch, it starts at once — a skip already faded the old track out for it (2026-11-15: "skip→request
        // 1257 ms" was this fetch, heard as silence)
        if (playerRepository.isWarm(trackId)) {
            onDiskTrackId.value = trackId.takeIf { it == lastOnDiskId }
            publishQuality(trackId)
            streamingTrackId = trackId
            playingCopyId = null
            playerRepository.playTrack(trackId, null, title, artist, coverUrl, crossfade, startPositionMs, pickAt)
            return
        }

        currentPlayJob = scope.launch {
            val stream = streams.forAccount(account).resolve(ref).getOrNull()
                ?: run {
                    delay(URL_RETRY_DELAY_MS)
                    streams.forAccount(account).resolve(ref).getOrNull()
                }
            if (stream != null) {
                playStreamOrEqualCopy(track, stream, crossfade, startPositionMs, pickAt)
            } else {
                Log.e(TAG, "Failed to resolve mp3 URL for track $trackId")
                reportTrouble()
                if (currentTrackNow()?.idString != trackId) return@launch
                // A pick whose link never came: the engine was not touched, the previous track plays on
                val reason = if (pickAt > 0L && playerRepository.isPlaying.value) PlaybackFailure.PICK_FAILED else "URL_RESOLVE"
                onPlaybackFailure(PlaybackFailure(trackId, startPositionMs, reason))
            }
        }
    }

    /**
     * The sound mode changed. Links and the warmed-up next track came from the old mode. A FLAC track
     * playing in silence is the reason for the switch, so it goes on from the same second at once;
     * anything else plays on, and the next tracks follow the new mode.
     */
    fun onSoundModeChanged(compatible: Boolean) {
        val track = currentTrackNow()?.takeIf { !it.isRadio }
        val id = track?.idString
        val playingFlac = id != null && playingCopyId != id && if (streamingTrackId == id) {
            preloadManager.qualityOf(id)?.isLossless == true
        } else {
            downloadManager.downloadedQuality(id)?.isLossless == true
        }
        preloadManager.clearCache()
        dropNext()
        if (!compatible || track == null || !playingFlac) return
        if (isPlaying.value) {
            resolveAndPlay(track, crossfade = false, startPositionMs = progress.value)
        } else {
            // On pause: Play starts it again from here
            restoredPositionMs.value = progress.value
            playerRepository.stop()
        }
    }

    /** The stream, unless the reserve holds as good a copy: then it starts at once and costs no traffic. */
    private fun playStreamOrEqualCopy(track: Track, stream: ResolvedStream, crossfade: Boolean, startPositionMs: Long, pickAt: Long) {
        val trackId = track.idString
        val copy = reserve.qualityOf(trackId)
        if (copy != null && RoadPlan.copyIsEnough(preloadManager.qualityOf(trackId), copy)) {
            playCopy(track, crossfade, startPositionMs, pickAt)
            return
        }
        onDiskTrackId.value = trackId.takeIf { stream.localFile || it == lastOnDiskId }
        publishQuality(trackId)
        // Another provider's stream says its own quality (Jellyfin in compatible mode: MP3 320; as-is: unknown)
        if (!isYandex(playbackAccount)) {
            _streamQuality.value = stream.quality?.let { context.getString(R.string.quality_stream, it.codec.uppercase(), it.bitrateKbps) }
        }
        streamingTrackId = trackId
        playingCopyId = null
        playerRepository.playTrack(trackId, stream, track.title, track.artists.firstOrNull()?.name, coverUrlFor(track), crossfade, startPositionMs, pickAt)
    }

    private fun playCopy(track: Track, crossfade: Boolean, startPositionMs: Long, pickAt: Long = 0L) {
        val trackId = track.idString
        onDiskTrackId.value = trackId
        val file = reserve.fileOf(trackId)
        if (file == null) {
            scope.launch { reserve.remove(trackId) }
            _isStarting.value = false
            queueOp { skipToNextOnDeviceNow(track) }
            return
        }
        currentPlayJob?.cancel()
        _isStarting.value = true
        _streamQuality.value = reserve.qualityOf(trackId)?.let { context.getString(R.string.quality_stream, it.codec.uppercase(), it.bitrateKbps) }
        streamingTrackId = null
        playingCopyId = trackId
        playerRepository.playTrack(trackId, ResolvedStream(file.toUri().toString(), localFile = true), track.title, track.artists.firstOrNull()?.name, coverUrlFor(track), crossfade, startPositionMs, pickAt)
    }

    /** The music has stood still long enough to matter: go on from the copy if there is one, and find out why. */
    private fun onStreamStalled(track: Track) {
        // In its own job: the music coming back ends the watchdog, and the music must not wait for the check
        scope.launch { reportTrouble() }
        val trackId = track.idString
        if (!reserve.has(trackId)) return
        val position = if (playerRepository.currentTrackId.value == trackId) progress.value else restoredPositionMs.value
        playCopy(track, crossfade = false, startPositionMs = position)
    }

    /** Is the server there at all? A short check, with no data to speak of: it tells a weak link from no link. */
    private suspend fun reportTrouble() {
        // A second try: on a very slow, busy link one connection can fail with the server still there
        val reachable = networkMonitor.isCurrentlyConnected() &&
            (networkMonitor.isYandexReachable(SERVER_CHECK_TIMEOUT_MS) || networkMonitor.isYandexReachable(SERVER_CHECK_TIMEOUT_MS))
        updateLink { onTrouble(reachable) }
    }

    private fun updateLink(change: LinkTracker.() -> Unit) {
        val before = link.state
        link.change()
        _linkState.value = link.state
        if (link.state != before) diagnosticLog.event("LINK", "${before.name} → ${link.state.name}")
    }

    /** The system's word on the network, the check of the server while it is out of reach, and coming back. */
    private fun watchTheLink() {
        networkMonitor.isConnectedFlow.onEach { present -> updateLink { onNetwork(present) } }.launchIn(scope)
        scope.launch {
            _linkState.collectLatest { state ->
                if (state == LinkState.GOOD && waitingForConnection) {
                    waitingForConnection = false
                    play()
                }
                while (state == LinkState.NONE) {
                    delay(SERVER_CHECK_INTERVAL_MS)
                    if (networkMonitor.isCurrentlyConnected() && networkMonitor.isYandexReachable(SERVER_CHECK_TIMEOUT_MS)) {
                        updateLink { onServerReachable() }
                    }
                }
            }
        }
    }

    /** The connection is good again: the music left for a mix of what is on the device goes back. */
    private suspend fun returnFromOfflineMixNow(): Boolean {
        val point = returnPoint ?: return false
        if (currentLink() != LinkState.GOOD) return false
        returnPoint = null
        if (point.wave) {
            playMyVibeNow()
            return true
        }
        val queueId = point.queueId ?: return false
        if (queueManager.queues.value.none { it.queue.id == queueId }) return false // deleted meanwhile
        queueManager.switchTo(queueId, null)
        val queue = active ?: return false
        val index = point.leftEntryId?.let { queue.indexOfEntry(it) } ?: -1
        val next = queue.entries.getOrNull(index + 1) ?: queue.entries.firstOrNull() ?: return false
        startEntry(next.entryId, crossfade = true, startPositionMs = 0L)
        return true
    }

    private fun upcomingTracks(): List<Track> = active?.let { it.tracks.drop(it.cursorIndex + 1) }.orEmpty()

    /**
     * First failure of a track: retry it once with a fresh URL from the same position (expired links
     * after a long pause). Repeated failure: skip. Several broken tracks in a row: stop and tell the user.
     */
    private fun onPlaybackFailure(failure: PlaybackFailure) {
        if (failure.reason == PlaybackFailure.NO_DATA) {
            // A start that brought no data (D67): pause, never go back or skip — the user sees why it stopped.
            // The engine only reports it for the transition it still runs, so it is never stale.
            pauseForNoData()
            return
        }
        val current = currentTrackNow() ?: return
        if (failure.trackId != current.idString) return // stale event from a track we already left
        if (failure.reason == PlaybackFailure.PICK_FAILED) {
            journal.record(PlaybackJournal.Kind.ERROR, current.title, "pick failed, back to the previous track")
            queueOp { revertFailedPick() }
            return
        }

        streams.forAccount(playbackAccount).invalidate(refOf(failure.trackId))
        // A copy that does not play is no use to anyone
        val copyFailed = playingCopyId == failure.trackId
        if (copyFailed) {
            playingCopyId = null
            scope.launch { reserve.remove(failure.trackId) }
        }
        val action = PlaybackQueue.recoveryFor(
            offline = isOffline(),
            isRadio = current.isRadio,
            isDownloaded = preloadManager.hasPlayableCopy(current.idString),
            alreadyRetried = retriedTrackId == failure.trackId,
            failedTracksInARow = consecutiveFailedTracks + 1,
            hasCopy = !copyFailed && reserve.has(failure.trackId),
            weakLink = _linkState.value == LinkState.WEAK,
            slowness = PlaybackQueue.isSlowness(failure.reason)
        )
        journal.record(PlaybackJournal.Kind.ERROR, current.title, "${failure.reason} at ${failure.positionMs / 1000} s → $action" + if (action == Recovery.RETRY) " new link" else "")
        when (action) {
            Recovery.RESUME_FROM_COPY -> {
                retriedTrackId = null
                playCopy(current, crossfade = false, startPositionMs = failure.positionMs)
            }
            Recovery.CONTINUE_OFFLINE -> {
                // The link dropped mid-track: not a broken track, continue with what is on the device
                retriedTrackId = null
                queueOp { skipToNextOnDeviceNow(current) }
            }
            Recovery.RETRY -> {
                retriedTrackId = failure.trackId
                Log.w(TAG, "Retrying ${failure.trackId} from ${failure.positionMs} ms after ${failure.reason}")
                resolveAndPlay(current, crossfade = false, startPositionMs = failure.positionMs)
            }
            Recovery.STOP -> {
                consecutiveFailedTracks = 0
                retriedTrackId = null
                _isStarting.value = false
                playerRepository.stop()
                // A station that went silent with the connection comes back with it
                waitingForConnection = isOffline()
                // Keep the track on screen so Play retries it
                restoredPositionMs.value = failure.positionMs
                showMessage(context.getString(if (current.isRadio) R.string.msg_station_unavailable else R.string.msg_playback_failed))
            }
            Recovery.SKIP -> {
                consecutiveFailedTracks++
                retriedTrackId = null
                playNext()
            }
            Recovery.PAUSE -> {
                retriedTrackId = null
                pause(reason = "${failure.reason}, too slow")
            }
        }
    }

    /** What plays now and what the server offered, for the player's quality details. */
    data class QualityDetails(
        val playing: String?,
        val offered: String?,
        val hasPlus: Boolean? = null
    )

    fun qualityDetails(): QualityDetails? {
        val track = currentTrackNow()?.takeIf { !it.isRadio } ?: return null
        val id = track.idString
        val hasPlus = authRepository.getActiveAccount()?.hasPlus
        return QualityDetails(
            _streamQuality.value,
            preloadManager.offeredOf(id),
            hasPlus
        )
    }

    private fun publishQuality(trackId: String) {
        _streamQuality.value = if (preloadManager.hasPlayableCopy(trackId)) {
            val q = downloadManager.downloadedQuality(trackId)
            if (q != null) {
                if (q.codec.startsWith("flac", ignoreCase = true)) "FLAC · ОФЛАЙН"
                else context.getString(R.string.quality_offline_known, q.codec.uppercase(), q.bitrateKbps)
            } else {
                context.getString(R.string.quality_offline)
            }
        } else {
            preloadManager.qualityOf(trackId)?.let {
                if (it.isLossless) "FLAC · LOSSLESS"
                else context.getString(R.string.quality_stream, it.codec.uppercase(), it.bitrateKbps)
            }
        }
    }

    private fun isOffline(): Boolean = !networkMonitor.isCurrentlyConnected() || link.state == LinkState.NONE

    private fun currentLink(): LinkState = if (isOffline()) LinkState.NONE else link.state

    private fun isOnDevice(trackId: String): Boolean = preloadManager.hasPlayableCopy(trackId) || reserve.has(trackId)

    /**
     * No connection and the queue has nothing more on the device: the rest of the reserve (what has not
     * been heard first), then the downloads, shuffled, in a queue of their own so the one the music left stays
     * as it was. With nothing at all the music waits for the connection.
     */
    private suspend fun playOfflineMixNow(leaving: ReturnPoint) {
        val reserved = ReservePolicy.playOrder(reserve.entries.value.values) { it in recentPlayedTrackIds }
            .map { it.track.toTrack() }
            .filterNot { preloadManager.hasPlayableCopy(it.idString) }
        val downloaded = libraryTracks.filter { preloadManager.hasPlayableCopy(it.idString) }.shuffled()
        val mix = (reserved + downloaded).distinctBy { it.idString }
        if (mix.isEmpty()) {
            _isStarting.value = false
            waitingForConnection = true
            playerRepository.pause()
            showMessage(context.getString(R.string.msg_offline_no_downloads))
            return
        }
        // Coming from a mix already, the place to go back to stays what it was
        val point = returnPoint ?: leaving
        val mixId = offlineMixQueueId?.takeIf { id -> queueManager.queues.value.any { it.queue.id == id } }
            ?: queueManager.queues.value.firstOrNull { it.queue.origin == OFFLINE_MIX_ORIGIN }?.queue?.id
            ?: queueManager.create(context.getString(R.string.queue_offline_mix_name), account = accountForNew).id
        offlineMixQueueId = mixId
        if (active?.queue?.id != mixId) queueManager.switchTo(mixId, progress.value.takeIf { active != null && liveStation.value == null })
        playFromNow(mix, OFFLINE_MIX_ORIGIN, mix.first().idString, crossfade = true, startPositionMs = 0L)
        returnPoint = point
    }

    /** The next track after [from] that is on the device, otherwise the mix of what is. */
    private suspend fun skipToNextOnDeviceNow(from: Track) {
        if (from.isRadio) {
            playerRepository.stop()
            waitingForConnection = true
            showMessage(context.getString(R.string.msg_station_unavailable))
            return
        }
        val queue = active
        if (queue != null) {
            val next = PlaybackQueue.nextMatching(queue.tracks, queue.cursorIndex) { isOnDevice(it.idString) }
            if (next != null) {
                startEntry(queue.entries[next].entryId, crossfade = true, startPositionMs = 0L)
                return
            }
        }
        playOfflineMixNow(ReturnPoint(queue?.queue?.id, queue?.cursorEntry?.entryId, wave = false))
    }

    private fun prepareNextTrackInQueue() {
        if (liveStation.value != null) return
        val queue = active ?: return
        if (queue.cursorTrack == null) return

        // Proactively refill the wave while 10 tracks are still ahead: the "next up" shelf never runs dry
        if (queue.queue.kind == QueueKind.WAVE && queue.size - queue.cursorIndex <= 10) {
            wave.fetchMore()
        }
        if (queue.queue.kind == QueueKind.RADIO && queue.size - queue.cursorIndex <= 10) fetchRadio()

        val next = nextAdvance(queue, manual = false) as? Advance.To ?: return
        prepareStandby(queue.tracks[next.index])
    }

    private var standbyJob: Job? = null

    /**
     * The next track is readied once the current one has [RESERVE_HEADROOM_MS] in hand and no track change runs:
     * both loading at the start of a track split the line exactly when the new one needs all of it (upstream 1.14.3).
     */
    private fun prepareStandbyWhenSettled(track: Track) {
        standbyJob?.cancel()
        val current = currentTrackNow()?.idString
        standbyJob = scope.launch {
            // A burst of skips is not over until the engine is settled (F4): nothing is fetched for a passing track
            while (!playerRepository.settled || playerRepository.bufferedAheadMs < RESERVE_HEADROOM_MS || playerRepository.currentTrackId.value != current) {
                if (currentTrackNow()?.idString != current) return@launch
                delay(STANDBY_POLL_MS)
            }
            prepareStandby(track)
        }
    }

    private fun prepareStandby(track: Track) {
        // The sleep timer ends playback with this track: nothing to ready (a gapless next track would sound, F4)
        if (track.isRadio || sleep.endsWithTrack) return
        val nextId = track.idString
        nextTitle = track.title
        if (_nextTrack.value !is NextTrack.Ready || _nextTrack.value.trackId != nextId) setNext(NextTrack.Loading(nextId))
        scope.launch {
            val ref = track.ref
            val stream = when {
                // The resolver hands the downloaded copy first
                preloadManager.hasPlayableCopy(nextId) -> streams.forAccount(playbackAccount).resolve(ref).getOrNull()
                RoadPlan.originFor(currentLink(), link.mayTryStream(), reserve.has(nextId)) == TrackOrigin.COPY -> copyStream(nextId)
                currentLink() == LinkState.NONE -> null
                else -> streams.forAccount(playbackAccount).resolve(ref).getOrNull()?.let { stream ->
                    val copy = reserve.qualityOf(nextId)
                    if (copy != null && RoadPlan.copyIsEnough(preloadManager.qualityOf(nextId), copy)) copyStream(nextId) ?: stream else stream
                }
            }
            if (stream == null) {
                if (_nextTrack.value.trackId == nextId) setNext(NextTrack.Failed(nextId, if (currentLink() == LinkState.NONE) "offline" else "no link"))
                return@launch
            }
            when {
                stream.localFile -> setNext(NextTrack.Ready(nextId, onDisk = true))
                nextFetch.isOnDisk(stream) -> setNext(NextTrack.Ready(nextId, onDisk = true))
                // The whole file goes to disk ahead of time; the warm player below needs only its first seconds (D86)
                else -> nextFetch.prefetch(nextId, stream)
            }
            playerRepository.prepareNextTrack(nextId, stream, track.title, track.artists.firstOrNull()?.name, coverUrlFor(track))
        }
    }

    /** The next track no longer holds (the queue changed): its warm player, its disk write and its state go. */
    private fun dropNext() {
        playerRepository.dropStandby()
        nextFetch.cancel()
        setNext(NextTrack.None)
    }

    /** The title of the track [nextTrack] is about, for the journal. */
    private var nextTitle: String? = null

    private fun setNext(state: NextTrack) {
        val before = _nextTrack.value
        if (before == state) return
        _nextTrack.value = state
        if (state is NextTrack.Ready && state.onDisk) lastOnDiskId = state.trackId
        val detail = when (state) {
            is NextTrack.None -> return
            is NextTrack.Loading -> "loading"
            is NextTrack.Ready -> if (state.onDisk) "ready (on disk)" else "ready (warm player)"
            is NextTrack.Failed -> "failed: ${state.reason}"
        }
        journal.record(PlaybackJournal.Kind.NEXT, nextTitle, detail)
    }

    /**
     * The next track's state follows the warm standby, the disk writes and their failures (D80); a failed one is
     * tried again by itself once the link comes back (D81).
     */
    private fun watchTheNextTrack() {
        playerRepository.warmTrackId.onEach { warm ->
            val next = _nextTrack.value
            val onDisk = (next as? NextTrack.Ready)?.onDisk == true
            if (warm != null && warm == next.trackId && !onDisk) setNext(NextTrack.Ready(warm, onDisk = false))
        }.launchIn(scope)
        nextFetch.results.onEach { result ->
            journal.record(PlaybackJournal.Kind.CACHE, nextTitle.takeIf { _nextTrack.value.trackId == result.trackId }, result.outcome.name.lowercase().replace('_', ' '))
            if (_nextTrack.value.trackId != result.trackId) return@onEach
            when (result.outcome) {
                NextTrackFetch.Outcome.WRITTEN, NextTrackFetch.Outcome.ALREADY_ON_DISK -> setNext(NextTrack.Ready(result.trackId, onDisk = true))
                NextTrackFetch.Outcome.FAILED -> if (_nextTrack.value !is NextTrack.Ready) setNext(NextTrack.Failed(result.trackId, "disk write failed"))
                NextTrackFetch.Outcome.CANCELLED -> Unit
            }
        }.launchIn(scope)
        playerRepository.standbyFailedEvent.onEach { (trackId, reason) ->
            if (_nextTrack.value.trackId == trackId && !(_nextTrack.value as? NextTrack.Ready)?.onDisk.orFalse()) setNext(NextTrack.Failed(trackId, reason))
        }.launchIn(scope)
        _linkState.onEach { state ->
            if (state == LinkState.GOOD) retryFailedNext()
        }.launchIn(scope)
    }

    /** The next track failed and the link is back: it is fetched again, with a fresh link (D81). */
    private fun retryFailedNext() {
        val failed = _nextTrack.value as? NextTrack.Failed ?: return
        val queue = active ?: return
        val next = nextAdvance(queue, manual = false) as? Advance.To ?: return
        val track = queue.tracks[next.index]
        if (track.idString != failed.trackId) return
        streams.forAccount(playbackAccount).invalidate(track.ref)
        prepareStandby(track)
    }

    private fun Boolean?.orFalse(): Boolean = this == true

    /**
     * The wave page's "next up" shelf must never stay empty. Whenever it is short (first launch, a restored
     * paused wave with nothing after the current track, a failed request) more of the wave is fetched, again
     * after a growing pause if a request fails, and at once when the network comes back. Waits for the saved
     * session, so the first request does not race the restore.
     */
    private fun keepWaveShelfFilled() {
        scope.launch {
            sessionRestored.await()
            combine(
                queueManager.active,
                liveStation,
                wave.tracks,
                // Online and signed in: signed out there is nothing to fetch, and signing in checks again
                networkMonitor.isConnectedFlow.combine(authRepository.isAuthorized()) { online, signedIn -> online && signedIn }
            ) { queue, station, wave, canFetch ->
                // The queue side counts by entry, so a track that is in the wave twice is two places
                val waveActive = station == null && queue?.queue?.kind == QueueKind.WAVE && queue.cursorTrack != null
                canFetch && PlaybackQueue.waveShelfIsShort(
                    waveActive = waveActive,
                    currentId = queue?.cursorEntry?.entryId?.toString(),
                    queue = queue?.entries?.map { it.entryId.toString() }.orEmpty(),
                    wave = wave.map { it.idString }
                )
            }.collectLatest { short ->
                if (!short) return@collectLatest
                var pause = WAVE_RETRY_START_MS
                // A successful request changes the lists, which re-checks the shelf from the top
                while (!wave.fetchMoreAwait()) {
                    delay(pause)
                    pause = (pause * 2).coerceAtMost(WAVE_RETRY_MAX_MS)
                }
            }
        }
    }

    /** A radio's "next up" never stays empty either (§4): the wave's rule on the radio's own entries. */
    private fun keepRadioShelfFilled() {
        scope.launch {
            sessionRestored.await()
            combine(
                queueManager.active,
                liveStation,
                networkMonitor.isConnectedFlow.combine(authRepository.isAuthorized()) { online, signedIn -> online && signedIn }
            ) { queue, station, canFetch ->
                val radioActive = station == null && queue?.queue?.kind == QueueKind.RADIO && queue.cursorTrack != null
                canFetch && radioActive && PlaybackQueue.waveShelfIsShort(
                    waveActive = true,
                    currentId = queue?.cursorEntry?.entryId?.toString(),
                    queue = queue?.entries?.map { it.entryId.toString() }.orEmpty(),
                    wave = emptyList()
                )
            }.distinctUntilChanged().collectLatest { short ->
                if (!short) return@collectLatest
                var pause = WAVE_RETRY_START_MS
                while (!fetchRadioAwait()) {
                    delay(pause)
                    pause = (pause * 2).coerceAtMost(WAVE_RETRY_MAX_MS)
                }
            }
        }
    }

    /** The source of [queue] when it is a radio: the one held, or a new one for its seed's station. */
    private fun radioSourceOf(queue: ActiveQueue): RotorStationSource? {
        if (queue.queue.kind != QueueKind.RADIO) return null
        val seed = queue.queue.seed?.let { MediaRef.parse(it) } ?: return null
        val station = RotorStations.stationOf(seed)
        radio?.takeIf { radioQueueId == queue.queue.id && it.station == station }?.let { return it }
        dropRadioSource()
        // The radio's own account (D57): its batches and feedback, whatever account was in force before
        val account = accountContext.signedIn(queue.queue.account) ?: contextAccount
        return RotorStationSource(
            station = station,
            apiOf = { clients.api(account) },
            heardLately = { it in recentPlayedTrackIds },
            keep = { !(skipExplicit.value && it.isExplicit) }
        ).also {
            radio = it
            radioQueueId = queue.queue.id
        }
    }

    private fun dropRadioSource() {
        radioFetch?.cancel()
        radioFetch = null
        radio = null
        radioQueueId = null
        radioFetching.value = false
    }

    /**
     * The next batch of the active radio; one fetch at a time. It lands only while that radio is still active
     * with the same source — nothing is appended to an inactive or re-seeded radio. True when tracks landed.
     */
    private fun radioFetchAsync(): Deferred<Boolean>? {
        if (liveStation.value != null || isOffline() || authRepository.getToken().isNullOrEmpty()) return null
        val queue = active ?: return null
        val source = radioSourceOf(queue) ?: return null
        radioFetch?.takeIf { it.isActive }?.let { return it }
        val queueId = queue.queue.id
        val queued = queue.entries.map { it.ref }
        radioFetching.value = true
        return scope.async {
            try {
                val batch = withContext(Dispatchers.IO) { source.fetchTracks(queued) }
                if (batch.isEmpty()) return@async false
                val landed = CompletableDeferred<Boolean>()
                queueOp {
                    val current = radio === source && active?.queue?.id == queueId
                    if (current) queueManager.appendFetched(queueId, batch)
                    landed.complete(current)
                }
                landed.await()
            } finally {
                if (radio === source) radioFetching.value = false
            }
        }.also { radioFetch = it }
    }

    private fun fetchRadio(onFetched: ((Boolean) -> Unit)? = null) {
        val fetch = radioFetchAsync()
        if (onFetched == null) return
        if (fetch == null) onFetched(false) else scope.launch { onFetched(fetch.await()) }
    }

    private suspend fun fetchRadioAwait(): Boolean = radioFetchAsync()?.await() ?: false

    /** Rotor feedback for the active radio's station (started / finished / skip), so the station learns. */
    private fun sendRadioFeedback(type: String, trackId: String, playedMs: Long = 0L) {
        val source = active?.let { radioSourceOf(it) } ?: return
        scope.launch(Dispatchers.IO) {
            try {
                source.feedback(TrackRef.yandex(trackId), type, playedMs)
            } catch (e: IOException) {
                Log.w(TAG, "Radio feedback $type not sent", e)
            } catch (e: HttpException) {
                Log.w(TAG, "Radio feedback $type refused: ${e.code()}", e)
            }
        }
    }

    private fun copyStream(trackId: String): ResolvedStream? =
        reserve.fileOf(trackId)?.toUri()?.toString()?.let { ResolvedStream(it, localFile = true) }

    private fun recordTrackPlayed(trackId: String) {
        val snapshot = recentPlayedTrackIds.record(trackId) ?: return
        val folder = activeUserFolder
        scope.launch { cacheManager.saveRecentTrackIds(folder, snapshot) }
    }

    private fun sendRotorFeedback(type: String, trackId: String, playedSeconds: Double? = null) {
        val station = wave.station
        scope.launch(Dispatchers.IO) {
            try {
                api.sendStationFeedback(station, type, trackId, from = WaveQueue.feedbackFrom(station), totalPlayedSeconds = playedSeconds)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error sending rotor feedback $type", e)
            }
        }
    }

    private fun stepRadioStation(current: Track, step: Int) {
        val stations = fmRadio.stations()
        if (stations.isEmpty()) return
        val index = stations.indexOfFirst { FmRadio.trackIdOf(it) == current.idString }
        if (index == -1) return
        playRadioStation(stations[(index + step + stations.size) % stations.size])
    }

    private fun stationFor(trackId: String): RadioStation? = fmRadio.station(trackId)

    private fun radioTrackFor(station: RadioStation): Track = fmRadio.trackOf(station)

    /** The position of the cursor track, written through to the queue (E3); a station on air has no position. */
    private suspend fun persistSession() {
        if (liveStation.value != null) return
        queueManager.savePosition(progress.value)
    }

    /**
     * Restore is read-only (DPI rule 2): the active queue comes back paused at its saved position, a station
     * that was on air comes back paused too. The one write is the one-time import of the pre-queue session file.
     */
    private suspend fun loadUserState(userFolder: String, restoreSession: Boolean) {
        val savedRecent = cacheManager.loadRecentTrackIds(userFolder)
        val cachedWave = cacheManager.loadWaveQueue(userFolder)
        if (activeUserFolder != userFolder) return // account switched while loading

        recentPlayedTrackIds.restore(savedRecent)
        // Without what was just heard, so the wave starts with something fresh
        wave.restore(cachedWave)

        if (!restoreSession) return
        legacyImport.run(mainName = mainName, waveName = waveName)
        queueManager.load()
        leaveStrandedQueue()
        val queue = active
        if (queue != null) {
            // A wave by a track goes on from where it was, with its own upcoming tracks
            followWaveStation(queue)
            // Shown paused, like Apple Music: Play continues from the saved position.
            restoredPositionMs.value = queue.queue.positionMs
        }
        val stationId = cacheDao.getValue(CacheDatabase.SHARED, LIVE_STATION) ?: return
        val station = fmRadio.stationById(stationId) ?: return
        liveStation.value = radioTrackFor(station)
        restoredPositionMs.value = 0L
    }

    private fun coverUrlFor(track: Track): String? = track.coverUri?.let { uri ->
        if (uri.startsWith("android.resource")) uri else uri.toCoverUrl(org.tegro.yamp.utils.CoverSize.Medium)
    }

    /** User-facing playback errors; the visible UI shows them as a Snackbar. */
    private val _messages = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: kotlinx.coroutines.flow.SharedFlow<String> = _messages

    private fun showMessage(text: String) {
        // No screen is collecting (app in background): fall back to a system toast
        if (_messages.subscriptionCount.value == 0) {
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        } else {
            _messages.tryEmit(text)
        }
    }

    companion object {
        const val WAVE_RETRY_START_MS = 2_000L
        const val WAVE_RETRY_MAX_MS = 60_000L
        /** Sound the playing track must have ahead before the road reserve may use the line. */
        const val RESERVE_HEADROOM_MS = 20_000L
        /** The first half-minute of a track: its own, not the cache's. */
        private const val TRACK_SETTLE_MS = 30_000L
        private const val STANDBY_POLL_MS = 500L
        /** `cache_values` key (owner [CacheDatabase.SHARED]) of the FM station on air, for the restore. */
        const val LIVE_STATION = "live_station"
        /** The origin that marks the app-made queue of the offline mix. */
        const val OFFLINE_MIX_ORIGIN = "OFFLINE_MIX"
        private const val TAG = "PlaybackController"
        private const val RECENT_LIMIT = 100
        private const val URL_RETRY_DELAY_MS = 700L
        private const val SNAPSHOT_INTERVAL_MS = 15_000L
        private const val RESUMPTION_START_TIMEOUT_MS = 4_000L
        /** How often a stalled load is checked for new data (D67). */
        private const val NO_DATA_CHECK_MS = 1_000L
        /** The player's buffer is empty and the music stands: the moment to look at the link. */
        private const val LINK_STALL_MS = 6_000L
        private const val SERVER_CHECK_INTERVAL_MS = 30_000L
        /** Long enough for a slow link to answer: a weak link must not read as no link. */
        private const val SERVER_CHECK_TIMEOUT_MS = 4_000
    }
}

/** The provider key of Jellyfin refs (`jellyfin@<server>`); `org.tegro.yamp.provider.jellyfin.JellyfinRefs.PROVIDER`. */
private const val JELLYFIN_PROVIDER = org.tegro.yamp.provider.jellyfin.JellyfinRefs.PROVIDER
