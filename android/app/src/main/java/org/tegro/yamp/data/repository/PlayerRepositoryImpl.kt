package org.tegro.yamp.data.repository

import androidx.core.net.toUri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import org.tegro.yamp.data.network.TrackDownloadManager
import org.tegro.yamp.data.network.TrackPreloadManager
import org.tegro.yamp.domain.repository.PlaybackFailure
import org.tegro.yamp.domain.repository.PlayerRepository
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Dual-ExoPlayer engine. One player is active (audible), the other is a standby used for
 * preloading the next track and as the incoming side of a crossfade.
 *
 * All player access happens on the main thread.
 */
class PlayerRepositoryImpl(
    private val playerA: ExoPlayer,
    private val playerB: ExoPlayer,
    private val downloadManager: TrackDownloadManager,
    private val preloadManager: TrackPreloadManager,
    private val wakeLockHelper: org.tegro.yamp.data.player.PlaybackWakeLockHelper,
    /** Where player B's audio session comes from, and whether it is its own one (Settings → Playback). */
    private val audioSessions: AudioSessionPolicy? = null,
    private val journal: org.tegro.yamp.data.player.PlaybackJournal? = null
) : PlayerRepository {

    private fun note(kind: org.tegro.yamp.data.player.PlaybackJournal.Kind, title: CharSequence? = null, detail: String? = null, valueMs: Long = 0L) {
        journal?.record(kind, title?.toString(), detail, valueMs)
    }

    /** When the active player ran dry mid-track (buffering with sound expected), to measure the stall. */
    private var stalledSince: Long? = null

    /**
     * Player A always uses [shared]; player B uses [own] while [separate] says so, otherwise [shared].
     * The change is applied when player B is idle, or at its next stop — never under a playing track.
     */
    class AudioSessionPolicy(
        val shared: Int,
        val own: Int,
        val separate: Flow<Boolean>,
        val onSeparateChanged: (Boolean) -> Unit
    )

    private val _currentTrackId = MutableStateFlow<String?>(null)
    override val currentTrackId: StateFlow<String?> = _currentTrackId.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    override val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private val _progress = MutableStateFlow(0L)
    override val progress: StateFlow<Long> = _progress.asStateFlow()

    // Buffered so that events are never dropped while the collector is busy.
    private val _trackEndedEvent = MutableSharedFlow<Unit>(extraBufferCapacity = EVENT_BUFFER)
    override val trackEndedEvent: Flow<Unit> = _trackEndedEvent.asSharedFlow()

    private val _prepareNextTrackEvent = MutableSharedFlow<Unit>(extraBufferCapacity = EVENT_BUFFER)
    override val prepareNextTrackEvent: Flow<Unit> = _prepareNextTrackEvent.asSharedFlow()

    private val _skipNextEvent = MutableSharedFlow<Unit>(extraBufferCapacity = EVENT_BUFFER)
    override val skipNextEvent: Flow<Unit> = _skipNextEvent.asSharedFlow()

    private val _skipPrevEvent = MutableSharedFlow<Unit>(extraBufferCapacity = EVENT_BUFFER)
    override val skipPrevEvent: Flow<Unit> = _skipPrevEvent.asSharedFlow()

    private val _playbackErrorEvent = MutableSharedFlow<PlaybackFailure>(extraBufferCapacity = EVENT_BUFFER)
    override val playbackErrorEvent: Flow<PlaybackFailure> = _playbackErrorEvent.asSharedFlow()

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "Unhandled player coroutine error", e)
        }
    )

    // Dual-Player ping-pong: 0 -> playerA, 1 -> playerB
    private var activePlayerIndex = 0

    private val activePlayer: ExoPlayer
        get() = if (activePlayerIndex == 0) playerA else playerB

    private val standbyPlayer: ExoPlayer
        get() = if (activePlayerIndex == 0) playerB else playerA

    private val _activePlayerState = MutableStateFlow<ExoPlayer>(playerA)
    override val activePlayerState: StateFlow<ExoPlayer> = _activePlayerState.asStateFlow()

    /** Player fading out during a crossfade; null when no crossfade is running. */
    private var outgoingPlayer: ExoPlayer? = null

    /**
     * The incoming track of a crossfade is held silent until it has enough to sound without a stop; it still
     * counts as playing, so nothing on the screen flickers to "paused" meanwhile.
     */
    private var awaitingIncoming = false

    /**
     * The user skipped and the next track is still being resolved: nothing may sound, but the engine keeps
     * reporting "playing, loading". Reporting nothing playing in the gap made the service abandon audio focus
     * and ask for it again at the next start — a request a head unit sometimes refuses, which pauses the music.
     */
    private var awaitingSkipTarget = false
    private var skipWaitJob: Job? = null

    @Volatile
    private var bufferedAhead = Long.MAX_VALUE
    override val bufferedAheadMs: Long get() = bufferedAhead
    private var crossfadeJob: Job? = null
    private var playJob: Job? = null
    private var preloadedTrackId: String? = null

    // Volume requested by the crossfade, before ducking is applied.
    private val logicalVolume = floatArrayOf(1f, 1f)
    private var duckFactor = 1f
    @Volatile
    private var trackGains: Map<String, Float> = emptyMap()

    private fun gainOf(player: ExoPlayer): Float = player.currentMediaItem?.mediaId?.let { trackGains[it] } ?: 1f

    /** The volume the player should have now: crossfade position × ducking × the track's normalization. */
    private fun applyVolume(player: ExoPlayer) {
        player.volume = (logicalVolume[indexOf(player)] * duckFactor * gainOf(player)).coerceIn(0f, 1f)
    }

    override fun setTrackGains(gains: Map<String, Float>) {
        scope.launch {
            trackGains = gains
            applyVolume(playerA)
            applyVolume(playerB)
        }
    }
    private var crossfadeDurationMs = CROSSFADE_DURATION_MS

    private var lastSeekTimestamp = 0L
    private var pendingSeekPosition = -1L
    private var hasEmittedPrepareForCurrent = false
    private var hasEmittedTrackEndedForCurrent = false

    /** Session id player B should have; applied by [applyPendingAudioSession] when B is not busy. */
    private var pendingSessionIdB: Int? = null

    init {
        setupPlayerListener(playerA, 0)
        setupPlayerListener(playerB, 1)

        audioSessions?.let { policy ->
            scope.launch {
                policy.separate.collect { separate ->
                    val wanted = if (separate && policy.own > 0) policy.own else policy.shared
                    if (wanted <= 0) return@collect
                    policy.onSeparateChanged(separate && policy.own > 0)
                    pendingSessionIdB = wanted
                    applyPendingAudioSession(playerB)
                }
            }
        }

        scope.launch {
            while (isActive) {
                val currentP = activePlayer
                if (currentP.isPlaying) {
                    // All of the track loaded counts as plenty: the end of a track is not a reason to hold back
                    val loadedToTheEnd = currentP.duration > 0 && currentP.bufferedPosition >= currentP.duration - 500
                    bufferedAhead = if (loadedToTheEnd) Long.MAX_VALUE else currentP.totalBufferedDuration
                    journal?.addPlayed(PROGRESS_INTERVAL_MS)
                    updateProgress(currentP)
                    checkTransitionTriggers(currentP)
                    delay(PROGRESS_INTERVAL_MS)
                } else {
                    delay(IDLE_INTERVAL_MS)
                }
            }
        }
    }

    /** Gives [player] its pending audio session if it is player B and holds nothing right now. */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun applyPendingAudioSession(player: ExoPlayer) {
        val wanted = pendingSessionIdB ?: return
        if (player !== playerB || player.mediaItemCount > 0 && player.playbackState != Player.STATE_IDLE) return
        pendingSessionIdB = null
        if (player.audioSessionId != wanted) runCatching { player.setAudioSessionId(wanted) }
            .onFailure { Log.w(TAG, "Audio session not applied", it) }
    }

    private fun updateProgress(player: ExoPlayer) {
        val position = player.currentPosition
        if (SystemClock.elapsedRealtime() - lastSeekTimestamp < 600L && pendingSeekPosition >= 0L) {
            // Ignore stale positions reported right after a seek until the player catches up.
            if (abs(position - pendingSeekPosition) < 400L) {
                pendingSeekPosition = -1L
                _progress.value = position
            }
        } else {
            pendingSeekPosition = -1L
            _progress.value = position
        }
    }

    private fun checkTransitionTriggers(player: ExoPlayer) {
        val duration = player.duration
        val position = player.currentPosition
        if (duration <= 8_000L || position <= 0L || outgoingPlayer != null) return
        val remaining = duration - position

        // 1. Warm standby preparation: a minute ahead once the playing track is loaded to its end, so the two
        //    downloads never compete on a slow link (D36); at the latest at 15 seconds remaining
        val loadedToTheEnd = bufferedAhead == Long.MAX_VALUE
        val prepareNow = remaining <= PREPARE_STANDBY_THRESHOLD_MS && loadedToTheEnd || remaining <= PREPARE_STANDBY_LATEST_MS
        if (prepareNow && !hasEmittedPrepareForCurrent) {
            hasEmittedPrepareForCurrent = true
            _prepareNextTrackEvent.tryEmit(Unit)
        }

        // 2. Early trigger so the crossfade overlaps the last seconds of the track
        if (crossfadeDurationMs > 0L && remaining in 1L..crossfadeDurationMs && !hasEmittedTrackEndedForCurrent) {
            hasEmittedTrackEndedForCurrent = true
            wakeLockHelper.acquireTransitionLock(30_000L)
            _trackEndedEvent.tryEmit(Unit)
        }
    }

    private fun setupPlayerListener(player: ExoPlayer, playerIdx: Int) {
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                // A new track brings its own normalization
                applyVolume(player)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                refreshPlaybackState()
                if (isPlaying && playerIdx == activePlayerIndex && outgoingPlayer == null) {
                    wakeLockHelper.releaseTransitionLock()
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                refreshPlaybackState()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                refreshPlaybackState()
                if (playerIdx == activePlayerIndex && outgoingPlayer == null) {
                    // A stall: the sounding track ran dry (not a start, not a seek)
                    val ranDry = playbackState == Player.STATE_BUFFERING && player.playWhenReady && player.currentPosition > 1_000L
                    if (ranDry && stalledSince == null) {
                        stalledSince = SystemClock.elapsedRealtime()
                    } else if (playbackState == Player.STATE_READY) {
                        stalledSince?.let { note(org.tegro.yamp.data.player.PlaybackJournal.Kind.STALL, player.mediaMetadata.title, valueMs = SystemClock.elapsedRealtime() - it) }
                        stalledSince = null
                    }
                }
                if (playbackState == Player.STATE_ENDED && playerIdx == activePlayerIndex && outgoingPlayer == null) {
                    Log.d(TAG, "Player[$playerIdx] reached STATE_ENDED, crossfadeRequested=$hasEmittedTrackEndedForCurrent")
                    // When the early crossfade trigger already fired, the controller is advancing — don't skip twice.
                    if (!hasEmittedTrackEndedForCurrent) {
                        hasEmittedTrackEndedForCurrent = true
                        wakeLockHelper.acquireTransitionLock(30_000L)
                        _trackEndedEvent.tryEmit(Unit)
                    }
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.e(TAG, "ExoPlayer error on player[$playerIdx]: ${error.errorCodeName}", error)
                note(org.tegro.yamp.data.player.PlaybackJournal.Kind.ERROR, player.mediaMetadata.title, "player ${error.errorCodeName}" + (if (player === outgoingPlayer) " (outgoing)" else if (playerIdx != activePlayerIndex) " (standby)" else ""))
                player.currentMediaItem?.mediaId?.let { preloadManager.evictUrl(it) }
                when {
                    player === outgoingPlayer -> {
                        // The fading-out track broke; just drop it.
                        clearPlayer(player)
                        outgoingPlayer = null
                        setVolume(activePlayer, 1f)
                    }
                    playerIdx == activePlayerIndex -> {
                        val failedId = _currentTrackId.value ?: player.currentMediaItem?.mediaId ?: return
                        wakeLockHelper.releaseTransitionLock()
                        _playbackErrorEvent.tryEmit(
                            PlaybackFailure(failedId, player.currentPosition.coerceAtLeast(0L), error.errorCodeName)
                        )
                    }
                    else -> {
                        // A preloaded standby went stale — never hand it to a transition.
                        if (preloadedTrackId == player.currentMediaItem?.mediaId) preloadedTrackId = null
                        clearPlayer(player)
                    }
                }
                refreshPlaybackState()
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK && playerIdx == activePlayerIndex) {
                    pendingSeekPosition = -1L
                    _progress.value = newPosition.positionMs
                }
            }
        })
    }

    private fun refreshPlaybackState() {
        val p = activePlayer
        val wantsToPlay = awaitingSkipTarget || (p.playWhenReady || awaitingIncoming) &&
            p.playerError == null &&
            p.mediaItemCount > 0 &&
            p.playbackState != Player.STATE_ENDED
        _isPlaying.value = wantsToPlay
        // Only a real STATE_BUFFERING counts as loading: a paused-but-wants-to-play limbo (focus loss) is not a
        // spinner, otherwise the indicator sticks when playback is interrupted mid-start.
        _isBuffering.value = awaitingSkipTarget || wantsToPlay && p.playbackState == Player.STATE_BUFFERING
    }

    override fun getPlayer(): Player = activePlayer

    private suspend fun resolvePlayUri(trackId: String, url: String): String? {
        val localFile = withContext(Dispatchers.IO) { preloadManager.playableCopy(trackId) }
        if (localFile != null) return android.net.Uri.fromFile(localFile).toString()
        // A copy from the road reserve was chosen on purpose: a resolved stream must not replace it
        if (url.startsWith("file:")) return url
        (preloadManager.getResolvedUrl(trackId) ?: url).takeIf { it.isNotBlank() }?.let { return it }
        // No link was asked for because the track counted as downloaded, and its copy is gone (the card was
        // taken out): it plays from the network instead of failing (upstream Н2)
        return if (trackId.startsWith("radio_")) null else preloadManager.getOrFetchUrl(trackId)
    }

    private fun buildMediaItem(trackId: String, uri: String, title: String?, artist: String?, coverUrl: String?): MediaItem {
        val mediaMetadata = MediaMetadata.Builder()
            .setTitle(title ?: "Unknown Track")
            .setDisplayTitle(title ?: "Unknown Track")
            .setArtist(artist ?: "Unknown Artist")
            .setArtworkUri(coverUrl?.toUri())
            .build()
        return MediaItem.Builder()
            .setMediaId(trackId)
            .setUri(uri)
            .setMediaMetadata(mediaMetadata)
            .build()
    }

    override fun prepareNextTrack(trackId: String, url: String, title: String?, artist: String?, coverUrl: String?) {
        if (preloadedTrackId == trackId || outgoingPlayer != null) return
        scope.launch {
            val playUri = resolvePlayUri(trackId, url) ?: return@launch
            // State may have changed while the URL was resolving.
            if (outgoingPlayer != null || _currentTrackId.value == trackId) return@launch

            val standby = standbyPlayer
            standby.stop()
            applyPendingAudioSession(standby)
            setVolume(standby, 0f)
            standby.playWhenReady = false
            standby.setMediaItem(buildMediaItem(trackId, playUri, title, artist, coverUrl), 0L)
            standby.prepare()
            preloadedTrackId = trackId
            Log.d(TAG, "Preloaded warm standby player with trackId = $trackId")
        }
    }

    override fun dropStandby() {
        scope.launch {
            if (outgoingPlayer != null) return@launch
            preloadedTrackId = null
            hasEmittedPrepareForCurrent = false
            clearPlayer(standbyPlayer)
        }
    }

    override fun playTrack(
        trackId: String,
        url: String,
        title: String?,
        artist: String?,
        coverUrl: String?,
        crossfade: Boolean,
        startPositionMs: Long
    ) {
        playJob?.cancel()
        playJob = scope.launch {
            val playUri = resolvePlayUri(trackId, url)
            if (playUri == null) {
                Log.w(TAG, "playUri is blank for track $trackId")
                note(org.tegro.yamp.data.player.PlaybackJournal.Kind.ERROR, title, "no stream URL")
                _playbackErrorEvent.tryEmit(PlaybackFailure(trackId, startPositionMs, "EMPTY_URL"))
                return@launch
            }
            startTransition(buildMediaItem(trackId, playUri, title, artist, coverUrl), crossfade, startPositionMs)
        }
    }

    /** Switches the audible track on the main thread. */
    private suspend fun startTransition(item: MediaItem, crossfade: Boolean, startPositionMs: Long) {
        val trackId = item.mediaId
        skipTargetArrived()
        // A crossfade that is still running (rapid skipping): fade its outgoing side out quickly instead of
        // cutting it, and keep the current track's volume to fade from.
        fadeOutRunningCrossfade()

        hasEmittedPrepareForCurrent = false
        hasEmittedTrackEndedForCurrent = false
        pendingSeekPosition = -1L
        lastSeekTimestamp = 0L
        _currentTrackId.value = trackId
        _progress.value = startPositionMs
        stalledSince = null
        note(org.tegro.yamp.data.player.PlaybackJournal.Kind.START, item.mediaMetadata.title, if (crossfade) "crossfade" else "cut", startPositionMs)

        val current = activePlayer
        val incoming = standbyPlayer
        val reusePreloaded = preloadedTrackId == trackId &&
            incoming.currentMediaItem?.mediaId == trackId &&
            incoming.playerError == null &&
            incoming.playbackState != Player.STATE_IDLE
        preloadedTrackId = null

        if (reusePreloaded) {
            if (startPositionMs > 0L) incoming.seekTo(startPositionMs)
        } else {
            incoming.stop()
            applyPendingAudioSession(incoming)
            incoming.setMediaItem(item, startPositionMs)
            incoming.prepare()
        }

        val isCurrentAudible = current.isPlaying && current.playerError == null
        activePlayerIndex = 1 - activePlayerIndex
        _activePlayerState.value = incoming

        if (skipFading === current && isCurrentAudible) {
            // The user skipped and the old track is still fading: the new one comes in under its tail at once.
            // The fade stops the outgoing player itself when it ends.
            outgoingPlayer = current
            setVolume(incoming, 1f)
            incoming.playWhenReady = true
        } else if (crossfade && isCurrentAudible && crossfadeDurationMs > 0L) {
            wakeLockHelper.acquireTransitionLock(30_000L)
            outgoingPlayer = current
            setVolume(incoming, 0f)
            // Silent and still until it can sound without a stop; the outgoing track plays its tail meanwhile
            awaitingIncoming = true
            incoming.playWhenReady = false
            crossfadeJob = scope.launch { runCrossfade(current, incoming) }
        } else {
            clearPlayer(current)
            setVolume(current, 1f)
            setVolume(incoming, 1f)
            incoming.playWhenReady = true
        }
        refreshPlaybackState()
    }

    /**
     * Keeps [outgoing] audible while [incoming] gets ready: ready to play, and with a few seconds in hand or all
     * of it loaded. Then blends them with an equal-power curve (cos² + sin² = 1) so the loudness stays even.
     * When the next track came too late and the previous one has already ended, it fades in quickly instead:
     * no silence, no slow quiet start.
     */
    private suspend fun runCrossfade(outgoing: ExoPlayer, incoming: ExoPlayer) {
        val waitStart = SystemClock.elapsedRealtime()
        while (incoming.playbackState != Player.STATE_READY) {
            if (incoming.playerError != null) {
                awaitingIncoming = false
                return // reported by the listener
            }
            if (SystemClock.elapsedRealtime() - waitStart > INCOMING_READY_TIMEOUT_MS) {
                awaitingIncoming = false
                incoming.playWhenReady = true
                val id = incoming.currentMediaItem?.mediaId ?: return
                Log.w(TAG, "Incoming track $id did not start in time")
                note(org.tegro.yamp.data.player.PlaybackJournal.Kind.HOLD_TIMEOUT, incoming.mediaMetadata.title)
                _playbackErrorEvent.tryEmit(PlaybackFailure(id, 0L, "READY_TIMEOUT"))
                return
            }
            delay(WAIT_STEP_MS)
        }
        // A little more in hand while the previous track still has its tail to play
        fun shortOfSound() = incoming.isLoading && incoming.totalBufferedDuration < CROSSFADE_HEADROOM_MS
        while (outgoing.isPlaying && shortOfSound() && SystemClock.elapsedRealtime() - waitStart < INCOMING_READY_TIMEOUT_MS) {
            delay(WAIT_STEP_MS)
        }

        val late = !outgoing.isPlaying
        awaitingIncoming = false
        incoming.playWhenReady = true
        refreshPlaybackState()
        note(if (late) org.tegro.yamp.data.player.PlaybackJournal.Kind.CROSSFADE_LATE else org.tegro.yamp.data.player.PlaybackJournal.Kind.CROSSFADE, incoming.mediaMetadata.title, valueMs = SystemClock.elapsedRealtime() - waitStart)

        if (late) {
            fade(incoming, from = 0f, to = 1f, durationMs = LATE_FADE_IN_MS)
        } else {
            equalPowerFade(outgoing, incoming, crossfadeDurationMs)
        }

        outgoingPlayer = null
        clearPlayer(outgoing)
        setVolume(outgoing, 1f)
        setVolume(incoming, 1f)
        wakeLockHelper.releaseTransitionLock()
    }

    /** [outgoing] down and [incoming] up over [durationMs], a step every [CROSSFADE_STEP_MS]: smooth to the ear. */
    private suspend fun equalPowerFade(outgoing: ExoPlayer, incoming: ExoPlayer, durationMs: Long) {
        val outStartVolume = logicalVolume[indexOf(outgoing)]
        alongTheClock(durationMs) { part ->
            val angle = part * (PI / 2.0)
            setVolume(outgoing, (outStartVolume * cos(angle)).toFloat().coerceIn(0f, 1f))
            setVolume(incoming, sin(angle).toFloat().coerceIn(0f, 1f))
        }
    }

    private suspend fun fade(player: ExoPlayer, from: Float, to: Float, durationMs: Long) {
        alongTheClock(durationMs) { part -> setVolume(player, (from + (to - from) * part).toFloat()) }
    }

    /**
     * Calls [step] with how far along [durationMs] the clock is (0..1], about every [CROSSFADE_STEP_MS], and
     * once more at the end. Paced by the clock, not by counting steps: a busy main thread makes the steps
     * coarser, never the fade longer.
     */
    private suspend fun alongTheClock(durationMs: Long, step: (Double) -> Unit) {
        val start = SystemClock.elapsedRealtime()
        while (true) {
            val part = ((SystemClock.elapsedRealtime() - start).toDouble() / durationMs).coerceAtMost(1.0)
            step(part)
            if (part >= 1.0) return
            delay(CROSSFADE_STEP_MS)
        }
    }

    /**
     * The outgoing player occupies the standby slot the next track needs, so it must be released first.
     * A [QUICK_FADE_MS] fade avoids an audible click; it is stopped even if this request is superseded.
     */
    private suspend fun fadeOutRunningCrossfade() {
        val out = outgoingPlayer ?: return
        crossfadeJob?.cancel()
        crossfadeJob = null
        awaitingIncoming = false
        if (skipFading === out) cancelSkipFade() // a skip tail: this quick fade takes over its end
        try {
            fade(out, from = logicalVolume[indexOf(out)], to = 0f, durationMs = QUICK_FADE_MS)
        } finally {
            if (outgoingPlayer === out) {
                clearPlayer(out)
                setVolume(out, 1f)
                outgoingPlayer = null
            }
        }
    }

    /** Ends a running crossfade immediately: the outgoing track is silenced, at most one track stays audible. */
    private fun finishCrossfadeNow(restoreActiveVolume: Boolean) {
        crossfadeJob?.cancel()
        crossfadeJob = null
        // An incoming track held silent for its crossfade plays now (a pause right after stops it again)
        if (awaitingIncoming) {
            awaitingIncoming = false
            activePlayer.playWhenReady = true
        }
        outgoingPlayer?.let { out ->
            clearPlayer(out)
            setVolume(out, 1f)
        }
        outgoingPlayer = null
        if (restoreActiveVolume) setVolume(activePlayer, 1f)
    }

    override fun fadeOutForSkip() {
        val player = activePlayer
        if (skipFading === player) return // skipped again while the fade runs: nothing new to do
        fadeJob?.cancel()
        expectSkipTarget()
        note(org.tegro.yamp.data.player.PlaybackJournal.Kind.SKIP, player.mediaMetadata.title, when {
            awaitingIncoming -> "during a hold"
            !player.isPlaying -> "not sounding (${player.playbackState})"
            else -> "fade"
        })
        if (skipFading != null) {
            // The tail of the previous skip is still fading as the outgoing side: it is not needed any more.
            cancelSkipFade()
            finishCrossfadeNow(restoreActiveVolume = false)
        }
        if (awaitingIncoming) {
            // A crossfade is holding the next track silent while the old one plays its tail, and the user skips
            // past the held track: its tail fades out as the skipped track, the held one is dropped.
            crossfadeJob?.cancel()
            crossfadeJob = null
            awaitingIncoming = false
            val tail = outgoingPlayer
            clearPlayer(player)
            setVolume(player, 1f)
            _currentTrackId.value = null
            _progress.value = 0L
            if (tail != null && tail.isPlaying) {
                skipFading = tail
                skipFadeJob = scope.launch {
                    fade(tail, from = logicalVolume[indexOf(tail)], to = 0f, durationMs = SKIP_FADE_MS)
                    skipFading = null
                    if (outgoingPlayer === tail) outgoingPlayer = null
                    clearPlayer(tail)
                    setVolume(tail, 1f)
                    wakeLockHelper.releaseTransitionLock()
                    refreshPlaybackState()
                }
            } else {
                finishCrossfadeNow(restoreActiveVolume = false)
            }
            refreshPlaybackState()
            return
        }
        if (!player.isPlaying || player.playerError != null) {
            // Nothing audible to fade (buffering, paused, ended): drop it now so the next track starts clean;
            // the engine keeps reporting "playing, loading" while the controller resolves the target.
            finishCrossfadeNow(restoreActiveVolume = false)
            clearPlayer(player)
            setVolume(player, 1f)
            _currentTrackId.value = null
            _progress.value = 0L
            refreshPlaybackState()
            return
        }
        skipFading = player
        skipFadeJob = scope.launch {
            // A crossfade that is still running: its outgoing side leaves quickly, the audible one fades below.
            fadeOutRunningCrossfade()
            fade(player, from = logicalVolume[indexOf(player)], to = 0f, durationMs = SKIP_FADE_MS)
            // Silent now. It keeps "playing" at zero volume so the media session and the notification keep
            // their item and state while the controller resolves the target (as the audible old track did
            // before). A target that never comes must not leave a silent track behind: give up after a while.
            val silentSince = SystemClock.elapsedRealtime()
            while (activePlayer === player && outgoingPlayer !== player && player.isPlaying &&
                SystemClock.elapsedRealtime() - silentSince < SKIP_SILENCE_MAX_MS
            ) delay(100)
            // Cancelled above means pause/seek/stop took the player over. From here the fade owns it.
            skipFading = null
            when {
                outgoingPlayer === player -> outgoingPlayer = null // the next track came in under the tail
                activePlayer === player -> {
                    // Nothing came yet: silence (still "playing, loading") until the controller starts something.
                    _currentTrackId.value = null
                    _progress.value = 0L
                }
            }
            clearPlayer(player)
            setVolume(player, 1f)
            refreshPlaybackState()
        }
    }

    private fun cancelSkipFade() {
        skipFadeJob?.cancel()
        skipFadeJob = null
        skipFading = null
    }

    /** From now until the next track starts the engine reports "playing, loading" (see [awaitingSkipTarget]). */
    private fun expectSkipTarget() {
        awaitingSkipTarget = true
        skipWaitJob?.cancel()
        // A target that never comes (the controller gave up without starting or stopping anything) must not
        // leave the engine "playing" for ever
        skipWaitJob = scope.launch {
            delay(SKIP_WAIT_MAX_MS)
            if (awaitingSkipTarget) {
                awaitingSkipTarget = false
                refreshPlaybackState()
            }
        }
    }

    private fun skipTargetArrived() {
        skipWaitJob?.cancel()
        skipWaitJob = null
        awaitingSkipTarget = false
    }

    override fun pause() {
        fadeJob?.cancel()
        skipTargetArrived()
        cancelSkipFade()
        finishCrossfadeNow(restoreActiveVolume = true)
        activePlayer.pause()
        refreshPlaybackState()
    }

    private var fadeJob: Job? = null
    /** Running quick fade of a track the user skipped away from (see [fadeOutForSkip]), and its player. */
    private var skipFadeJob: Job? = null
    private var skipFading: ExoPlayer? = null

    override fun fadeOutAndPause(durationMs: Long) {
        fadeJob?.cancel()
        fadeJob = scope.launch {
            finishCrossfadeNow(restoreActiveVolume = false)
            val player = activePlayer
            val start = logicalVolume[indexOf(player)]
            val steps = (durationMs / FADE_STEP_MS).toInt().coerceAtLeast(1)
            for (step in 1..steps) {
                setVolume(player, start * (1f - step.toFloat() / steps))
                delay(FADE_STEP_MS)
            }
            player.pause()
            setVolume(player, 1f)
            refreshPlaybackState()
        }
    }

    override fun resume(): Boolean {
        val player = activePlayer
        if (player.mediaItemCount == 0) return false
        if (player.playerError != null || player.playbackState == Player.STATE_IDLE) {
            // The stream is dead (e.g. the signed URL expired during a long pause): let the controller re-resolve it.
            val trackId = _currentTrackId.value ?: return false
            _playbackErrorEvent.tryEmit(PlaybackFailure(trackId, player.currentPosition.coerceAtLeast(0L), "RESUME_IDLE"))
            return true
        }
        if (player.playbackState == Player.STATE_ENDED) {
            triggerSkipNext()
            return true
        }
        setVolume(player, 1f)
        player.play()
        refreshPlaybackState()
        return true
    }

    override fun stop() {
        playJob?.cancel()
        skipTargetArrived()
        cancelSkipFade()
        finishCrossfadeNow(restoreActiveVolume = true)
        for (p in listOf(playerA, playerB)) {
            clearPlayer(p)
            setVolume(p, 1f)
        }
        preloadedTrackId = null
        hasEmittedPrepareForCurrent = false
        hasEmittedTrackEndedForCurrent = false
        wakeLockHelper.releaseTransitionLock()
        _currentTrackId.value = null
        _progress.value = 0L
        refreshPlaybackState()
    }

    override fun release() {
        scope.cancel()
        crossfadeJob?.cancel()
        skipFadeJob?.cancel()
        skipWaitJob?.cancel()
        playJob?.cancel()
        wakeLockHelper.releaseTransitionLock()
        playerA.release()
        playerB.release()
    }

    override fun seekTo(position: Long) {
        skipTargetArrived()
        cancelSkipFade()
        finishCrossfadeNow(restoreActiveVolume = true)
        val p = activePlayer
        lastSeekTimestamp = SystemClock.elapsedRealtime()
        pendingSeekPosition = position
        _progress.value = position
        p.seekTo(position)
        // Seeking back out of the crossfade window must allow the triggers to fire again.
        val duration = p.duration
        if (duration > 0L && duration - position > crossfadeDurationMs) hasEmittedTrackEndedForCurrent = false
        if (duration > 0L && duration - position > PREPARE_STANDBY_THRESHOLD_MS) hasEmittedPrepareForCurrent = false
    }

    override fun setDucked(ducked: Boolean) {
        duckFactor = if (ducked) DUCK_VOLUME else 1f
        applyVolume(playerA)
        applyVolume(playerB)
    }

    override fun setCrossfadeDuration(durationMs: Long) {
        crossfadeDurationMs = durationMs.coerceAtLeast(0L)
    }

    private fun indexOf(player: ExoPlayer) = if (player === playerA) 0 else 1

    /** Stops and empties [player]; a pending audio-session change is applied while it holds nothing. */
    private fun clearPlayer(player: ExoPlayer) {
        player.stop()
        player.clearMediaItems()
        applyPendingAudioSession(player)
    }

    private fun setVolume(player: ExoPlayer, volume: Float) {
        logicalVolume[indexOf(player)] = volume
        applyVolume(player)
    }

    override fun triggerSkipNext() {
        _skipNextEvent.tryEmit(Unit)
    }

    override fun triggerSkipPrev() {
        _skipPrevEvent.tryEmit(Unit)
    }

    companion object {
        private const val TAG = "PlayerRepositoryImpl"
        const val CROSSFADE_DURATION_MS = 3000L
        /** The next track is warmed up this long before the end, if the playing one is already loaded to its end. */
        const val PREPARE_STANDBY_THRESHOLD_MS = 60_000L
        /** …and at the latest this long before the end, whatever the buffer: the old rule. */
        const val PREPARE_STANDBY_LATEST_MS = 15_000L
        /** Sound the incoming track should have in hand before the crossfade starts (unless all of it loaded). */
        const val CROSSFADE_HEADROOM_MS = 5_000L
        /** When the previous track has already ended, the next one fades in this fast instead of crossfading. */
        const val LATE_FADE_IN_MS = 300L
        const val CROSSFADE_STEP_MS = 20L
        private const val WAIT_STEP_MS = 20L
        const val INCOMING_READY_TIMEOUT_MS = 8_000L
        const val DUCK_VOLUME = 0.2f
        const val QUICK_FADE_MS = 150L
        /** A manual skip silences the current track this fast; the next one starts under it or after it. */
        const val SKIP_FADE_MS = 500L
        /** How long a skipped track may stay silently loaded waiting for its successor. */
        const val SKIP_SILENCE_MAX_MS = 10_000L
        /** How long after a skip the engine reports "playing, loading" with nothing started. */
        const val SKIP_WAIT_MAX_MS = 30_000L
        private const val FADE_STEP_MS = 50L
        private const val PROGRESS_INTERVAL_MS = 100L
        private const val IDLE_INTERVAL_MS = 500L
        private const val EVENT_BUFFER = 16
    }
}
