package org.tegro.yamp.data.player

import kotlinx.coroutines.withContext
import org.tegro.yamp.domain.model.WaveTuning
import kotlinx.coroutines.flow.distinctUntilChanged
import org.tegro.yamp.utils.prefetchCovers
import org.tegro.yamp.utils.CoverSize
import org.tegro.yamp.data.reserve.RoadReserve
import org.tegro.yamp.data.reserve.ReservePolicy
import org.tegro.yamp.data.reserve.ReserveKeeper
import org.tegro.yamp.utils.toCoverUrl
import org.tegro.yamp.R
import androidx.core.net.toUri
import android.content.Context
import android.util.Log
import android.widget.Toast
import org.tegro.yamp.BuildConfig
import org.tegro.yamp.data.cache.CacheDao
import org.tegro.yamp.data.cache.CacheDatabase
import org.tegro.yamp.data.cache.PlaylistCacheManager
import org.tegro.yamp.data.cache.ValueEntity
import org.tegro.yamp.data.dto.ArtistDto
import org.tegro.yamp.data.dto.TrackDetailsDto
import org.tegro.yamp.data.network.TrackDownloadManager
import org.tegro.yamp.data.network.TrackUrlResolver
import org.tegro.yamp.data.network.YandexMusicApi
import org.tegro.yamp.data.queue.ActiveQueue
import org.tegro.yamp.data.queue.LegacySessionImport
import org.tegro.yamp.data.queue.QueueManager
import org.tegro.yamp.data.repository.RadioRepository
import org.tegro.yamp.domain.model.RadioStation
import org.tegro.yamp.domain.queue.QueueKind
import org.tegro.yamp.domain.queue.QueuePolicy
import org.tegro.yamp.domain.queue.QueuePolicy.Advance
import org.tegro.yamp.domain.queue.QueueSummary
import org.tegro.yamp.domain.queue.RepeatMode
import org.tegro.yamp.domain.queue.TrackRef
import org.tegro.yamp.domain.repository.AuthRepository
import org.tegro.yamp.domain.repository.PlaybackFailure
import org.tegro.yamp.domain.repository.PlayerRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
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

/**
 * Makes every playback decision (next/previous, My Wave refill, error recovery) over the queues that
 * [QueueManager] owns: the controller reads the active queue and asks the manager for changes, it never holds
 * a queue of its own (docs/phase-1-queues.md §4). It is a process-wide singleton, so notification, headset and
 * track-end events keep working after the Activity and its ViewModel are destroyed (e.g. the task is swiped away).
 */
@Singleton
class PlaybackController @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val api: YandexMusicApi,
    private val authRepository: AuthRepository,
    private val playerRepository: PlayerRepository,
    private val downloadManager: TrackDownloadManager,
    private val preloadManager: TrackUrlResolver,
    private val cacheManager: PlaylistCacheManager,
    private val radioRepository: RadioRepository,
    private val queueManager: QueueManager,
    private val legacyImport: LegacySessionImport,
    private val cacheDao: CacheDao,
    private val settingsRepository: org.tegro.yamp.domain.repository.SettingsRepository,
    private val networkMonitor: org.tegro.yamp.data.network.NetworkMonitor,
    private val reserve: RoadReserve,
    private val journal: PlaybackJournal,
    private val diagnosticLog: org.tegro.yamp.data.diagnostics.DiagnosticLog
) {
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
    private val liveStation = MutableStateFlow<TrackDetailsDto?>(null)

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
    private val _currentPlaylist = MutableStateFlow<List<TrackDetailsDto>>(emptyList())
    val currentPlaylist: StateFlow<List<TrackDetailsDto>> = _currentPlaylist.asStateFlow()

    private val _currentTrack = MutableStateFlow<TrackDetailsDto?>(null)
    val currentTrack: StateFlow<TrackDetailsDto?> = _currentTrack.asStateFlow()

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

    val isBuffering: StateFlow<Boolean> = combine(playerRepository.isBuffering, _isStarting) { buffering, starting ->
        buffering || starting
    }.stateIn(scope, SharingStarted.Eagerly, false)

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

    private val _waveSeed = MutableStateFlow<TrackDetailsDto?>(null)
    /** The track a "wave by track" was started from; null while My Wave (or anything else) plays. */
    val waveSeed: StateFlow<TrackDetailsDto?> = _waveSeed.asStateFlow()

    private val _waveTuning = MutableStateFlow(WaveTuning())
    /** The current station's character, mood and language. */
    val waveTuning: StateFlow<WaveTuning> = _waveTuning.asStateFlow()

    private val _trackWaveStarted = kotlinx.coroutines.flow.MutableSharedFlow<TrackDetailsDto>(extraBufferCapacity = 1)
    /** A wave by a track has just started: the screen offers to undo it. */
    val trackWaveStarted: kotlinx.coroutines.flow.SharedFlow<TrackDetailsDto> = _trackWaveStarted

    /** Where the music was when a wave by a track started: the queue, its cursor entry and the position. */
    private data class BeforeTrackWave(
        val queueId: Long?,
        val entryId: Long?,
        val positionMs: Long,
        val seed: TrackDetailsDto?
    )

    private var beforeTrackWave: BeforeTrackWave? = null

    init {
        // Volume normalization: the gains of the queued tracks, applied as each one plays
        combine(currentPlaylist, settingsRepository.normalizeVolume) { queue, on ->
            if (!on) emptyMap() else queue.mapNotNull { track -> track.r128?.integrated?.let { track.idString to Loudness.gainFor(it) } }.toMap()
        }.distinctUntilChanged().onEach { playerRepository.setTrackGains(it) }.launchIn(scope)
    }

    /**
     * "Моя волна": the rotor fetching, its de-dup and the preview the wave page shows. What it fetches joins
     * the account's WAVE queue, which is the wave the user plays; the wave's own list stays for the preview.
     */
    private val wave = WaveQueue(
        api = api,
        cacheManager = cacheManager,
        preloadManager = preloadManager,
        scope = scope,
        recent = recentPlayedTrackIds,
        isOffline = ::isOffline,
        userFolder = { activeUserFolder },
        keep = { !(skipExplicit.value && it.isExplicit) },
        // Appended to the wave queue as it is now: tracks the user removed or moved stay that way
        onFresh = { fresh -> queueOp { queueManager.appendFetched(waveQueueId(), fresh) } }
    )
    val isFetchingMoreQueue: StateFlow<Boolean> get() = wave.isFetching

    /** What the wave will play when it is started; shown on the wave page while the wave is not playing. */
    val wavePreview: StateFlow<List<TrackDetailsDto>> get() = wave.preview
    private var libraryTracks: List<TrackDetailsDto> = emptyList()

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
        resolve = preloadManager::resolveOrdinaryStream,
        // The reserve fills only while the playing track has sound in hand: the two downloads must not compete
        streamNeedsTheLine = { isBuffering.value || playerRepository.bufferedAheadMs < RESERVE_HEADROOM_MS },
        upcoming = { upcomingTracks() },
        moreTracks = { wave.tracks.value },
        isDownloaded = preloadManager::hasPlayableCopy,
        fetchMore = { wave.fetchMoreAwait() },
        onStored = { prefetchCovers(context, listOf(it), listOf(CoverSize.Small, CoverSize.Medium, CoverSize.Large)) }
    )

    private var currentPlayJob: Job? = null
    private var bufferingWatchdog: Job? = null
    private var stallWatchdog: Job? = null
    /** Incremented on every explicit start/stop so stale async callbacks can tell they were superseded. */
    private var startRequestId = 0L
    private var retriedTrackId: String? = null
    private var consecutiveFailedTracks = 0
    /** Completes once the saved session (if any) has been loaded from the queue store. */
    private val sessionRestored = kotlinx.coroutines.CompletableDeferred<Unit>()

    private val activeUserFolder: String
        get() = authRepository.getActiveAccount()?.folderName ?: "default"

    private val mainName: String get() = context.getString(R.string.queue_main_name)
    private val waveName: String get() = context.getString(R.string.queue_my_wave_name)

    init {
        playerRepository.trackEndedEvent.onEach { onTrackEnded() }.launchIn(scope)
        playerRepository.prepareNextTrackEvent.onEach { prepareNextTrackInQueue() }.launchIn(scope)
        playerRepository.skipNextEvent.onEach { playNext() }.launchIn(scope)
        playerRepository.skipPrevEvent.onEach { playPrev() }.launchIn(scope)
        playerRepository.playbackErrorEvent.onEach { onPlaybackFailure(it) }.launchIn(scope)
        settingsRepository.crossfadeSeconds
            .onEach { playerRepository.setCrossfadeDuration(it * 1000L) }
            .launchIn(scope)

        // A track that actually renders audio resets error recovery.
        combine(playerRepository.isPlaying, playerRepository.isBuffering) { playing, buffering -> playing && !buffering }
            .onEach { audible ->
                if (audible) {
                    _isStarting.value = false
                    consecutiveFailedTracks = 0
                    retriedTrackId = null
                    if (streamingTrackId != null && streamingTrackId == currentTrackNow()?.idString) updateLink { onStreamAudible() }
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
                    bufferingWatchdog = scope.launch {
                        delay(BUFFERING_TIMEOUT_MS)
                        if (isBuffering.value && currentTrackNow()?.idString == trackId && trackId != null) {
                            Log.w(TAG, "Buffering watchdog fired for $trackId")
                            onPlaybackFailure(PlaybackFailure(trackId, progress.value, "BUFFERING_TIMEOUT"))
                        }
                    }
                }
            }
            .launchIn(scope)

        keepWaveShelfFilled()
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

    fun updateLibrary(tracks: List<TrackDetailsDto>) {
        libraryTracks = tracks
    }

    /**
     * Plays [trackId] out of [playlist]. The same list as the active queue (the queue sheet's rows) moves the
     * cursor to its first entry with that track; any other list replaces the queue (E5). Kept for the screens
     * that still hand in lists; the queue sheet will pass entry ids to [playEntry].
     */
    fun playTrack(
        trackId: String,
        playlist: List<TrackDetailsDto>,
        source: PlaylistSource? = null,
        crossfade: Boolean = true,
        startPositionMs: Long = 0L
    ) {
        if (playlist.none { it.idString == trackId }) return
        // A context chosen on purpose: there is nothing to go back to any more
        if (source != null) returnPoint = null
        playOp {
            val queue = active
            if (queue != null && queue.tracks.map { it.idString } == playlist.map { it.idString }) {
                val entry = queue.entries.firstOrNull { it.ref.id == trackId } ?: return@playOp
                startEntry(entry.entryId, crossfade, startPositionMs)
            } else {
                playFromNow(playlist, source?.name ?: queue?.queue?.origin, trackId, crossfade, startPositionMs)
            }
        }
    }

    /** Replaces the active queue with [tracks] (E5) and plays [startId], or the first one. */
    fun playFrom(tracks: List<TrackDetailsDto>, source: PlaylistSource, startId: String?) {
        if (tracks.isEmpty()) return
        returnPoint = null
        playOp { playFromNow(tracks, source.name, startId, crossfade = true, startPositionMs = 0L) }
    }

    /** Moves the cursor to [entryId] of the active queue and plays it: the queue sheet's row, duplicates included. */
    fun playEntry(entryId: Long) {
        returnPoint = null
        playOp { startEntry(entryId, crossfade = true, startPositionMs = 0L) }
    }

    /** Starts the wave from a track of its preview (the wave page's "next up" shelf). */
    fun playWaveFrom(trackId: String) {
        if (wave.tracks.value.none { it.idString == trackId }) {
            playMyVibe()
            return
        }
        returnPoint = null
        playOp { playWaveFromNow(trackId) }
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
        playOp { playMyVibeNow() }
    }

    fun playAllLikedTracks(shuffle: Boolean = false) {
        val playlist = if (shuffle) libraryTracks.shuffled() else libraryTracks
        if (playlist.isNotEmpty()) playFrom(playlist, PlaylistSource.LIBRARY, playlist.first().idString)
    }

    fun playHits(hits: List<TrackDetailsDto>, shuffle: Boolean = true) {
        val hitsList = if (shuffle) hits.shuffled() else hits
        if (hitsList.isEmpty()) return
        val trackToPlay = hitsList.firstOrNull { it.idString !in recentPlayedTrackIds } ?: hitsList.first()
        playFrom(hitsList, PlaylistSource.TRENDS, trackToPlay.idString)
    }

    /** A station goes on air beside the active queue, which stays as it is; anything played from a queue ends it. */
    fun playRadioStation(station: RadioStation) {
        returnPoint = null
        playOp {
            val radioTrack = radioTrackFor(station)
            liveStation.value = radioTrack
            restoredPositionMs.value = 0L
            waitingForConnection = false
            scope.launch { cacheDao.putValue(ValueEntity(CacheDatabase.SHARED, LIVE_STATION, station.id)) }
            resolveAndPlay(radioTrack, crossfade = true, startPositionMs = 0L)
        }
    }

    // region Queues: switching, modes, adding

    /**
     * Makes [id] the active queue: shown paused at its saved track and position; keeps playing if the player
     * was, or starts it when [play] asks (a queue picked in Android Auto).
     */
    fun switchQueue(id: Long, play: Boolean = false) = playOp {
        if (active?.queue?.id == id) {
            if (play && !isPlaying.value) this.play()
            return@playOp
        }
        val wasPlaying = isPlaying.value || play
        val position = progress.value.takeIf { liveStation.value == null }
        currentPlayJob?.cancel()
        _isStarting.value = false
        queueManager.switchTo(id, position)
        playerRepository.stop()
        leaveStation()
        val target = active ?: return@playOp
        restoredPositionMs.value = target.queue.positionMs
        followWaveStation(target)
        val entry = target.cursorEntry ?: target.entries.firstOrNull() ?: return@playOp
        if (wasPlaying) startEntry(entry.entryId, crossfade = false, startPositionMs = if (entry == target.cursorEntry) target.queue.positionMs else 0L)
    }

    /** Shuffle keeps the current track where it is and can be switched off again. */
    fun setShuffle(on: Boolean) = queueOp { keepingStandby { queueManager.setShuffle(on) } }

    fun setRepeat(mode: RepeatMode) = queueOp { queueManager.setRepeat(mode) }

    /** "Add to queue…": to the end of any queue, the active one included. */
    fun addToQueue(queueId: Long, tracks: List<TrackDetailsDto>) = queueOp { queueManager.appendTo(queueId, tracks) }

    /** "Add to end of queue": the active one, created when there is none. */
    fun addToEnd(tracks: List<TrackDetailsDto>) = queueOp {
        val queueId = active?.queue?.id ?: queueManager.create(mainName).id.also { queueManager.switchTo(it, null) }
        queueManager.appendTo(queueId, tracks)
    }

    /** "Play next" for a list: right after the current track. */
    fun playNextTracks(tracks: List<TrackDetailsDto>) = queueOp {
        if (active == null) playFromNow(tracks, null, null, crossfade = true, startPositionMs = 0L)
        else queueManager.insertAfterCursor(tracks)
    }

    /** The switcher's edits, each one a queue operation in call order; nothing to wait for. */
    fun createQueue(name: String) = queueOp { queueManager.create(name) }

    /** A new queue that at once gets [tracks] ("New queue" in the "Add to queue…" picker). */
    fun createQueueWith(name: String, tracks: List<TrackDetailsDto>) = queueOp {
        queueManager.appendTo(queueManager.create(name).id, tracks)
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

    fun togglePlayPause() {
        if (isPlaying.value) pause() else play()
    }

    fun play() {
        val current = currentTrackNow()
        if (current == null) {
            // A queue with entries but no cursor starts from its first one; nothing at all starts the wave
            val first = active?.entries?.firstOrNull()
            if (first != null) playEntry(first.entryId) else playMyVibe()
            return
        }
        // A start is already in flight (e.g. a second Play from a headset): don't restart it
        if (_isStarting.value) return
        if (playerRepository.currentTrackId.value == current.idString && playerRepository.resume()) return
        // Nothing loaded (restored session or stopped player): start the current track where it was left.
        resolveAndPlay(current, crossfade = false, startPositionMs = restoredPositionMs.value)
    }

    fun pause(reason: String = "user") {
        journal.record(PlaybackJournal.Kind.PAUSE, currentTrackNow()?.title, reason)
        waitingForConnection = false
        // A start that hasn't produced sound yet is cancelled, also one still waiting for its queue operation
        startRequestId++
        currentPlayJob?.cancel()
        _isStarting.value = false
        playerRepository.pause()
        persistNow()
    }

    fun seekTo(positionMs: Long) {
        if (playerRepository.currentTrackId.value == null) {
            restoredPositionMs.value = positionMs
        } else {
            playerRepository.seekTo(positionMs)
        }
    }

    fun playNext(isAuto: Boolean = false) = queueOp { playNextNow(isAuto) }

    fun playPrev() = queueOp { playPrevNow() }

    fun loadMoreQueue() {
        if (active?.queue?.kind == QueueKind.WAVE) wave.fetchMore()
    }

    fun reorderQueue(from: Int, to: Int) = queueOp {
        val queue = active ?: return@queueOp
        val entry = queue.entries.getOrNull(from) ?: return@queueOp
        val target = queue.entries.getOrNull(to) ?: return@queueOp
        keepingStandby { queueManager.move(entry.entryId, target.entryId) }
    }

    /** Puts [track] right after the current one; with nothing (or a station) playing it just starts. */
    fun enqueueNext(track: TrackDetailsDto) = queueOp {
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

    /** Account switch/logout: drop the session and load the other user's wave and history. */
    fun resetForAccount(userFolder: String?) {
        stopAndClear()
        // The reserve was saved under this account's subscription
        scope.launch { reserve.clear() }
        recentPlayedTrackIds.clear()
        wave.switchTo(WaveQueue.MY_WAVE)
        _waveSeed.value = null
        _waveTuning.value = WaveTuning()
        libraryTracks = emptyList()
        if (userFolder == null) return
        queueOp {
            // Queues are app-wide (D22): only another account's My Wave gives way to this account's
            val queue = active
            if (queue?.queue?.kind == QueueKind.WAVE && queue.queue.account != userFolder) {
                queueManager.switchTo(queueManager.waveQueue(userFolder, waveName).id, null)
                restoredPositionMs.value = active?.queue?.positionMs ?: 0L
            }
            loadUserState(userFolder, restoreSession = false)
        }
    }

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
                playerRepository.dropStandby()
            }
            wave.fetchMore()
        }
    }

    /**
     * A wave by [track]: the track plays (on, if it already does) and similar ones follow, inside the WAVE
     * queue (D40). What played before is kept, so [undoTrackWave] can go back to it.
     */
    fun startTrackWave(track: TrackDetailsDto) {
        if (track.isRadio || isOffline()) {
            if (isOffline()) showMessage(context.getString(R.string.msg_offline_no_downloads))
            return
        }
        returnPoint = null
        playOp { startTrackWaveNow(track) }
    }

    private suspend fun startTrackWaveNow(track: TrackDetailsDto) {
        val left = active
        val current = currentTrackNow()
        beforeTrackWave = BeforeTrackWave(left?.queue?.id, left?.cursorEntry?.entryId, progress.value, _waveSeed.value)
        wave.switchTo(WaveQueue.trackStation(track.idString))
        _waveSeed.value = track
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
            playerRepository.dropStandby()
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
        _waveSeed.value = null
        beforeTrackWave = null
        queueOp {
            val current = currentTrackNow()?.takeIf { !it.isRadio }
            val saved = cacheManager.loadWaveQueue(activeUserFolder).filterNot { it.idString == current?.idString }
            wave.switchTo(WaveQueue.MY_WAVE, saved)
            _waveTuning.value = WaveTuning()
            loadWaveTuning()
            val waveId = waveQueueId()
            queueManager.restartDynamic(waveId, seed = null, keepUpToCursor = true, tracks = saved)
            if (active?.queue?.id == waveId && liveStation.value == null) {
                playerRepository.dropStandby()
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
        _waveSeed.value = null
        playOp { undoTrackWaveNow(before) }
    }

    private suspend fun undoTrackWaveNow(before: BeforeTrackWave) {
        val current = currentTrackNow()
        val waveId = waveQueueId()
        wave.switchTo(WaveQueue.MY_WAVE, cacheManager.loadWaveQueue(activeUserFolder))
        _waveTuning.value = WaveTuning()
        loadWaveTuning()
        queueManager.restartDynamic(waveId, seed = null, keepUpToCursor = true, tracks = wave.tracks.value)
        val leftQueue = before.queueId
        if (leftQueue != null && leftQueue != waveId && active?.queue?.id == waveId) queueManager.switchTo(leftQueue, progress.value)
        val queue = active ?: return
        val previous = before.entryId?.let { id -> queue.entries.firstOrNull { it.entryId == id } }
        if (previous != null && previous.ref.id != current?.idString) {
            // Started from a menu on another track: the track that played before comes back where it was
            startEntry(previous.entryId, crossfade = true, startPositionMs = before.positionMs)
        } else {
            if (previous != null) queueManager.moveCursor(previous.entryId, progress.value)
            playerRepository.dropStandby()
            prepareNextTrackInQueue()
            persistSession()
        }
    }

    // endregion

    fun setSleepTimer(minutes: Int) = sleep.start(minutes)

    fun setSleepAtEndOfTrack() = sleep.atEndOfTrack()

    fun cancelSleepTimer() = sleep.cancel()

    /** "Without explicit lyrics": such tracks are passed over on the way; a track the user picks still plays. */
    private fun skippedOnTheWay(track: TrackDetailsDto): Boolean = skipExplicit.value && track.isExplicit

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

    /** A request to start something: a later one, or a pause, supersedes it before it runs. */
    private fun playOp(block: suspend () -> Unit) {
        val request = ++startRequestId
        queueOp { if (request == startRequestId) block() }
    }

    private fun currentTrackNow(): TrackDetailsDto? = liveStation.value ?: active?.cursorTrack

    /** The track last, so whoever waits for it finds the source and the list already there. */
    private fun refreshDerivedState() {
        val queue = active
        val station = liveStation.value
        _currentSource.value = sourceOf(queue, station)
        _currentPlaylist.value = queue?.tracks.orEmpty()
        _currentTrack.value = station ?: queue?.cursorTrack
    }

    private fun sourceOf(queue: ActiveQueue?, station: TrackDetailsDto?): PlaylistSource {
        if (station != null) return PlaylistSource.RADIO
        if (queue == null || queue.cursorTrack == null) return PlaylistSource.NONE
        if (queue.queue.kind == QueueKind.WAVE) return PlaylistSource.VIBE
        return PlaylistSource.entries.firstOrNull { it.name.equals(queue.queue.origin, ignoreCase = true) } ?: PlaylistSource.LIBRARY
    }

    /** The account's My Wave queue, created empty on first use. */
    private suspend fun waveQueueId(): Long = queueManager.waveQueue(activeUserFolder, waveName).id

    /** Replaces the active queue with [tracks] and plays [startId] (or the first); My Wave is never replaced. */
    private suspend fun playFromNow(tracks: List<TrackDetailsDto>, origin: String?, startId: String?, crossfade: Boolean, startPositionMs: Long) {
        val left = active
        if (left?.queue?.kind == QueueKind.WAVE) {
            // The wave is not editable: the list goes to the user's latest own queue, or a new one
            val target = queueManager.queues.value
                .filter { it.queue.kind == QueueKind.STATIC }
                .maxByOrNull { it.queue.updatedAt }?.queue?.id
                ?: queueManager.create(mainName).id
            queueManager.switchTo(target, progress.value.takeIf { liveStation.value == null })
        }
        val queue = queueManager.replaceActive(tracks, origin, startId?.let { TrackRef.yandex(it) }, nameIfNew = mainName)
        val entry = queue.cursorEntry ?: return
        startEntry(entry.entryId, crossfade, startPositionMs)
    }

    /** Moves the cursor to [entryId] and starts its track: the one place a queue entry is played from. */
    private suspend fun startEntry(entryId: Long, crossfade: Boolean, startPositionMs: Long) {
        val request = ++startRequestId
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

        val upcoming = queue.tracks.drop(index + 1).take(3).map { it.idString }
        if (upcoming.isNotEmpty()) preloadManager.preloadTracks(upcoming)
        queue.tracks.getOrNull(index + 1)?.let { prepareStandby(it) }

        if (queue.queue.kind == QueueKind.WAVE) {
            // Continuous queue auto-refill: fewer than 20 tracks left in My Wave
            if (queue.size - index <= 20) wave.fetchMore()
            sendRotorFeedback("trackStarted", trackId)
        }

        resolveAndPlay(track, crossfade, startPositionMs)
    }

    private suspend fun playNextNow(isAuto: Boolean) {
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
            if (first != null) startEntry(first.entryId, crossfade = true, startPositionMs = 0L) else playAllLikedTracks()
            return
        }
        // A manual skip must not leave the old track playing while the next stream is resolved (D36): it
        // fades out now and the play glyph shows loading until the next one sounds. Automatic transitions
        // keep the user's crossfade.
        if (!isAuto) playerRepository.fadeOutForSkip()
        if (returnFromOfflineMixNow()) return

        val currentId = current.idString
        if (queue.queue.kind == QueueKind.WAVE) {
            val playedSeconds = progress.value / 1000.0
            sendRotorFeedback(if (isAuto) "trackFinished" else "skip", currentId, playedSeconds)
        }

        when (val advance = nextAdvance(queue, manual = !isAuto)) {
            is Advance.To -> startEntry(queue.entries[advance.index].entryId, crossfade = true, startPositionMs = 0L)
            Advance.FetchMore -> {
                if (isOffline()) {
                    skipToNextOnDeviceNow(current)
                    return
                }
                // End of the wave: fetch more instead of stopping; the batch joins the queue before this runs
                val request = ++startRequestId
                wave.fetchMore { _ ->
                    if (request != startRequestId) return@fetchMore
                    queueOp {
                        if (request != startRequestId) return@queueOp
                        val updated = active ?: return@queueOp
                        val following = nextAdvance(updated, manual = !isAuto) as? Advance.To
                        when {
                            following != null -> startEntry(updated.entries[following.index].entryId, crossfade = true, startPositionMs = 0L)
                            updated.entries.isNotEmpty() -> startEntry(updated.entries.first().entryId, crossfade = true, startPositionMs = 0L)
                            else -> playAllLikedTracks()
                        }
                    }
                }
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
            queueManager.appendFetched(waveId, wave.tracks.value)
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
            queueManager.appendFetched(waveId, wave.tracks.value)
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
            wave.switchTo(station, if (seed != null) queue.tracks.drop(queue.cursorIndex + 1) else cacheManager.loadWaveQueue(activeUserFolder))
            _waveTuning.value = WaveTuning()
            loadWaveTuning()
        }
        _waveSeed.value = seed?.let { id -> queue.tracks.firstOrNull { it.idString == id } }
    }

    private fun leaveStation() {
        if (liveStation.value == null) return
        liveStation.value = null
        scope.launch { cacheDao.deleteValue(CacheDatabase.SHARED, LIVE_STATION) }
    }

    // endregion

    private fun resolveAndPlay(track: TrackDetailsDto, crossfade: Boolean, startPositionMs: Long) {
        currentPlayJob?.cancel()
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
            playerRepository.playTrack(trackId, station.streamUrl, title, artist, coverUrl, crossfade, 0L)
            return
        }

        val downloaded = preloadManager.hasPlayableCopy(trackId)
        if (downloaded) {
            publishQuality(trackId)
            streamingTrackId = null
            playingCopyId = null
            playerRepository.playTrack(trackId, "", title, artist, coverUrl, crossfade, startPositionMs)
            return
        }
        when (RoadPlan.originFor(currentLink(), link.mayTryStream(), reserve.has(trackId))) {
            TrackOrigin.UNAVAILABLE -> {
                _isStarting.value = false
                queueOp { skipToNextOnDeviceNow(track) }
                return
            }
            TrackOrigin.COPY -> {
                playCopy(track, crossfade, startPositionMs)
                return
            }
            TrackOrigin.STREAM -> Unit
        }

        // Instant start when the URL is already resolved
        val fastUrl = preloadManager.getResolvedUrl(trackId)
        if (fastUrl != null) {
            playStreamOrEqualCopy(track, fastUrl, crossfade, startPositionMs)
            return
        }

        currentPlayJob = scope.launch {
            val url = preloadManager.getOrFetchUrl(trackId)
                ?: run {
                    delay(URL_RETRY_DELAY_MS)
                    preloadManager.getOrFetchUrl(trackId)
                }
            if (url != null) {
                playStreamOrEqualCopy(track, url, crossfade, startPositionMs)
            } else {
                Log.e(TAG, "Failed to resolve mp3 URL for track $trackId")
                reportTrouble()
                if (currentTrackNow()?.idString != trackId) return@launch
                onPlaybackFailure(PlaybackFailure(trackId, startPositionMs, "URL_RESOLVE"))
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
        playerRepository.dropStandby()
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
    private fun playStreamOrEqualCopy(track: TrackDetailsDto, url: String, crossfade: Boolean, startPositionMs: Long) {
        val trackId = track.idString
        val copy = reserve.qualityOf(trackId)
        if (copy != null && RoadPlan.copyIsEnough(preloadManager.qualityOf(trackId), copy)) {
            playCopy(track, crossfade, startPositionMs)
            return
        }
        publishQuality(trackId)
        streamingTrackId = trackId
        playingCopyId = null
        playerRepository.playTrack(trackId, url, track.title, track.artists.firstOrNull()?.name, coverUrlFor(track), crossfade, startPositionMs)
    }

    private fun playCopy(track: TrackDetailsDto, crossfade: Boolean, startPositionMs: Long) {
        val trackId = track.idString
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
        playerRepository.playTrack(trackId, file.toUri().toString(), track.title, track.artists.firstOrNull()?.name, coverUrlFor(track), crossfade, startPositionMs)
    }

    /** The music has stood still long enough to matter: go on from the copy if there is one, and find out why. */
    private fun onStreamStalled(track: TrackDetailsDto) {
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

    private fun upcomingTracks(): List<TrackDetailsDto> = active?.let { it.tracks.drop(it.cursorIndex + 1) }.orEmpty()

    /**
     * First failure of a track: retry it once with a fresh URL from the same position (expired links
     * after a long pause). Repeated failure: skip. Several broken tracks in a row: stop and tell the user.
     */
    private fun onPlaybackFailure(failure: PlaybackFailure) {
        val current = currentTrackNow() ?: return
        if (failure.trackId != current.idString) return // stale event from a track we already left

        preloadManager.evictUrl(failure.trackId)
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
            weakLink = _linkState.value == LinkState.WEAK
        )
        journal.record(PlaybackJournal.Kind.ERROR, current.title, "${failure.reason} at ${failure.positionMs / 1000} s → $action")
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
            .map { it.track }
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
            ?: queueManager.create(context.getString(R.string.queue_offline_mix_name)).id
        offlineMixQueueId = mixId
        if (active?.queue?.id != mixId) queueManager.switchTo(mixId, progress.value.takeIf { active != null && liveStation.value == null })
        playFromNow(mix, OFFLINE_MIX_ORIGIN, mix.first().idString, crossfade = true, startPositionMs = 0L)
        returnPoint = point
    }

    /** The next track after [from] that is on the device, otherwise the mix of what is. */
    private suspend fun skipToNextOnDeviceNow(from: TrackDetailsDto) {
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

        val next = nextAdvance(queue, manual = false) as? Advance.To ?: return
        prepareStandby(queue.tracks[next.index])
    }

    private fun prepareStandby(track: TrackDetailsDto) {
        if (track.isRadio) return
        scope.launch {
            val nextId = track.idString
            val url = when {
                preloadManager.hasPlayableCopy(nextId) -> ""
                RoadPlan.originFor(currentLink(), link.mayTryStream(), reserve.has(nextId)) == TrackOrigin.COPY -> copyUri(nextId) ?: return@launch
                currentLink() == LinkState.NONE -> return@launch
                else -> preloadManager.getOrFetchUrl(nextId)?.let { stream ->
                    val copy = reserve.qualityOf(nextId)
                    if (copy != null && RoadPlan.copyIsEnough(preloadManager.qualityOf(nextId), copy)) copyUri(nextId) ?: stream else stream
                } ?: return@launch
            }
            playerRepository.prepareNextTrack(nextId, url, track.title, track.artists.firstOrNull()?.name, coverUrlFor(track))
        }
    }

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

    private fun copyUri(trackId: String): String? = reserve.fileOf(trackId)?.toUri()?.toString()

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

    private fun stepRadioStation(current: TrackDetailsDto, step: Int) {
        val stations = radioRepository.getBuiltinStations()
        if (stations.isEmpty()) return
        val index = stations.indexOfFirst { "radio_${it.id}" == current.idString }
        if (index == -1) return
        playRadioStation(stations[(index + step + stations.size) % stations.size])
    }

    private fun stationFor(trackId: String): RadioStation? =
        radioRepository.getBuiltinStations().firstOrNull { "radio_${it.id}" == trackId }

    private fun radioTrackFor(station: RadioStation) = TrackDetailsDto(
        id = kotlinx.serialization.json.JsonPrimitive("radio_${station.id}"),
        title = station.name,
        artists = listOf(ArtistDto(id = kotlinx.serialization.json.JsonPrimitive("0"), name = context.getString(R.string.radio_live_frequency, station.frequency))),
        coverUri = station.iconRes?.let { "android.resource://${BuildConfig.APPLICATION_ID}/$it" } ?: station.logoUrl.ifEmpty { null },
        durationMs = 0,
        isRadio = true
    )

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
        val queue = active
        if (queue != null) {
            // A wave by a track goes on from where it was, with its own upcoming tracks
            followWaveStation(queue)
            // Shown paused, like Apple Music: Play continues from the saved position.
            restoredPositionMs.value = queue.queue.positionMs
        }
        val stationId = cacheDao.getValue(CacheDatabase.SHARED, LIVE_STATION) ?: return
        val station = radioRepository.getBuiltinStations().firstOrNull { it.id == stationId } ?: return
        liveStation.value = radioTrackFor(station)
        restoredPositionMs.value = 0L
    }

    private fun coverUrlFor(track: TrackDetailsDto): String? = track.coverUri?.let { uri ->
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
        /** `cache_values` key (owner [CacheDatabase.SHARED]) of the FM station on air, for the restore. */
        const val LIVE_STATION = "live_station"
        /** The origin that marks the app-made queue of the offline mix. */
        const val OFFLINE_MIX_ORIGIN = "OFFLINE_MIX"
        private const val TAG = "PlaybackController"
        private const val RECENT_LIMIT = 100
        private const val URL_RETRY_DELAY_MS = 700L
        private const val SNAPSHOT_INTERVAL_MS = 15_000L
        private const val RESUMPTION_START_TIMEOUT_MS = 4_000L
        /** Longest a stream may keep buffering before we treat the stall as a failure and recover. */
        private const val BUFFERING_TIMEOUT_MS = 15_000L
        /** The player's buffer is empty and the music stands: the moment to look at the link. */
        private const val LINK_STALL_MS = 6_000L
        private const val SERVER_CHECK_INTERVAL_MS = 30_000L
        /** Long enough for a slow link to answer: a weak link must not read as no link. */
        private const val SERVER_CHECK_TIMEOUT_MS = 4_000
    }
}
