package org.tegro.yamp.data.repository

import androidx.core.net.toUri
import android.os.SystemClock
import android.util.Log
import org.tegro.yamp.data.player.OutputGain
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import org.tegro.yamp.data.network.TrackDownloadManager
import org.tegro.yamp.domain.repository.PlaybackFailure
import org.tegro.yamp.domain.provider.ResolvedStream
import org.tegro.yamp.domain.repository.PlayerRepository
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Dual-ExoPlayer engine. One player is active (audible), the other is a standby used for
 * preloading the next track and as the incoming side of a crossfade.
 *
 * All player access happens on the main thread. Fades are applied to each player's samples through its [OutputGain]
 * (D98); a player's own `volume` is never changed and stays 1.
 */
class PlayerRepositoryImpl(
    private val playerA: ExoPlayer,
    private val playerB: ExoPlayer,
    /** [playerA]'s fade level, in its sink ([org.tegro.yamp.data.player.SampleGain]). */
    private val gainA: OutputGain,
    /** [playerB]'s. */
    private val gainB: OutputGain,
    private val downloadManager: TrackDownloadManager,
    private val wakeLockHelper: org.tegro.yamp.data.player.PlaybackWakeLockHelper,
    private val journal: org.tegro.yamp.data.player.PlaybackJournal? = null,
    /**
     * Every byte of a cache key is on disk (D86) — in the cache, or a converted stream's complete download (D91): an
     * unseekable stream seeks from there.
     */
    private val fullyCached: (String) -> Boolean = { false },
    /**
     * The share of a converted stream's download on disk (0..1), from the player's loaded ms and the track's length
     * ([org.tegro.yamp.data.player.StreamCache.downloadShare]): the seek bar's loaded part while seeking waits for it.
     */
    private val downloadShare: (key: String, bufferedMs: Long, durationMs: Long) -> Float = { _, _, _ -> 0f },
    /** The downloads of converted streams (D91): told what is handed out and what plays. */
    private val progressive: org.tegro.yamp.data.player.ProgressiveStreams = org.tegro.yamp.data.player.ProgressiveStreams.None,
    /** Where the sound goes now, for the journal's output findings (a car's Bluetooth, a USB amplifier, the speaker). */
    private val outputRoute: () -> String? = { null }
) : PlayerRepository, org.tegro.yamp.data.player.SessionPlayers {

    private fun note(kind: org.tegro.yamp.data.player.PlaybackJournal.Kind, title: CharSequence? = null, detail: String? = null, valueMs: Long = 0L) {
        journal?.record(kind, title?.toString(), detail, valueMs)
    }

    /** Underrun and start-timing lines for the journal (see [org.tegro.yamp.data.player.EngineDiagnostics]). */
    private val diagnostics = org.tegro.yamp.data.player.EngineDiagnostics(journal) { if (it === outgoingPlayer) "outgoing" else if (it === activePlayer) "active" else "standby" }

    /**
     * The output that stopped taking sound while the track plays, or the position that fell back with no seek: what the
     * journal could not see before (upstream 1.14.8: a car went silent in every track and the timer jumped back, with
     * an empty journal). Fed by the poll below; [outputWatchId] tells a new track from a jump.
     */
    private val outputWatch = org.tegro.yamp.data.player.OutputWatch()
    private var outputWatchId: String? = null

    /** When the active player ran dry mid-track (buffering with sound expected), to measure the stall. */
    private var stalledSince: Long? = null

    private val _currentTrackId = MutableStateFlow<String?>(null)
    override val currentTrackId: StateFlow<String?> = _currentTrackId.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    override val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private val _progress = MutableStateFlow(0L)
    override val progress: StateFlow<Long> = _progress.asStateFlow()
    private val _bufferedProgress = MutableStateFlow(0L)
    override val bufferedProgress: StateFlow<Long> = _bufferedProgress.asStateFlow()
    private val _seekLock = MutableStateFlow<Float?>(null)
    override val seekLock: StateFlow<Float?> = _seekLock.asStateFlow()
    private val _seekByReopen = MutableStateFlow(false)
    override val seekByReopen: StateFlow<Boolean> = _seekByReopen.asStateFlow()

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

    private val _staleStreamEvent = MutableSharedFlow<String>(extraBufferCapacity = EVENT_BUFFER)
    override val staleStreamEvent: Flow<String> = _staleStreamEvent.asSharedFlow()

    private val _warmTrackId = MutableStateFlow<String?>(null)
    override val warmTrackId: StateFlow<String?> = _warmTrackId.asStateFlow()

    private val _standbyFailedEvent = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = EVENT_BUFFER)
    override val standbyFailedEvent: Flow<Pair<String, String>> = _standbyFailedEvent.asSharedFlow()

    override val settled: Boolean get() = !inTransition && calm()

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

    private val _soundingTrackId = MutableStateFlow<String?>(null)
    override val soundingTrackId: StateFlow<String?> = _soundingTrackId.asStateFlow()

    override val inTransition: Boolean get() = outgoingPlayer != null || awaitingIncoming
    private var crossfadeJob: Job? = null
    private var playJob: Job? = null
    private var preloadedTrackId: String? = null

    /**
     * F4 (D68): with crossfade off the next track is queued right behind the playing one on the same player, which
     * moves on to it by itself — gapless, on the same AudioTrack. Media3 releases the AudioTrack on every flush
     * (a new item set, a stop, a seek), so a playlist move is the only way a track change keeps it.
     * [queuedOnActive] is the id waiting behind the current item; [gaplessArrived] the one the player moved on to,
     * until the controller's start for it ([startTransition]) takes it over.
     */
    private var queuedOnActive: String? = null
    private var gaplessArrived: String? = null

    /** When the last track change began (elapsedRealtime): a burst of skips gets no silent players (F4). */
    private var lastStartAt = 0L

    private var duckFactor = 1f
    @Volatile
    private var trackGains: Map<String, Float> = emptyMap()

    private fun gainOf(player: ExoPlayer): Float = player.currentMediaItem?.mediaId?.let { trackGains[it] } ?: 1f

    /** [player]'s fade level, applied to its samples (D98). */
    private fun fader(player: ExoPlayer): OutputGain = if (player === playerA) gainA else gainB

    /** Ducking × the track's normalization, on top of the player's fade level (both in its samples, D98). */
    private fun applyVolume(player: ExoPlayer) {
        fader(player).setScale((duckFactor * gainOf(player)).coerceIn(0f, 1f))
    }

    /**
     * After [clearPlayer]: full level for the player's next item — applied once its old stream is gone (stopped,
     * flushed), never to what the old one may still render.
     */
    private fun restoreVolumeLater(player: ExoPlayer) = fader(player).setAfterFlush(1f)

    override fun setTrackGains(gains: Map<String, Float>) {
        scope.launch {
            trackGains = gains
            applyVolume(playerA)
            applyVolume(playerB)
        }
    }
    private var crossfadeDurationMs = CROSSFADE_DURATION_MS
    private var playPauseDurationMs = PLAY_PAUSE_DURATION_MS
    private var seekDurationMs = SEEK_DURATION_MS

    /** The skip being made came while a transition was running: the in-flight sound is cut with a short ramp. */
    private var rapidSkip = false

    /**
     * The old track of a skip that is fading out inside [startTransition], before the target starts: nobody else owns
     * it (it left [outgoingPlayer] and [skipFading]), so a pause has to find it here.
     */
    private var handoverTail: ExoPlayer? = null

    /** The old track is fading out after the new one was ready: the new one can't sound yet ([inHandover]). */
    private var handingOver = false

    override val inHandover: Boolean get() = handingOver || handoverTail != null || skipFading != null

    private suspend inline fun whileHandingOver(block: () -> Unit) {
        handingOver = true
        try {
            block()
        } finally {
            handingOver = false
        }
    }

    private var lastSeekTimestamp = 0L
    private var pendingSeekPosition = -1L
    private var hasEmittedPrepareForCurrent = false
    private var hasEmittedTrackEndedForCurrent = false

    /** "Fade then rise" at a track's natural end instead of a crossfade (Settings → Playback → Transition). */
    private var fadeThenRise = false

    private val _unseekableStreamEvent = MutableSharedFlow<String>(extraBufferCapacity = EVENT_BUFFER)
    override val unseekableStreamEvent: Flow<String> = _unseekableStreamEvent.asSharedFlow()

    /** The item already reported unseekable (once per item). */
    private var unseekableReported: String? = null

    /** Where the playing track last sounded: a jump to its start that nobody asked for is a broken stream. */
    private var lastSoundedId: String? = null
    private var lastSoundedAt = -1L

    /** A seek or a recovery on an unseekable stream waits for the track's whole file on disk, at [heldPosition]. */
    private var awaitingCopy = false
    private var heldPosition = -1L
    private var copyWaitJob: Job? = null

    /** The wait for a whole file ended by a pause or "no data" (D67): Play goes on with it, for this item ([heldPosition]). */
    private var heldWait: Pair<String, String>? = null

    /** "Fade then rise" with the next track queued behind (gapless): the current one fading out before its end. */
    private var gaplessFadeJob: Job? = null

    init {
        setupPlayerListener(playerA, 0)
        setupPlayerListener(playerB, 1)

        scope.launch {
            while (isActive) {
                val currentP = activePlayer
                // A track that became the active one already prepared (a crossfade, a warm standby) is checked here
                if (currentP.playbackState == Player.STATE_READY) maybeReportUnseekable(currentP)
                updateSeekLock(currentP)
                watchForRestart(currentP)
                // The sound stood while the track played, or its time fell back with no seek: the journal says it
                watchOutput(currentP)
                // The track that plays now, or the one a crossfade waits for: never the previous one, loaded to
                // its end long ago — the cache would take the line from the new track at its start (upstream 1.14.3)
                if (currentP.isPlaying || awaitingIncoming) bufferedAhead = aheadOf(currentP)
                if (currentP.isPlaying) {
                    journal?.addPlayed(PROGRESS_INTERVAL_MS)
                    updateProgress(currentP)
                    watchForJump(currentP)
                    checkTransitionTriggers(currentP)
                    delay(PROGRESS_INTERVAL_MS)
                } else {
                    delay(IDLE_INTERVAL_MS)
                }
            }
        }
    }

    /** All of a track loaded counts as plenty: its end is no reason to hold back. */
    private fun aheadOf(player: ExoPlayer): Long {
        val duration = durationOf(player)
        val loadedToTheEnd = duration > 0 && player.bufferedPosition >= duration - 500
        return if (loadedToTheEnd) Long.MAX_VALUE else player.totalBufferedDuration
    }

    /**
     * The track's length: the player's, or — a progressive stream sent with no length (a server transcode) —
     * the catalog's ([ResolvedStream.durationMs]), so the end-of-track transition still fires on time.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun durationOf(player: ExoPlayer): Long {
        val own = player.duration
        if (own != androidx.media3.common.C.TIME_UNSET && own > 0L) return own
        return player.currentMediaItem?.mediaMetadata?.durationMs?.takeIf { it > 0L } ?: androidx.media3.common.C.TIME_UNSET
    }

    private fun updateProgress(player: ExoPlayer) {
        if (awaitingCopy) {
            // A seek or a recovery waits for the disk copy: the bar stays where the music will go on from
            _progress.value = heldPosition
            return
        }
        val position = player.currentPosition
        _bufferedProgress.value = player.bufferedPosition
        if (rampKind == Ramp.SEEK_OUT && seekTarget >= 0L) {
            // A smooth seek is still fading out the old position: the bar stays where the user put it, the jump
            // follows (the player's own position would snap the bar back until then)
            _progress.value = seekTarget
            return
        }
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
        val duration = durationOf(player)
        val position = player.currentPosition
        if (duration <= 8_000L || position <= 0L || outgoingPlayer != null) return
        val remaining = duration - position

        // 1. Warm standby preparation: a minute ahead once the playing track is loaded to its end, so the two
        //    downloads never compete on a slow link (D36); at the latest at 15 seconds remaining
        val loadedToTheEnd = bufferedAhead == Long.MAX_VALUE
        // A long transition needs the next track ready when it starts, not as long after
        val latest = maxOf(PREPARE_STANDBY_LATEST_MS, crossfadeDurationMs + PREPARE_TRANSITION_MARGIN_MS)
        val prepareNow = remaining <= PREPARE_STANDBY_THRESHOLD_MS && loadedToTheEnd || remaining <= latest
        if (prepareNow && !hasEmittedPrepareForCurrent) {
            hasEmittedPrepareForCurrent = true
            _prepareNextTrackEvent.tryEmit(Unit)
        }

        // 2. Early trigger so the crossfade overlaps the last seconds of the track. "Fade then rise" fades the current
        //    track out over the whole length too, and ends as the next one starts rising over the same length. A fade is
        //    rendered ahead of the speaker by the output buffer (D98): the trigger comes that much earlier
        val window = crossfadeDurationMs
        val lag = fader(player).latencyMs
        if (fadeThenRise && queuedOnActive != null) {
            // Gapless: the next track waits behind on this player; it fades out here and the arrival rises — one
            // ramp in the player's output, so the rise starts at the boundary in the samples, not when it is heard
            val inWindow = window > 0L && remaining in 1L..window + lag
            if (inWindow && gaplessFadeJob == null && !hasEmittedTrackEndedForCurrent) {
                val outMs = (remaining - lag).coerceIn(1L, window)
                fader(player).ramp(0f, outMs, from = level(player), then = OutputGain.Segment(1f, window))
                gaplessFadeJob = scope.launch { delay(outMs) }
            }
            return
        }
        if (crossfadeDurationMs > 0L && remaining in 1L..window + lag && !hasEmittedTrackEndedForCurrent) {
            hasEmittedTrackEndedForCurrent = true
            wakeLockHelper.acquireTransitionLock(30_000L)
            _trackEndedEvent.tryEmit(Unit)
        }
    }

    private fun setupPlayerListener(player: ExoPlayer, playerIdx: Int) {
        diagnostics.attach(player, playerIdx)
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                // A new track brings its own normalization
                applyVolume(player)
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && isGaplessArrival(player, mediaItem?.mediaId)) {
                    val arrived = mediaItem?.mediaId
                    // The queued track plays on now, gapless (F4): the controller hears "track ended" as it would
                    // at STATE_ENDED, advances its queue and starts this track, which [startTransition] takes over
                    queuedOnActive = null
                    gaplessArrived = arrived
                    if (!hasEmittedTrackEndedForCurrent) {
                        hasEmittedTrackEndedForCurrent = true
                        wakeLockHelper.acquireTransitionLock(30_000L)
                        _trackEndedEvent.tryEmit(Unit)
                    }
                    return
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                refreshPlaybackState()
                if (isPlaying && playerIdx == activePlayerIndex && outgoingPlayer == null) {
                    wakeLockHelper.releaseTransitionLock()
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // The headphones or the Bluetooth went away and the player paused by itself (R12): a fresh output
                // within a few seconds plays on. A pause the app asked for has another reason
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY && playerIdx == activePlayerIndex) {
                    journal?.onPaused(
                        org.tegro.yamp.data.player.PlaybackJournal.Kind.OUTPUT_GONE,
                        player.mediaMetadata.title?.toString()
                    )
                }
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
                if (playbackState == Player.STATE_READY && playerIdx == activePlayerIndex) maybeReportUnseekable(player)
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
                // A local file's error names its path (docs/phase-4d-local-files.md §10): then the type only, no trace
                val names = generateSequence<Throwable>(error) { it.cause }.any { e -> e.message.orEmpty().let { "/storage/" in it || "content://" in it || "file://" in it } }
                if (names) Log.e(TAG, "ExoPlayer error on player[$playerIdx]: ${error.errorCodeName} (${error.cause?.javaClass?.simpleName})")
                else Log.e(TAG, "ExoPlayer error on player[$playerIdx]: ${error.errorCodeName}", error)
                outgoingPlayer?.takeIf { pickAt > 0L && awaitingIncoming && player === activePlayer }?.let {
                    crossfadeJob?.cancel()
                    revertPick(it, player, error.errorCodeName)
                    return
                }
                note(org.tegro.yamp.data.player.PlaybackJournal.Kind.ERROR, player.mediaMetadata.title, "player ${error.errorCodeName}" + (if (player === outgoingPlayer) " (outgoing)" else if (playerIdx != activePlayerIndex) " (standby)" else ""))
                // The link is spent: the controller asks the resolver afresh next time (D74)
                player.currentMediaItem?.mediaId?.let { _staleStreamEvent.tryEmit(it) }
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
                            PlaybackFailure(
                                failedId, player.currentPosition.coerceAtLeast(0L), error.errorCodeName,
                                download = org.tegro.yamp.data.player.PlaybackBuffering.fromProgressiveDownload(error),
                                httpCode = org.tegro.yamp.data.player.PlaybackBuffering.httpStatusOf(error)
                            )
                        )
                    }
                    else -> {
                        // A preloaded standby went stale — never hand it to a transition.
                        val failed = player.currentMediaItem?.mediaId
                        if (preloadedTrackId == failed) preloadedTrackId = null
                        failed?.let { _standbyFailedEvent.tryEmit(it to error.errorCodeName) }
                        clearPlayer(player)
                        refreshWarm()
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
        refreshWarm()
        val p = activePlayer
        // The other player sounding a real track (not the silent clip, not held at volume 0, not a crossfade's
        // tail) also counts as playing: the screen and the session never say "paused" over audible music (F2)
        val other = standbyPlayer
        val otherAudible = other !== outgoingPlayer && other.isPlaying && fader(other).let { it.level > 0f || !it.outputSilent }
        val wantsToPlay = otherAudible || awaitingSkipTarget || awaitingCopy || (p.playWhenReady || awaitingIncoming) &&
            p.playerError == null &&
            p.mediaItemCount > 0 &&
            p.playbackState != Player.STATE_ENDED
        _isPlaying.value = wantsToPlay && !pausing
        // Only a real STATE_BUFFERING counts as loading: a paused-but-wants-to-play limbo (focus loss) is not a
        // spinner, otherwise the indicator sticks when playback is interrupted mid-start. While a crossfade's
        // previous track still sounds, the next one loading is not a stall either (upstream 1.14.3).
        val outgoing = outgoingPlayer
        val tailSounds = awaitingIncoming && outgoing != null && outgoing.isPlaying
        _isBuffering.value = awaitingSkipTarget || awaitingCopy || wantsToPlay && !tailSounds && p.playbackState == Player.STATE_BUFFERING
        _soundingTrackId.value = when {
            outgoing != null && outgoing.isPlaying && (awaitingIncoming || !p.isPlaying) -> outgoing.currentMediaItem?.mediaId
            p.isPlaying -> p.currentMediaItem?.mediaId
            otherAudible -> other.currentMediaItem?.mediaId
            else -> null
        }
    }

    override fun getPlayer(): Player = activePlayer

    /** The on-disk cache key the item was built with (D86). */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun MediaItem.cacheKey(): String? = localConfiguration?.customCacheKey

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun buildMediaItem(trackId: String, uri: String, title: String?, artist: String?, coverUrl: String?, cacheKey: String? = null, durationMs: Long? = null): MediaItem {
        val mediaMetadata = MediaMetadata.Builder()
            // The catalog's length: a stream sent without one still has a known end (see [durationOf])
            .setDurationMs(durationMs?.takeIf { it > 0L })
            .setTitle(title ?: "Unknown Track")
            .setDisplayTitle(title ?: "Unknown Track")
            .setArtist(artist ?: "Unknown Artist")
            .setArtworkUri(coverUrl?.toUri())
            .build()
        return MediaItem.Builder()
            .setMediaId(trackId)
            .setUri(uri)
            // The on-disk cache finds the bytes by account + track + quality, not by the short-lived link (D86)
            .setCustomCacheKey(cacheKey)
            .setMediaMetadata(mediaMetadata)
            .build()
    }

    override fun prepareNextTrack(trackId: String, stream: ResolvedStream, title: String?, artist: String?, coverUrl: String?) {
        if (preloadedTrackId == trackId || outgoingPlayer != null) return
        val playUri = stream.uri.takeIf { it.isNotBlank() } ?: return
        // Its headers (a Jellyfin token, step 23) go with every request to this URL, and only to it
        org.tegro.yamp.data.player.StreamHeaders.register(playUri, stream.headers)
        progressive.register(stream)
        scope.launch {
            // State may have changed since the controller asked.
            if (outgoingPlayer != null || _currentTrackId.value == trackId) return@launch

            val item = buildMediaItem(trackId, playUri, title, artist, coverUrl, stream.cacheKey, stream.durationMs)
            if (gaplessFits()) {
                // Crossfade off: behind the playing track on its own player, no second AudioTrack (F4)
                dropQueuedOnActive()
                activePlayer.addMediaItem(item)
                queuedOnActive = trackId
                preloadedTrackId = trackId
                Log.d(TAG, "Queued ${org.tegro.yamp.data.diagnostics.logId(trackId)} gapless behind the playing track")
                return@launch
            }
            val standby = standbyPlayer
            silenceNow(standby)
            preloadedTrackId = trackId
            releaseForReuse(standby)
            standby.playWhenReady = false
            standby.setMediaItem(item, 0L)
            standby.prepare()
            Log.d(TAG, "Preloaded warm standby player with trackId = ${org.tegro.yamp.data.diagnostics.logId(trackId)}")
        }
    }

    override fun dropStandby() {
        scope.launch {
            if (outgoingPlayer != null) return@launch
            preloadedTrackId = null
            hasEmittedPrepareForCurrent = false
            dropQueuedOnActive()
            clearPlayer(standbyPlayer)
        }
    }

    /**
     * The gapless path (F4) applies: crossfade off, the silent-clip modes off (they own the standby), and the active
     * player holds the current track — sounding, not in a transition.
     */
    private fun gaplessFits(): Boolean {
        val p = activePlayer
        return (crossfadeDurationMs == 0L || fadeThenRise) && outgoingPlayer == null &&
            !awaitingIncoming && p.playerError == null && p.mediaItemCount > 0 &&
            p.currentMediaItem?.mediaId == _currentTrackId.value
    }

    /** A track queued behind the playing one is taken off again (the queue changed, a skip, crossfade switched on). */
    private fun dropQueuedOnActive() {
        val queued = queuedOnActive ?: return
        queuedOnActive = null
        gaplessFadeJob?.let {
            // "Fade then rise" was under way: the fade-out goes on to the track's end, the rise is not for it
            it.cancel()
            gaplessFadeJob = null
            val p = activePlayer
            val left = (durationOf(p) - p.currentPosition - fader(p).latencyMs).coerceAtLeast(QUICK_FADE_MS)
            fader(p).ramp(0f, left)
        }
        if (preloadedTrackId != null && standbyPlayer.currentMediaItem?.mediaId != preloadedTrackId) preloadedTrackId = null
        val p = activePlayer
        // The player already moved on to it: nothing to take off (the current item is never touched)
        if (p.currentMediaItem?.mediaId == queued) return
        // Only the items after the current one go — no seek, the playing item plays on undisturbed
        val after = p.currentMediaItemIndex + 1
        if (p.mediaItemCount > after) {
            droppedQueued = p.getMediaItemAt(after).takeIf { it.mediaId == queued }
            p.removeMediaItems(after, p.mediaItemCount)
        }
    }

    /** The track last taken off from behind the playing one: a skip to it starts it without a new link. */
    private var droppedQueued: MediaItem? = null

    /** [trackId] waits behind the playing item on the active player (gapless, F4): as ready as a warm standby. */
    private fun isQueuedBehind(trackId: String): Boolean {
        if (queuedOnActive != trackId) return false
        val p = activePlayer
        val next = p.currentMediaItemIndex + 1
        return next < p.mediaItemCount && p.getMediaItemAt(next).mediaId == trackId
    }

    /**
     * [old] goes fully silent before another player sounds (D98): its level ramps to 0 in its samples over [fadeMs]
     * (from where it is), and it is paused and stopped only once that silence has reached the speaker — all it still
     * has buffered is silence, so nothing of the old track can be heard over the new one whatever an effect does with
     * the session's volume. (b152: Poweramp's Dynamics Processing ignored the player volume; the old player was paused
     * mid-waveform and its full AudioTrack buffer played out over the new track — an 80 ms pop.) [owned] says the
     * caller still owns the players; if not, it is left as it is.
     */
    private suspend fun silenceForHandover(old: ExoPlayer, fadeMs: Long = CUT_FADE_MS, owned: () -> Boolean = { true }) {
        val g = fader(old)
        if (old.isPlaying) {
            // Always a ramp, even from 0: it also ends a rise that has not reached the output yet
            val from = g.level
            fade(old, from, 0f, (fadeMs * from).toLong().coerceAtLeast(CUT_FADE_MS))
            awaitSilentOutput(old)
        } else {
            g.set(0f)
        }
        if (!owned()) return
        old.pause()
        old.stop()
    }

    /**
     * Waits (bounded) while [player] still sounds until the silence of its faded level has reached the speaker: then
     * nothing audible is left in its output. Writes how long that took.
     */
    private suspend fun awaitSilentOutput(player: ExoPlayer) {
        val g = fader(player)
        val since = SystemClock.elapsedRealtime()
        // The player may be handed another track meanwhile (a rapid skip reuses it): the old sound is gone then, and
        // the new one's rise is not silence to wait for
        val awaited = player.currentMediaItem?.mediaId
        fun same() = player.currentMediaItem?.mediaId == awaited
        fun waiting() = !g.outputSilent && player.isPlaying && same()
        while (waiting() && SystemClock.elapsedRealtime() - since < SILENCE_WAIT_MAX_MS) delay(WAIT_STEP_MS)
        val waited = SystemClock.elapsedRealtime() - since
        val how = when {
            g.outputSilent -> "silent"
            !same() -> "reassigned"
            player.isPlaying -> "NOT silent (timed out)"
            else -> "stopped sounding"
        }
        note(org.tegro.yamp.data.player.PlaybackJournal.Kind.FADE, player.mediaMetadata.title, "handover: output $how after $waited ms (buffer ${g.latencyMs} ms)")
    }

    /** [player]'s level is 0 from its next frame on (not ramped: for a player that is not sounding). */
    private fun silenceNow(player: ExoPlayer) {
        fader(player).ramp(0f, 0L, from = 0f)
    }

    /** [player] starts from silence and rises quickly (a cut, a handover): its first frame is 0. */
    private fun riseFromSilence(player: ExoPlayer) {
        fader(player).ramp(1f, HANDOVER_RISE_MS, from = 0f)
    }

    /** A skip's or pick's next track after the old one faded out: rises over the crossfade length (0: a quick de-click). */
    private fun riseAfterSkip(player: ExoPlayer) {
        val ms = crossfadeDurationMs
        if (ms > HANDOVER_RISE_MS) riseTo(player, from = 0f, durationMs = ms, what = "skip rise", kind = Ramp.TRACK_RISE) else riseFromSilence(player)
    }

    /** How long a skip fades the old track out: the crossfade length, a cut ramp when transitions are off. */
    private fun skipFadeMs() = if (crossfadeDurationMs > 0L) crossfadeDurationMs else CUT_FADE_MS

    /** A skip or a pick overlaps the playing track with the next one (equal power) instead of fading out first. */
    private fun overlapsOnSkip() = crossfadeDurationMs > 0L && !fadeThenRise

    /** [player] moved on by itself to [arrivedId], the track queued behind the playing one (F4). */
    private fun isGaplessArrival(player: ExoPlayer, arrivedId: String?): Boolean =
        arrivedId != null && arrivedId == queuedOnActive && player === activePlayer && outgoingPlayer == null

    /** No track change for [SETTLE_MS] and none running: a burst of skips is over (no preload in a burst, F4). */
    private fun calm(): Boolean =
        SystemClock.elapsedRealtime() - lastStartAt >= SETTLE_MS && skipFading == null && !awaitingSkipTarget

    override fun playTrack(
        trackId: String,
        stream: ResolvedStream?,
        title: String?,
        artist: String?,
        coverUrl: String?,
        crossfade: Boolean,
        startPositionMs: Long,
        pickAtMs: Long
    ) {
        playJob?.cancel()
        diagnostics.requested(onlyIfUnset = true)
        stream?.let {
            org.tegro.yamp.data.player.StreamHeaders.register(it.uri, it.headers)
            progressive.register(it)
        }
        playJob = scope.launch {
            // The player already moved on to this track gapless (F4): nothing to resolve, the start takes it over
            if (gaplessArrived == trackId && activePlayer.currentMediaItem?.mediaId == trackId) {
                activePlayer.currentMediaItem?.let { startTransition(it, crossfade, startPositionMs, pickAtMs) }
                return@launch
            }
            // A warm standby already holds this track: its link is in hand. The engine never resolves (D74): what
            // it plays is the warm standby or the stream the controller resolved
            val warmUri = standbyPlayer.currentMediaItem?.localConfiguration?.uri?.toString()?.takeIf { isStandbyWarm(trackId) }
            // Queued behind the playing track and taken off by the skip: its item still holds the link
            val queuedItem = if (isQueuedBehind(trackId)) activePlayer.getMediaItemAt(activePlayer.currentMediaItemIndex + 1) else droppedQueued
            val dropped = queuedItem?.takeIf { it.mediaId == trackId && warmUri == null && stream?.uri.isNullOrBlank() }
            droppedQueued = null
            if (dropped != null) {
                startTransition(dropped, crossfade, startPositionMs, pickAtMs)
                return@launch
            }
            val playUri = warmUri ?: stream?.uri?.takeIf { it.isNotBlank() }
            if (playUri == null) {
                Log.w(TAG, "playUri is blank for track ${org.tegro.yamp.data.diagnostics.logId(trackId)}")
                note(org.tegro.yamp.data.player.PlaybackJournal.Kind.ERROR, title, "no stream URL")
                _playbackErrorEvent.tryEmit(PlaybackFailure(trackId, startPositionMs, "EMPTY_URL"))
                return@launch
            }
            val cacheKey = if (warmUri != null) standbyPlayer.currentMediaItem?.cacheKey() else stream?.cacheKey
            val duration = stream?.durationMs ?: standbyPlayer.currentMediaItem?.mediaMetadata?.durationMs?.takeIf { warmUri != null }
            startTransition(buildMediaItem(trackId, playUri, title, artist, coverUrl, cacheKey, duration), crossfade, startPositionMs, pickAtMs)
        }
    }

    override fun noteStartRequested() = diagnostics.requested()

    override fun bufferedPositionMs(): Long = activePlayer.bufferedPosition

    override fun loadingProgress(): Long {
        val written = activePlayer.currentMediaItem?.cacheKey()?.let { progressive.writtenTotal(it) } ?: 0L
        return activePlayer.bufferedPosition + written
    }

    /** Publishes which track a warm standby holds ready (the Next button, D80). */
    private fun refreshWarm() {
        _warmTrackId.value = preloadedTrackId?.takeIf { isWarm(it) }
    }

    /**
     * Ready to sound on a manual Next: the gapless arrival, a warm standby, or a track queued behind the playing one
     * ("Fade then rise" always queues it there) — so Next takes the skip path, never a pick (the b109 pop).
     */
    override fun isWarm(trackId: String): Boolean = gaplessArrived == trackId || isStandbyWarm(trackId) || isQueuedBehind(trackId)

    private fun isStandbyWarm(trackId: String): Boolean = standbyPlayer.let {
        preloadedTrackId == trackId && it.currentMediaItem?.mediaId == trackId && it.playbackState == Player.STATE_READY && it.playerError == null
    }

    /** Switches the audible track on the main thread. */
    private suspend fun startTransition(item: MediaItem, crossfade: Boolean, startPositionMs: Long, pickAtMs: Long = 0L) {
        val trackId = item.mediaId
        val gapless = gaplessArrived == trackId && startPositionMs == 0L && outgoingPlayer == null &&
            activePlayer.currentMediaItem?.mediaId == trackId
        gaplessArrived = null
        if (gapless) {
            continueGapless(item)
            return
        }
        val rapid = rapidSkip
        rapidSkip = false
        // A new track ends any pause / play / seek ramp of the old one
        val rise = riseNextStart
        riseNextStart = false
        endRamp()
        cancelCopyWait()
        heldWait = null
        gaplessFadeJob?.cancel()
        gaplessFadeJob = null
        unseekableReported = null
        _seekLock.value = null
        _seekByReopen.value = false
        lastSoundedId = null
        progressive.playing(item.cacheKey())
        // Any other start: a track queued behind the current one must not play on after it
        dropQueuedOnActive()
        lastStartAt = SystemClock.elapsedRealtime()
        // A pick still loading: the latest one wins, the track that plays on stays as it is
        outgoingPlayer?.takeIf { pickAt > 0L && awaitingIncoming }?.let { revertPick(it, activePlayer, reason = null) }
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
        _bufferedProgress.value = startPositionMs
        stalledSince = null
        val current = activePlayer
        val isCurrentAudible = current.isPlaying && current.playerError == null
        pickAt = if (isCurrentAudible) pickAtMs else 0L
        // A pick writes its START once it is ready, with the wait
        if (pickAt == 0L) note(org.tegro.yamp.data.player.PlaybackJournal.Kind.START, item.mediaMetadata.title, if (crossfade) "crossfade" else "cut", startPositionMs)

        val incoming = standbyPlayer
        val reusePreloaded = preloadedTrackId == trackId &&
            incoming.currentMediaItem?.mediaId == trackId &&
            incoming.playerError == null &&
            incoming.playbackState != Player.STATE_IDLE
        preloadedTrackId = null

        // Silent before it renders anything: the one buffer its sink writes before play is silence, and the ramp
        // set when it starts runs from the next frame (D98). A warm standby already is
        if (!reusePreloaded) silenceNow(incoming)
        if (reusePreloaded) {
            // A kept player may still hold this track where it was left (played earlier, then skipped away):
            // a skip back to it starts from the asked position (0 unless a session restore), not from there
            if (kotlin.math.abs(incoming.currentPosition - startPositionMs) > REUSE_POSITION_SLACK_MS) incoming.seekTo(startPositionMs)
        } else {
            releaseForReuse(incoming)
            incoming.setMediaItem(item, startPositionMs)
            incoming.prepare()
        }
        diagnostics.started(incoming, item.mediaMetadata.title, ready = reusePreloaded && incoming.playbackState == Player.STATE_READY)

        activePlayerIndex = 1 - activePlayerIndex
        _activePlayerState.value = incoming

        // A skip's fade-out still running: it ends now, before the new track sounds. The two never play at once —
        // under an effect that takes over the session's volume (Poweramp's DVC on YAMP's shared session) a player's
        // own volume is not applied, so a fading or "silent" old track is heard at full level next to the new one
        val skipTail = skipFading
        if (skipTail != null) {
            cancelSkipFade()
            if (outgoingPlayer === skipTail) outgoingPlayer = null
            // Its fade ends in silence that has reached the speaker before anything else sounds (D98); meanwhile
            // the engine still says "playing, loading" (a moment of "not playing" costs audio focus, b148)
            awaitingSkipTarget = true
            handoverTail = skipTail
            try {
                silenceForHandover(skipTail, if (rapid) RAPID_RAMP_MS else skipFadeMs())
            } finally {
                if (handoverTail === skipTail) handoverTail = null
            }
            clearPlayer(skipTail)
            restoreVolumeLater(skipTail)
        }
        if (skipTail === current) {
            riseAfterSkip(incoming)
            incoming.playWhenReady = true
        } else if (pickAt > 0L || crossfadesFrom(isCurrentAudible, crossfade)) {
            wakeLockHelper.acquireTransitionLock(30_000L)
            outgoingPlayer = current
            setVolume(incoming, 0f)
            // Silent and still until it can sound without a stop; the outgoing track plays its tail meanwhile.
            // Nothing of the new track is in hand yet: the cache waits until it has its own buffer
            awaitingIncoming = true
            bufferedAhead = 0L
            incoming.playWhenReady = false
            crossfadeJob = scope.launch { runCrossfade(current, incoming) }
        } else {
            // A cut: the old track is silent before the new one sounds (no overlap, see above) — a short de-click
            // ramp to 0 in its samples, heard to the end, then stopped; its level comes back only for its next item
            if (isCurrentAudible) {
                silenceForHandover(current)
            } else if (current.playWhenReady) {
                current.pause()
            }
            restoreVolumeLater(current)
            val riseMs = playPauseDurationMs
            if (rise && !isCurrentAudible && riseMs > 0L) {
                setVolume(incoming, 0f)
                incoming.playWhenReady = true
                riseTo(incoming, from = 0f, durationMs = riseMs, what = "play")
            } else {
                riseFromSilence(incoming)
                incoming.playWhenReady = true
            }
            clearPlayer(current) // after the new one plays: with both players running, the old one idles
        }
        // A converted stream started at a position (a retry, a restored session) can't seek there until its whole
        // download is in (D91): the bar holds the position and the music starts there once it is
        if (startPositionMs > 0L && outgoingPlayer == null && waitsForWholeDownload(item)) {
            reopenFromCopy(incoming, startPositionMs, "start")
        }
        // The skip tail's silence is over and the new track is on its way
        if (skipTail != null) awaitingSkipTarget = false
        refreshPlaybackState()
    }

    /**
     * The active player already plays [item] — it moved on to it from the track before, gapless (F4). Nothing changes
     * hands: the engine's state follows, the played item leaves the playlist (an earlier item, no flush).
     */
    private fun continueGapless(item: MediaItem) {
        val p = activePlayer
        skipTargetArrived()
        hasEmittedPrepareForCurrent = false
        hasEmittedTrackEndedForCurrent = false
        pendingSeekPosition = -1L
        lastSeekTimestamp = 0L
        stalledSince = null
        pickAt = 0L
        preloadedTrackId = null
        _currentTrackId.value = item.mediaId
        _progress.value = p.currentPosition
        _bufferedProgress.value = p.bufferedPosition
        note(org.tegro.yamp.data.player.PlaybackJournal.Kind.START, item.mediaMetadata.title, "gapless")
        if (p.currentMediaItemIndex > 0) p.removeMediaItems(0, p.currentMediaItemIndex)
        diagnostics.started(p, item.mediaMetadata.title, ready = true)
        // "Fade then rise" issued the rise with the fade-out, as one ramp of this player's output (D98)
        val riseQueued = gaplessFadeJob != null
        gaplessFadeJob?.cancel()
        gaplessFadeJob = null
        cancelCopyWait()
        heldWait = null
        unseekableReported = null
        _seekLock.value = null
        _seekByReopen.value = false
        lastSoundedId = null
        progressive.playing(item.cacheKey())
        val riseMs = crossfadeDurationMs
        if (fadeThenRise && riseMs > 0L && riseQueued) {
            fadeNote("rise", riseMs)
            rampKind = Ramp.TRACK_RISE
            rampJob = scope.launch {
                delay(riseMs)
                rampKind = null
            }
        } else if (fadeThenRise && riseMs > 0L) {
            // "Fade then rise": the previous track faded out before its end; this one rises over the same length
            setVolume(p, 0f)
            riseTo(p, from = 0f, durationMs = riseMs, what = "rise", kind = Ramp.TRACK_RISE)
        } else {
            setVolume(p, 1f)
        }
        wakeLockHelper.releaseTransitionLock()
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
        // A newer start took over (or the pick was given up): this transition no longer owns the players, and
        // touching them — clearing "the outgoing one", reverting — would stop the track that plays now (F2)
        fun stillMine() = outgoingPlayer === outgoing && activePlayer === incoming
        // Slowness never gives a track up (D67): it keeps loading while bytes arrive. Only a load that brings no
        // data for NO_DATA_TIMEOUT_MS ends the wait — and then playback pauses, it does not go back or skip.
        val loading = org.tegro.yamp.data.player.LoadProgress(SystemClock.elapsedRealtime(), incoming.bufferedPosition)
        while (incoming.playbackState != Player.STATE_READY) {
            if (!stillMine()) return
            if (incoming.playerError != null) {
                awaitingIncoming = false
                return // reported by the listener
            }
            if (loading.stalledFor(SystemClock.elapsedRealtime(), incoming.bufferedPosition) >= NO_DATA_TIMEOUT_MS) {
                val id = incoming.currentMediaItem?.mediaId ?: return
                Log.w(TAG, "Incoming track ${org.tegro.yamp.data.diagnostics.logId(id)} brought no data in ${NO_DATA_TIMEOUT_MS / 1000} s")
                note(org.tegro.yamp.data.player.PlaybackJournal.Kind.HOLD_TIMEOUT, incoming.mediaMetadata.title)
                // The controller pauses; the pause ends this transition with the new track as the current one
                _playbackErrorEvent.tryEmit(PlaybackFailure(id, 0L, PlaybackFailure.NO_DATA))
                return
            }
            delay(WAIT_STEP_MS)
        }
        // A little more in hand while the previous track still has its tail to play
        fun shortOfSound() = pickAt == 0L && incoming.isLoading && incoming.totalBufferedDuration < CROSSFADE_HEADROOM_MS
        val readyAt = SystemClock.elapsedRealtime()
        while (outgoing.isPlaying && shortOfSound() && SystemClock.elapsedRealtime() - readyAt < HEADROOM_MAX_WAIT_MS) {
            delay(WAIT_STEP_MS)
        }
        if (!stillMine()) return

        val late = !outgoing.isPlaying
        // "Fade then rise": nothing of the next track sounds until the current one has faded out — still "playing"
        val fadeRise = fadeThenRise && pickAt == 0L && crossfadeDurationMs > 0L
        // One after the other (a pick, "fade then rise"): the engine says "playing" through the handover's silence.
        // A moment of "not playing" makes the service abandon audio focus and ask again ~100 ms later, which the
        // head unit answers with a pop (b148 ride)
        awaitingIncoming = (fadeRise || pickAt > 0L) && !late
        val pick = pickAt > 0L
        if (pick) {
            note(org.tegro.yamp.data.player.PlaybackJournal.Kind.START, incoming.mediaMetadata.title, "pick, tap→ready ${SystemClock.elapsedRealtime() - pickAt} ms")
            pickAt = 0L
        }
        if (pick && overlapsOnSkip() && !late) {
            // The pick is ready: it crossfades with the track that played on, over the crossfade length
            awaitingIncoming = false
            crossfadeTail(outgoing, incoming, late, waitStart, ::stillMine)
        } else if (pick) {
            // The pick is ready: the current track fades out over the crossfade length (a cut when it is 0) and stops —
            // only then does the pick sound. Never both at once: under Poweramp's DVC the fade is not heard and the
            // old track would leak
            whileHandingOver { silenceForHandover(outgoing, skipFadeMs(), ::stillMine) }
            if (!stillMine()) return
            awaitingIncoming = false
            riseAfterSkip(incoming)
            incoming.playWhenReady = true
            refreshPlaybackState()
        } else if (fadeRise) {
            // One after the other, never both at once: works under an effect that takes over the session's volume
            // (Poweramp's DVC), and only one track is decoded at a time
            note(if (late) org.tegro.yamp.data.player.PlaybackJournal.Kind.CROSSFADE_LATE else org.tegro.yamp.data.player.PlaybackJournal.Kind.CROSSFADE, incoming.mediaMetadata.title, "fade then rise", SystemClock.elapsedRealtime() - waitStart)
            val length = crossfadeDurationMs.coerceAtLeast(1L)
            whileHandingOver {
                if (!late) {
                    fade(outgoing, from = level(outgoing), to = 0f, durationMs = length)
                    if (!stillMine()) return
                }
                silenceForHandover(outgoing, owned = ::stillMine)
            }
            if (!stillMine()) return
            awaitingIncoming = false
            setVolume(incoming, 0f)
            incoming.playWhenReady = true
            refreshPlaybackState()
            fade(incoming, from = 0f, to = 1f, durationMs = length)
        } else {
            // A crossfade at a track's natural end overlaps the two on purpose; both sides are ramped in their
            // own samples (D98), so an effect that takes over the session's volume can't undo the blend
            crossfadeTail(outgoing, incoming, late, waitStart, ::stillMine)
        }
        if (!stillMine()) return
        outgoingPlayer = null
        clearPlayer(outgoing)
        restoreVolumeLater(outgoing)
        wakeLockHelper.releaseTransitionLock()
    }

    private suspend fun crossfadeTail(outgoing: ExoPlayer, incoming: ExoPlayer, late: Boolean, waitStart: Long, mine: () -> Boolean) {
        note(if (late) org.tegro.yamp.data.player.PlaybackJournal.Kind.CROSSFADE_LATE else org.tegro.yamp.data.player.PlaybackJournal.Kind.CROSSFADE, incoming.mediaMetadata.title, valueMs = SystemClock.elapsedRealtime() - waitStart)

        if (late) {
            fader(incoming).ramp(1f, LATE_FADE_IN_MS, from = 0f)
            incoming.playWhenReady = true
            refreshPlaybackState()
            delay(LATE_FADE_IN_MS)
        } else {
            equalPowerFade(outgoing, incoming, crossfadeDurationMs, mine)
        }
    }

    private fun crossfadesFrom(audible: Boolean, crossfade: Boolean) = audible && crossfade && crossfadeDurationMs > 0L

    /** When the user picked the track now loading in a hold (elapsedRealtime), or 0: see [PlayerRepository.playTrack]. */
    private var pickAt = 0L

    /**
     * The pick loading on [incoming] is given up — a failure ([reason]) or a newer start (null): [outgoing], which
     * never stopped, is the current track again, and a failure is reported so the screen goes back to it too.
     */
    private fun revertPick(outgoing: ExoPlayer, incoming: ExoPlayer, reason: String?) {
        // Every way of dropping a pick stops its transition too: left running, the job would later "finish" a
        // crossfade on players a newer start owns (F2: play icon while music plays, a cleared active player)
        crossfadeJob?.cancel()
        crossfadeJob = null
        val failedId = incoming.currentMediaItem?.mediaId
        if (reason != null) note(org.tegro.yamp.data.player.PlaybackJournal.Kind.ERROR, incoming.mediaMetadata.title, "pick failed: $reason, the current track plays on")
        pickAt = 0L
        awaitingIncoming = false
        outgoingPlayer = null
        activePlayerIndex = indexOf(outgoing)
        _activePlayerState.value = outgoing
        _currentTrackId.value = outgoing.currentMediaItem?.mediaId
        clearPlayer(incoming)
        restoreVolumeLater(incoming)
        wakeLockHelper.releaseTransitionLock()
        if (reason != null && failedId != null) _playbackErrorEvent.tryEmit(PlaybackFailure(failedId, 0L, PlaybackFailure.PICK_FAILED))
        refreshPlaybackState()
    }

    /**
     * [outgoing] down and [incoming] up over [durationMs], equal power, each in its own samples (D98). The outgoing
     * side's fade reaches the speaker after its output buffer: the incoming track — which renders its first frame
     * first ([org.tegro.yamp.data.player.FixedOutputRate.GatedSink]) — starts that much later, so the two curves meet
     * as heard. It ends once the outgoing side's silence has been heard.
     */
    private suspend fun equalPowerFade(outgoing: ExoPlayer, incoming: ExoPlayer, durationMs: Long, mine: () -> Boolean) {
        fader(outgoing).ramp(0f, durationMs, OutputGain.Shape.EQUAL_POWER, from = level(outgoing))
        val lag = fader(outgoing).latencyMs
        if (lag > 0L) {
            // Still "playing" while the incoming track waits for the outgoing fade to be heard
            awaitingIncoming = true
            delay(lag)
            awaitingIncoming = false
            if (!mine()) return
        }
        fader(incoming).ramp(1f, durationMs, OutputGain.Shape.EQUAL_POWER, from = 0f)
        incoming.playWhenReady = true
        refreshPlaybackState()
        delay(durationMs)
        if (outgoing.isPlaying) awaitSilentOutput(outgoing)
    }

    /**
     * [player]'s level from [from] to [to] over [durationMs] of its output, in dB ([org.tegro.yamp.data.player.FadeCurve]),
     * sample-accurate (D98); returns after that long. Cancelled, the level stays where it is.
     */
    private suspend fun fade(player: ExoPlayer, from: Float, to: Float, durationMs: Long) {
        val g = fader(player)
        val token = g.ramp(to, durationMs, from = from)
        try {
            delay(durationMs)
        } catch (e: CancellationException) {
            g.freeze(token)
            throw e
        }
    }

    /**
     * The outgoing player occupies the standby slot the next track needs, so it must be released first.
     * A [RAPID_RAMP_MS] ramp avoids an audible click; it is stopped even if this request is superseded.
     */
    private suspend fun fadeOutRunningCrossfade() {
        // Cancelled before the early return: a reverted pick leaves no outgoing player but may leave its job (F2)
        crossfadeJob?.cancel()
        crossfadeJob = null
        val out = outgoingPlayer ?: return
        awaitingIncoming = false
        if (skipFading === out) cancelSkipFade() // a skip tail: this quick fade takes over its end
        try {
            fade(out, from = level(out), to = 0f, durationMs = RAPID_RAMP_MS)
            if (out.isPlaying) awaitSilentOutput(out)
        } finally {
            if (outgoingPlayer === out) {
                clearPlayer(out)
                restoreVolumeLater(out)
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
            restoreVolumeLater(out)
        }
        outgoingPlayer = null
        if (restoreActiveVolume) setVolume(activePlayer, 1f)
    }

    override fun fadeOutForSkip(overlap: Boolean) {
        // The old track of the previous skip still fades out inside the start of a target that is dropped now: that old
        // track is the current one again and is cut short — left alone, the new start would reuse its player and stop
        // it mid-waveform (a pop)
        val unfinishedHandover = handoverTail
        if (unfinishedHandover != null) {
            handoverTail = null
            playJob?.cancel()
            val dropped = activePlayer
            clearPlayer(dropped)
            restoreVolumeLater(dropped)
            activePlayerIndex = indexOf(unfinishedHandover)
            _activePlayerState.value = unfinishedHandover
            _currentTrackId.value = unfinishedHandover.currentMediaItem?.mediaId
        }
        val player = activePlayer
        if (skipFading === player) { // skipped again while the fade runs: the target starts after a short ramp
            rapidSkip = true
            return
        }
        // A rise after a skip or at a gapless fade-then-rise is a transition too
        val inFlight = outgoingPlayer != null || awaitingIncoming || skipFading != null || rampKind == Ramp.TRACK_RISE ||
            crossfadeJob?.isActive == true || unfinishedHandover != null
        // The first skip of the crossfade transition keeps the track sounding: the next start overlaps it
        val overlaps = overlap && !inFlight && overlapsOnSkip() && player.isPlaying && player.playerError == null
        // Any skip made during a transition cuts the sound in flight with a short ramp; the latest target gets the full length
        rapidSkip = inFlight
        val outMs = if (inFlight) RAPID_RAMP_MS else skipFadeMs()
        endRamp()
        diagnostics.skipRequested()
        fadeJob?.cancel()
        // "Fade then rise" near the end: its own fade-out gives way to the skip fade
        gaplessFadeJob?.cancel()
        gaplessFadeJob = null
        lastStartAt = SystemClock.elapsedRealtime()
        // The fading track must not play on into a track queued behind it (F4); the controller starts the target
        dropQueuedOnActive()
        gaplessArrived = null
        expectSkipTarget()
        note(org.tegro.yamp.data.player.PlaybackJournal.Kind.SKIP, player.mediaMetadata.title, when {
            awaitingIncoming -> "during a hold"
            !player.isPlaying -> "not sounding (${player.playbackState})"
            overlaps -> "crossfade"
            else -> "fade"
        })
        if (overlaps) return
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
            restoreVolumeLater(player)
            _currentTrackId.value = null
            _progress.value = 0L
            _bufferedProgress.value = 0L
            if (tail != null && tail.isPlaying) {
                skipFading = tail
                skipFadeJob = scope.launch {
                    fade(tail, from = level(tail), to = 0f, durationMs = outMs)
                    awaitSilentOutput(tail)
                    skipFading = null
                    if (outgoingPlayer === tail) outgoingPlayer = null
                    clearPlayer(tail)
                    restoreVolumeLater(tail)
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
            restoreVolumeLater(player)
            _currentTrackId.value = null
            _progress.value = 0L
            _bufferedProgress.value = 0L
            refreshPlaybackState()
            return
        }
        skipFading = player
        skipFadeJob = scope.launch {
            // A crossfade that is still running: its outgoing side leaves quickly, the audible one fades below.
            fadeOutRunningCrossfade()
            fade(player, from = level(player), to = 0f, durationMs = outMs)
            // Faded in its samples (D98), and that silence heard to the end of its buffer: it stops now rather than
            // playing on at level 0, and a pause can't cut a waveform mid-way.
            awaitSilentOutput(player)
            // The engine keeps reporting "playing, loading" ([awaitingSkipTarget]) until the target starts.
            // Cancelled above means pause/seek/stop/a new start took the player over. From here the fade owns it.
            player.pause()
            skipFading = null
            if (outgoingPlayer === player) outgoingPlayer = null
            if (activePlayer === player) {
                _currentTrackId.value = null
                _progress.value = 0L
                _bufferedProgress.value = 0L
            }
            clearPlayer(player)
            restoreVolumeLater(player)
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

    /** A pause while a skip's old track still fades out before the target starts: the start is dropped, the tail stopped. */
    private fun abortHandover() {
        val tail = handoverTail ?: return
        handoverTail = null
        playJob?.cancel()
        clearPlayer(tail)
        restoreVolumeLater(tail)
    }

    override fun pause() {
        // Mid-ramp the level stays where it is (no jump up just before the pause); a resume sets it again
        val ramping = rampKind != null
        endRamp()
        abortHandover()
        fadeJob?.cancel()
        skipTargetArrived()
        cancelSkipFade()
        if (ramping) finishCrossfadeNow(restoreActiveVolume = false) else finishCrossfadeNow(restoreActiveVolume = true)
        holdCopyWait()
        activePlayer.pause()
        refreshPlaybackState()
    }

    private var fadeJob: Job? = null
    /** Running quick fade of a track the user skipped away from (see [fadeOutForSkip]), and its player. */
    private var skipFadeJob: Job? = null
    private var skipFading: ExoPlayer? = null

    override fun fadeOutAndPause(durationMs: Long) {
        endRamp()
        fadeJob?.cancel()
        fadeJob = scope.launch {
            finishCrossfadeNow(restoreActiveVolume = false)
            val player = activePlayer
            fade(player, level(player), 0f, durationMs.coerceAtLeast(1L))
            if (player.isPlaying) awaitSilentOutput(player)
            player.pause()
            setVolume(player, 1f)
            refreshPlaybackState()
        }
    }

    override fun resume(): Boolean {
        val player = activePlayer
        if (player.mediaItemCount == 0) return false
        if (resumeHeldWait(player)) return true
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
        endRamp()
        cancelCopyWait()
        heldWait = null
        progressive.playing(null)
        gaplessFadeJob?.cancel()
        gaplessFadeJob = null
        playJob?.cancel()
        skipTargetArrived()
        cancelSkipFade()
        finishCrossfadeNow(restoreActiveVolume = true)
        for (p in listOf(playerA, playerB)) {
            clearPlayer(p)
            restoreVolumeLater(p)
        }
        preloadedTrackId = null
        queuedOnActive = null
        gaplessArrived = null
        hasEmittedPrepareForCurrent = false
        hasEmittedTrackEndedForCurrent = false
        wakeLockHelper.releaseTransitionLock()
        _currentTrackId.value = null
        _progress.value = 0L
        _bufferedProgress.value = 0L
        refreshPlaybackState()
    }

    override fun release() {
        progressive.playing(null)
        scope.cancel()
        crossfadeJob?.cancel()
        skipFadeJob?.cancel()
        skipWaitJob?.cancel()
        playJob?.cancel()
        rampJob?.cancel()
        wakeLockHelper.releaseTransitionLock()
        playerA.release()
        playerB.release()
    }

    override fun seekTo(position: Long) {
        endRamp()
        skipTargetArrived()
        cancelSkipFade()
        finishCrossfadeNow(restoreActiveVolume = true)
        seekNow(position)
    }

    /** The jump itself; the caller has ended a crossfade and a skip fade (a ramp keeps its level). */
    private fun seekNow(position: Long) {
        val p = activePlayer
        lastSeekTimestamp = SystemClock.elapsedRealtime()
        pendingSeekPosition = position
        _progress.value = position
        if (!seekable(p)) {
            // A stream with no length can't seek: Media3 turns the seek into 0. It goes on from the disk copy
            reopenFromCopy(p, position, "seek")
            return
        }
        p.seekTo(position)
        // Seeking back out of the crossfade window must allow the triggers to fire again.
        val duration = p.duration
        if (duration > 0L && duration - position > crossfadeDurationMs) hasEmittedTrackEndedForCurrent = false
        if (duration > 0L && duration - position > PREPARE_STANDBY_THRESHOLD_MS) hasEmittedPrepareForCurrent = false
    }

    /** The playing item can seek: anything but a progressive network stream sent with no length. */
    private fun seekable(p: ExoPlayer): Boolean =
        p.isCurrentMediaItemSeekable || p.currentMediaItem?.cacheKey() == null || p.playbackState == Player.STATE_IDLE

    /** Reports, once per item, a network stream that turned out unseekable: seeking waits for its whole download. */
    private fun maybeReportUnseekable(p: ExoPlayer) {
        val item = p.currentMediaItem ?: return
        val isNetworkStream = item.cacheKey() != null
        if (!isNetworkStream || p.isCurrentMediaItemSeekable || unseekableReported == item.mediaId) return
        unseekableReported = item.mediaId
        note(org.tegro.yamp.data.player.PlaybackJournal.Kind.CACHE, item.mediaMetadata.title, "stream not seekable (no length): seeking waits for the whole download")
        _unseekableStreamEvent.tryEmit(item.mediaId)
        updateSeekLock(p)
    }

    /**
     * Seeking waits for the whole download (D91): the playing network stream was reported unseekable and its download
     * is not complete yet. The share is kept to whole percents, so the flow changes no more often than that. Once it
     * is complete the lock goes, and nothing else happens: the music plays on, the first seek reopens it there.
     */
    private fun updateSeekLock(p: ExoPlayer) {
        val item = p.currentMediaItem
        val key = item?.cacheKey()
        val unseekable = key != null && unseekableReported == item.mediaId && !p.isCurrentMediaItemSeekable &&
            p.playbackState != Player.STATE_IDLE
        val onDisk = unseekable && key != null && fullyCached(key)
        _seekLock.value = if (unseekable && !onDisk && key != null) {
            (downloadShare(key, p.bufferedPosition, durationOf(p)) * PERCENT).toInt() / PERCENT
        } else null
        _seekByReopen.value = onDisk
    }

    /** [item] is a converted stream whose download is not complete: it can't start anywhere but at 0 yet (D91). */
    private fun waitsForWholeDownload(item: MediaItem): Boolean {
        val key = item.cacheKey() ?: return false
        return progressive.isProgressive(key) && !fullyCached(key)
    }

    /**
     * The download broke and restarted (D91): the player played on from the old generation and has now read all of
     * it, running dry. It goes on where it is from the new generation, once that is complete — the only switch,
     * and only in this failure case.
     */
    private fun watchForRestart(p: ExoPlayer) {
        if (awaitingCopy || p !== activePlayer || outgoingPlayer != null) return
        val key = p.currentMediaItem?.cacheKey() ?: return
        if (p.playbackState != Player.STATE_BUFFERING || !p.playWhenReady || !progressive.isProgressive(key)) return
        if (progressive.parkedAtBrokenGeneration(key)) reopenFromCopy(p, p.currentPosition, "restart")
    }

    /**
     * The playing track's position fell back to its start with no seek asked: a progressive stream that broke off
     * mid-way and was restarted from 0 by Media3 (a transcode can't resume mid-way). It goes on where it was.
     */
    private fun watchForJump(p: ExoPlayer) {
        val id = p.currentMediaItem?.mediaId
        val position = p.currentPosition
        if (awaitingCopy || id == null) return
        val recentSeek = SystemClock.elapsedRealtime() - lastSeekTimestamp < JUMP_SEEK_GRACE_MS || rampKind == Ramp.SEEK_OUT || rampKind == Ramp.SEEK_IN
        val last = lastSoundedAt
        val fellToStart = last >= JUMP_FROM_MIN_MS && position <= JUMP_TO_MAX_MS && last - position >= JUMP_FROM_MIN_MS
        if (id == lastSoundedId && !recentSeek && fellToStart) {
            note(org.tegro.yamp.data.player.PlaybackJournal.Kind.JUMP, p.mediaMetadata.title, "0 unexpected, from ${last / 1000} s (${if (p.isCurrentMediaItemSeekable) "seekable" else "not seekable"})")
            lastSoundedAt = last
            if (p.isCurrentMediaItemSeekable) {
                p.seekTo(last)
                lastSeekTimestamp = SystemClock.elapsedRealtime()
            } else {
                reopenFromCopy(p, last, "recovery")
            }
            return
        }
        lastSoundedId = id
        lastSoundedAt = position
    }

    /**
     * The output took no sound while the track played, or the position went back with no seek: the journal's
     * [org.tegro.yamp.data.player.PlaybackJournal.Kind.OUTPUT_STALL] and `POSITION_BACK` (upstream 1.14.8's report: with
     * Bluetooth the music fell silent in every track, the timer counted on and jumped back 3–5 s, the journal was empty).
     * The watch itself is pure; a seek or another track starts it afresh, so neither is taken for trouble.
     */
    private fun watchOutput(p: ExoPlayer) {
        val now = SystemClock.elapsedRealtime()
        val id = p.currentMediaItem?.mediaId
        val recentSeek = now - lastSeekTimestamp < JUMP_SEEK_GRACE_MS || rampKind == Ramp.SEEK_OUT || rampKind == Ramp.SEEK_IN
        if (id != outputWatchId || recentSeek) {
            outputWatch.reset()
            outputWatchId = id
        }
        val finding = outputWatch.tick(now, p.isPlaying, p.currentPosition) ?: return
        val role = if (p === outgoingPlayer) "outgoing" else if (p === activePlayer) "active" else "standby"
        val where = "pos ${p.currentPosition / 1000} s, player $role" + (outputRoute()?.let { ", $it" } ?: "")
        when (finding) {
            is org.tegro.yamp.data.player.OutputWatch.Finding.OutputStall ->
                note(org.tegro.yamp.data.player.PlaybackJournal.Kind.OUTPUT_STALL, p.mediaMetadata.title, where, finding.durationMs)
            is org.tegro.yamp.data.player.OutputWatch.Finding.PositionBack ->
                note(org.tegro.yamp.data.player.PlaybackJournal.Kind.POSITION_BACK, p.mediaMetadata.title, where, finding.byMs)
        }
    }

    /**
     * Goes on with [p]'s track at [position] from its whole file on disk (seekable, with a known length): at once if
     * the copy is complete, else after waiting for it (bounded) — loading meanwhile, the bar held at [position].
     */
    private fun reopenFromCopy(p: ExoPlayer, position: Long, why: String) {
        val item = p.currentMediaItem ?: return
        val key = item.cacheKey() ?: return
        cancelCopyWait()
        heldWait = null
        heldPosition = position
        _progress.value = position
        if (fullyCached(key)) {
            reopenAt(p, item, position, why)
            return
        }
        note(org.tegro.yamp.data.player.PlaybackJournal.Kind.CACHE, item.mediaMetadata.title, "$why at ${position / 1000} s waits for the whole download")
        awaitingCopy = true
        p.playWhenReady = false
        refreshPlaybackState()
        copyWaitJob = scope.launch {
            // Slowness never gives up (D67): the wait goes on while the download brings bytes; none for
            // NO_DATA_TIMEOUT_MS ends it — playback pauses, and Play goes on with the same wait
            val loading = org.tegro.yamp.data.player.LoadProgress(SystemClock.elapsedRealtime(), progressive.writtenTotal(key))
            while (!fullyCached(key)) {
                if (p !== activePlayer || p.currentMediaItem?.mediaId != item.mediaId) {
                    awaitingCopy = false
                    return@launch
                }
                if (loading.stalledFor(SystemClock.elapsedRealtime(), progressive.writtenTotal(key)) >= NO_DATA_TIMEOUT_MS) {
                    awaitingCopy = false
                    heldWait = item.mediaId to why
                    note(org.tegro.yamp.data.player.PlaybackJournal.Kind.HOLD_TIMEOUT, item.mediaMetadata.title, "$why: no data for ${NO_DATA_TIMEOUT_MS / 1000} s")
                    _playbackErrorEvent.tryEmit(PlaybackFailure(item.mediaId, position, PlaybackFailure.NO_DATA))
                    refreshPlaybackState()
                    return@launch
                }
                delay(COPY_POLL_MS)
            }
            awaitingCopy = false
            reopenAt(p, item, position, why)
        }
    }

    /** A pause during the wait for a whole file: the wait stops, and Play goes on with it ([resumeHeldWait]). */
    private fun holdCopyWait() {
        if (!awaitingCopy) return
        val id = activePlayer.currentMediaItem?.mediaId
        copyWaitJob?.cancel()
        copyWaitJob = null
        awaitingCopy = false
        if (id != null) heldWait = id to "play"
    }

    /** Play after a held wait for a whole file: the wait goes on at the same position instead of playing from 0. */
    private fun resumeHeldWait(p: ExoPlayer): Boolean {
        val (id, why) = heldWait ?: return false
        heldWait = null
        if (p.currentMediaItem?.mediaId != id || p.playerError != null || p.playbackState == Player.STATE_IDLE) return false
        reopenFromCopy(p, heldPosition, why)
        return true
    }

    private fun reopenAt(p: ExoPlayer, item: MediaItem, position: Long, why: String) {
        note(org.tegro.yamp.data.player.PlaybackJournal.Kind.CACHE, item.mediaMetadata.title, "$why: reopened at ${position / 1000} s (complete)")
        lastSeekTimestamp = SystemClock.elapsedRealtime()
        pendingSeekPosition = position
        lastSoundedId = item.mediaId
        lastSoundedAt = position
        // A next track queued behind (gapless) would be dropped by the new item set: take it off cleanly and let
        // the controller queue it again (the prepare event fires anew)
        if (queuedOnActive != null) {
            dropQueuedOnActive()
            hasEmittedPrepareForCurrent = false
        }
        gaplessFadeJob?.cancel()
        gaplessFadeJob = null
        p.setMediaItem(item, position)
        p.prepare()
        p.playWhenReady = true
        // A seek's fade-out left it silent: it rises again once it sounds
        val rise = if (why == "play") playPauseDurationMs else seekDurationMs / 2
        if (level(p) < 1f) riseTo(p, from = level(p), durationMs = rise.coerceAtLeast(QUICK_FADE_MS), what = why)
        refreshPlaybackState()
    }

    private fun cancelCopyWait() {
        copyWaitJob?.cancel()
        copyWaitJob = null
        if (awaitingCopy) {
            awaitingCopy = false
            refreshPlaybackState()
        }
    }

    /** Running smooth pause / play / seek ramp (the play/pause and seek lengths) and what it does. */
    private var rampJob: Job? = null
    private var rampKind: Ramp? = null
    private enum class Ramp { PAUSE, PLAY, SEEK_OUT, SEEK_IN, TRACK_RISE }
    /** A pause is fading out: the engine already reports "paused", the sound follows. */
    private var pausing = false
    private var seekTarget = -1L
    private var riseNextStart = false

    private fun endRamp() {
        rampJob?.cancel()
        rampJob = null
        rampKind = null
        pausing = false
    }

    private fun level(player: ExoPlayer) = fader(player).level

    private fun fadeNote(what: String, ms: Long) =
        note(org.tegro.yamp.data.player.PlaybackJournal.Kind.FADE, activePlayer.currentMediaItem?.mediaMetadata?.title, "$what $ms ms")

    /**
     * Raises [player] from [from] to full over [durationMs] of its output. The ramp is set at once: it runs with the
     * frames the player renders (D98), so a player still loading starts it with its first sound. The ramp counts as
     * running until the player has sounded (bounded) and that long has passed.
     */
    private fun riseTo(player: ExoPlayer, from: Float, durationMs: Long, what: String, kind: Ramp = Ramp.PLAY) {
        fadeNote(what, durationMs)
        rampKind = kind
        val g = fader(player)
        val token = g.ramp(1f, durationMs.coerceAtLeast(1L), from = from)
        rampJob = scope.launch {
            try {
                awaitSounding(player)
                delay(durationMs.coerceAtLeast(1L))
            } catch (e: CancellationException) {
                g.freeze(token)
                throw e
            }
            rampKind = null
        }
    }

    /** Waits (bounded) until [player] actually sounds (it may still be loading). */
    private suspend fun awaitSounding(player: ExoPlayer) {
        var waited = 0L
        while (!player.isPlaying && waited < RISE_WAIT_MAX_MS) {
            delay(WAIT_STEP_MS)
            waited += WAIT_STEP_MS
        }
    }

    override fun pauseSmoothly() {
        val x = playPauseDurationMs
        val p = activePlayer
        if (pausing) return
        if (x <= 0L || !p.isPlaying) {
            pause()
            return
        }
        val wasRamping = rampKind != null
        // A pause during a smooth seek's fade-out keeps the seek: the jump happens now instead of being lost
        val pendingJump = seekTarget.takeIf { rampKind == Ramp.SEEK_OUT && it >= 0L }
        rampJob?.cancel()
        pendingJump?.let { seekNow(it) }
        skipTargetArrived()
        cancelSkipFade()
        // A ramp's level stays where it is: the fade starts from what one hears, without a jump
        finishCrossfadeNow(restoreActiveVolume = !wasRamping)
        pausing = true
        refreshPlaybackState()
        val from = level(p)
        val ms = (x * from).toLong().coerceAtLeast(1L)
        fadeNote("pause", x)
        rampKind = Ramp.PAUSE
        rampJob = scope.launch {
            fade(p, from, 0f, ms)
            // Paused only once the faded silence has been heard: no waveform cut mid-way (D98)
            awaitSilentOutput(p)
            rampKind = null
            rampJob = null
            pausing = false
            if (p !== activePlayer) return@launch
            p.pause() // stays at 0: a resume rises from silence, a plain one sets full volume first
            refreshPlaybackState()
        }
    }

    override fun resumeSmoothly(): Boolean {
        val x = playPauseDurationMs
        val p = activePlayer
        if (x <= 0L) return resume()
        if (pausing && rampKind == Ramp.PAUSE) {
            // Play during the pause fade: back up from where the sound is, no restart
            rampJob?.cancel()
            pausing = false
            val from = level(p)
            riseTo(p, from, (x * (1f - from)).toLong(), "play")
            refreshPlaybackState()
            return true
        }
        if (p.mediaItemCount == 0) return false
        if (resumeHeldWait(p)) return true
        if (p.playerError != null || p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED) {
            riseNextStart = true // the restart the controller makes rises too
            return resume()
        }
        endRamp()
        setVolume(p, 0f)
        p.play()
        refreshPlaybackState()
        riseTo(p, from = 0f, durationMs = x, what = "play")
        return true
    }

    override fun seekSmoothly(position: Long) {
        val x = seekDurationMs
        val half = x / 2
        val p = activePlayer
        if (pausing) { // the pause fade goes on; the position moves now
            seekNow(position)
            return
        }
        if (x <= 0L || !p.isPlaying) {
            seekTo(position)
            return
        }
        when (rampKind) {
            Ramp.SEEK_OUT -> { // still fading out: the jump goes to the newest position
                seekTarget = position
                _progress.value = position
                return
            }
            Ramp.SEEK_IN -> { // rising after a jump: jump again, the rise goes on from the current level
                rampJob?.cancel()
                seekNow(position)
                val from = level(p)
                riseTo(p, from, (half * (1f - from)).toLong(), "seek", Ramp.SEEK_IN)
                return
            }
            else -> Unit
        }
        val wasRamping = rampKind != null
        rampJob?.cancel()
        skipTargetArrived()
        cancelSkipFade()
        finishCrossfadeNow(restoreActiveVolume = !wasRamping)
        seekTarget = position
        _progress.value = position
        pendingSeekPosition = position
        lastSeekTimestamp = SystemClock.elapsedRealtime()
        val from = level(p)
        fadeNote("seek", x)
        rampKind = Ramp.SEEK_OUT
        rampJob = scope.launch {
            fade(p, from, 0f, (half * from).toLong().coerceAtLeast(1L))
            // The jump flushes the output: only once the faded silence has been heard (D98)
            awaitSilentOutput(p)
            if (p !== activePlayer) return@launch
            seekNow(seekTarget)
            if (awaitingCopy || rampJob !== coroutineContext[Job]) {
                // The jump goes on from the disk copy, which rises by itself once it sounds
                if (rampJob === coroutineContext[Job]) rampKind = null
                return@launch
            }
            rampKind = Ramp.SEEK_IN
            // Set now: the rise starts with the first frame at the new position
            val rise = half.coerceAtLeast(1L)
            val g = fader(p)
            val token = g.ramp(1f, rise, from = 0f)
            try {
                awaitSounding(p)
                delay(rise)
            } catch (e: CancellationException) {
                g.freeze(token)
                throw e
            }
            rampKind = null
        }
    }

    override fun riseOnNextStart() {
        riseNextStart = true
    }

    override fun setDucked(ducked: Boolean) {
        duckFactor = if (ducked) DUCK_VOLUME else 1f
        applyVolume(playerA)
        applyVolume(playerB)
    }

    override fun setCrossfadeDuration(durationMs: Long) {
        crossfadeDurationMs = durationMs.coerceAtLeast(0L)
        // Crossfade on: the next track needs its own player for the overlap, not a place behind the current one
        if (crossfadeDurationMs > 0L && !fadeThenRise) scope.launch { if (queuedOnActive != null) { dropQueuedOnActive(); hasEmittedPrepareForCurrent = false } }
    }

    override fun setPlayPauseDuration(durationMs: Long) {
        playPauseDurationMs = durationMs.coerceAtLeast(0L)
    }

    override fun setSeekDuration(durationMs: Long) {
        seekDurationMs = durationMs.coerceAtLeast(0L)
    }

    override fun setTransitionFadeThenRise(enabled: Boolean) {
        scope.launch {
            fadeThenRise = enabled
            // A crossfade needs the next track on its own player, not queued behind the current one
            if (!enabled && crossfadeDurationMs > 0L && queuedOnActive != null) {
                dropQueuedOnActive()
                hasEmittedPrepareForCurrent = false
            }
        }
    }

    private fun indexOf(player: ExoPlayer) = if (player === playerA) 0 else 1

    /** Stops and empties [player]. */
    private fun clearPlayer(player: ExoPlayer) {
        player.stop()
        player.clearMediaItems()
    }

    /** Before a player gets a new item: a stopped player is stopped again (harmless). */
    private fun releaseForReuse(player: ExoPlayer) {
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.stop()
    }

    /** [player]'s fade level to [volume] at once (de-clicked), in its samples (D98). */
    private fun setVolume(player: ExoPlayer, volume: Float) {
        fader(player).set(volume)
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
        const val PLAY_PAUSE_DURATION_MS = 1000L
        const val SEEK_DURATION_MS = 500L
        /** The next track is warmed up this long before the end, if the playing one is already loaded to its end. */
        const val PREPARE_STANDBY_THRESHOLD_MS = 60_000L
        /** …and at the latest this long before the end, whatever the buffer: the old rule. */
        const val PREPARE_STANDBY_LATEST_MS = 15_000L
        /** …or this long before a transition of the crossfade length starts, when that is later. */
        const val PREPARE_TRANSITION_MARGIN_MS = 5_000L
        /** Sound the incoming track should have in hand before the crossfade starts (unless all of it loaded). */
        const val CROSSFADE_HEADROOM_MS = 5_000L
        /** When the previous track has already ended, the next one fades in this fast instead of crossfading. */
        const val LATE_FADE_IN_MS = 300L
        private const val WAIT_STEP_MS = 20L
        /**
         * A handover waits at most this long for the old player's faded silence to reach the speaker (D98): Media3's
         * largest PCM output buffer (750 ms) and a margin.
         */
        const val SILENCE_WAIT_MAX_MS = 1_500L
        /** A cut (crossfade off, the Audio Router's hard cut) ramps the old track to 0 this fast: no click. */
        const val CUT_FADE_MS = 10L
        /** The next track of a skip, a pick or a cut rises from silence this fast: no click, no audible fade-in. */
        const val HANDOVER_RISE_MS = 20L
        /** A preloaded player this close to the asked start position is used as is (no seek, no AudioTrack drop). */
        private const val REUSE_POSITION_SLACK_MS = 1_000L
        /** A track that brings no data for this long stops waiting: playback pauses (D67). */
        const val NO_DATA_TIMEOUT_MS = 20_000L
        /** How long a ready track waits for [CROSSFADE_HEADROOM_MS] while the previous one still sounds. */
        private const val HEADROOM_MAX_WAIT_MS = 8_000L
        const val DUCK_VOLUME = 0.2f
        const val QUICK_FADE_MS = 150L
        /**
         * A skip during a running transition cuts the sound in flight with this short ramp (no click) and starts the
         * latest target; the tail never plays on and is never dropped abruptly.
         */
        const val RAPID_RAMP_MS = 40L
        /** How long after a skip the engine reports "playing, loading" with nothing started. */
        const val SKIP_WAIT_MAX_MS = 30_000L
        /** No track change for this long counts as calm: the next track may be preloaded again (F4). */
        const val SETTLE_MS = 3_000L
        /** A play / seek rise waits this long at most for the player to sound (it may still be loading). */
        const val RISE_WAIT_MAX_MS = 10_000L
        private const val PROGRESS_INTERVAL_MS = 100L
        /** A jump to the start counts only from at least this far in, landing this close to 0, with no seek this recent. */
        private const val JUMP_FROM_MIN_MS = 5_000L
        private const val JUMP_TO_MAX_MS = 1_500L
        private const val JUMP_SEEK_GRACE_MS = 2_000L
        private const val COPY_POLL_MS = 500L
        private const val IDLE_INTERVAL_MS = 500L
        private const val PERCENT = 100f
        private const val EVENT_BUFFER = 16
    }
}
