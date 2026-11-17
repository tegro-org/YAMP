package org.tegro.yamp.data.repository

import androidx.core.net.toUri
import android.os.SystemClock
import android.util.Log
import org.tegro.yamp.data.player.FadeCurve
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import org.tegro.yamp.data.network.TrackDownloadManager
import org.tegro.yamp.domain.repository.PlaybackFailure
import org.tegro.yamp.domain.provider.ResolvedStream
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    private val wakeLockHelper: org.tegro.yamp.data.player.PlaybackWakeLockHelper,
    private val journal: org.tegro.yamp.data.player.PlaybackJournal? = null,
    /** "Keep audio streams open" (Settings → Playback) and the silent clip a parked player holds meanwhile. */
    private val parking: ParkingPolicy? = null
) : PlayerRepository, org.tegro.yamp.data.player.SessionPlayers {

    /**
     * Instead of being stopped, a player that finished (the outgoing side of a crossfade, a skipped track, a
     * dropped standby) is paused on a one-second silent clip: ExoPlayer then keeps its AudioTrack for the next
     * track of the same format, and a head unit that takes the sound whenever a new stream appears sees none.
     */
    class ParkingPolicy(val keep: Flow<Boolean>, val silenceUri: String, val bothRunning: Flow<Boolean> = flowOf(false))

    private val idler = SilentIdler(parking?.silenceUri)

    private fun isParked(player: ExoPlayer): Boolean = player.currentMediaItem?.mediaId == PARKED_ID

    private fun note(kind: org.tegro.yamp.data.player.PlaybackJournal.Kind, title: CharSequence? = null, detail: String? = null, valueMs: Long = 0L) {
        journal?.record(kind, title?.toString(), detail, valueMs)
    }

    /** Underrun and start-timing lines for the journal (see [org.tegro.yamp.data.player.EngineDiagnostics]). */
    private val diagnostics = org.tegro.yamp.data.player.EngineDiagnostics(journal) { if (it === outgoingPlayer) "outgoing" else if (it === activePlayer) "active" else "standby" }

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

    // Volume requested by the crossfade, before ducking is applied.
    private val logicalVolume = floatArrayOf(1f, 1f)
    private var duckFactor = 1f
    @Volatile
    private var trackGains: Map<String, Float> = emptyMap()

    private fun gainOf(player: ExoPlayer): Float = player.currentMediaItem?.mediaId?.let { trackGains[it] } ?: 1f

    /** The volume the player should have now: crossfade position × ducking × the track's normalization. */
    private fun applyVolume(player: ExoPlayer) {
        // A just-cleared player may still drain its last buffer: it stays at its faded level until it has really
        // stopped or got a new item (the "pop" at the end of a skip fade)
        if (volumeHeld[indexOf(player)]) return
        player.volume = (logicalVolume[indexOf(player)] * duckFactor * gainOf(player)).coerceIn(0f, 1f)
    }

    /** Per player: the volume restore is held back until the player has stopped or got a new item. */
    private val volumeHeld = BooleanArray(2)

    /** After [clearPlayer]: full volume for the player's next item, applied only once it can't sound the old one. */
    private fun restoreVolumeLater(player: ExoPlayer) {
        val i = indexOf(player)
        logicalVolume[i] = 1f
        // stop() reports IDLE at once while the playback thread still drains the AudioTrack, so the state can't
        // tell; the hold ends with the player's next item or after [VOLUME_HOLD_MS]
        volumeHeld[i] = true
        scope.launch {
            delay(VOLUME_HOLD_MS)
            releaseVolumeHold(player)
        }
    }

    private fun releaseVolumeHold(player: ExoPlayer) {
        val i = indexOf(player)
        if (!volumeHeld[i]) return
        volumeHeld[i] = false
        applyVolume(player)
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

    init {
        setupPlayerListener(playerA, 0)
        setupPlayerListener(playerB, 1)

        parking?.let { policy -> scope.launch { policy.keep.collect { idler.keepStreams = it } } }
        // "Keep both players running" (see [SilentIdler]); switched off, an idling standby is stopped at its next use
        parking?.let { policy -> scope.launch { policy.bothRunning.collect { on -> idler.enabled = on; if (on && activePlayer.isPlaying) idler.setRunning(standbyPlayer, true, outgoingPlayer) } } }

        scope.launch {
            while (isActive) {
                val currentP = activePlayer
                // The track that plays now, or the one a crossfade waits for: never the previous one, loaded to
                // its end long ago — the cache would take the line from the new track at its start (upstream 1.14.3)
                if (currentP.isPlaying || awaitingIncoming) bufferedAhead = aheadOf(currentP)
                if (currentP.isPlaying && !isParked(currentP)) {
                    journal?.addPlayed(PROGRESS_INTERVAL_MS)
                    updateProgress(currentP)
                    checkTransitionTriggers(currentP)
                    // "Keep both players running": a standby stopped during a burst of skips starts idling once
                    // things are calm again, not while every skip would rebuild it (F4)
                    idleStandbyWhenCalm()
                    delay(PROGRESS_INTERVAL_MS)
                } else {
                    delay(IDLE_INTERVAL_MS)
                }
            }
        }
    }

    /** All of a track loaded counts as plenty: its end is no reason to hold back. */
    private fun aheadOf(player: ExoPlayer): Long {
        val loadedToTheEnd = player.duration > 0 && player.bufferedPosition >= player.duration - 500
        return if (loadedToTheEnd) Long.MAX_VALUE else player.totalBufferedDuration
    }

    private fun updateProgress(player: ExoPlayer) {
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
        diagnostics.attach(player, playerIdx)
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                // A new item: the old one can't sound any more, so a held volume restore applies now
                releaseVolumeHold(player)
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
                if (player === idler.handover && mediaItem != null && mediaItem.mediaId != PARKED_ID) {
                    // The queued track plays now: the silence before it goes; a crossfade's hold keeps it still
                    idler.handedOver(player)
                    if (awaitingIncoming && player === activePlayer) player.playWhenReady = false
                    diagnostics.arrived(player)
                    refreshPlaybackState()
                }
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
                            PlaybackFailure(failedId, player.currentPosition.coerceAtLeast(0L), error.errorCodeName)
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
        val otherAudible = other !== outgoingPlayer && other.isPlaying && !isParked(other) && other.volume > 0f
        val wantsToPlay = otherAudible || awaitingSkipTarget || idler.handover === p || (p.playWhenReady || awaitingIncoming) &&
            p.playerError == null &&
            p.mediaItemCount > 0 && !isParked(p) &&
            p.playbackState != Player.STATE_ENDED
        _isPlaying.value = wantsToPlay && !pausing
        // Only a real STATE_BUFFERING counts as loading: a paused-but-wants-to-play limbo (focus loss) is not a
        // spinner, otherwise the indicator sticks when playback is interrupted mid-start. While a crossfade's
        // previous track still sounds, the next one loading is not a stall either (upstream 1.14.3).
        val outgoing = outgoingPlayer
        val tailSounds = awaitingIncoming && outgoing != null && outgoing.isPlaying
        _isBuffering.value = awaitingSkipTarget || idler.handover === p && !tailSounds || wantsToPlay && !tailSounds && p.playbackState == Player.STATE_BUFFERING
        _soundingTrackId.value = when {
            outgoing != null && outgoing.isPlaying && (awaitingIncoming || !p.isPlaying) -> outgoing.currentMediaItem?.mediaId
            p.isPlaying && !isParked(p) -> p.currentMediaItem?.mediaId
            otherAudible -> other.currentMediaItem?.mediaId
            else -> null
        }
    }

    override fun getPlayer(): Player = activePlayer

    /** The on-disk cache key the item was built with (D86). */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun MediaItem.cacheKey(): String? = localConfiguration?.customCacheKey

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun buildMediaItem(trackId: String, uri: String, title: String?, artist: String?, coverUrl: String?, cacheKey: String? = null): MediaItem {
        val mediaMetadata = MediaMetadata.Builder()
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
        scope.launch {
            // State may have changed since the controller asked.
            if (outgoingPlayer != null || _currentTrackId.value == trackId) return@launch

            val item = buildMediaItem(trackId, playUri, title, artist, coverUrl, stream.cacheKey)
            if (gaplessFits()) {
                // Crossfade off: behind the playing track on its own player, no second AudioTrack (F4)
                dropQueuedOnActive()
                activePlayer.addMediaItem(item)
                queuedOnActive = trackId
                preloadedTrackId = trackId
                Log.d(TAG, "Queued $trackId gapless behind the playing track")
                return@launch
            }
            val standby = standbyPlayer
            setVolume(standby, 0f)
            preloadedTrackId = trackId
            if (idler.preload(standby, item)) return@launch // behind its silence, held when it arrives
            releaseForReuse(standby)
            standby.playWhenReady = false
            standby.setMediaItem(item, 0L)
            standby.prepare()
            Log.d(TAG, "Preloaded warm standby player with trackId = $trackId")
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
        return crossfadeDurationMs == 0L && !idler.enabled && !idler.keepStreams && outgoingPlayer == null &&
            !awaitingIncoming && p.playerError == null && !isParked(p) && p.mediaItemCount > 0 &&
            p.currentMediaItem?.mediaId == _currentTrackId.value
    }

    /** A track queued behind the playing one is taken off again (the queue changed, a skip, crossfade switched on). */
    private fun dropQueuedOnActive() {
        if (queuedOnActive == null) return
        queuedOnActive = null
        if (preloadedTrackId != null && standbyPlayer.currentMediaItem?.mediaId != preloadedTrackId) preloadedTrackId = null
        val p = activePlayer
        val after = p.currentMediaItemIndex + 1
        if (p.mediaItemCount > after) p.removeMediaItems(after, p.mediaItemCount)
    }

    /** [player] moved on by itself to [arrivedId], the track queued behind the playing one (F4). */
    private fun isGaplessArrival(player: ExoPlayer, arrivedId: String?): Boolean =
        arrivedId != null && arrivedId == queuedOnActive && player === activePlayer && outgoingPlayer == null

    /** "Keep both players running": an empty standby starts idling on the silence, once no track change runs (F4). */
    private fun idleStandbyWhenCalm() {
        if (!idler.enabled || inTransition || !calm()) return
        val standby = standbyPlayer
        if (standby.mediaItemCount == 0) idler.setRunning(standby, true, null)
    }

    /** No track change for [SETTLE_MS] and none running: idling or parking a player costs no rebuild in a burst. */
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
        stream?.let { org.tegro.yamp.data.player.StreamHeaders.register(it.uri, it.headers) }
        playJob = scope.launch {
            // The player already moved on to this track gapless (F4): nothing to resolve, the start takes it over
            if (gaplessArrived == trackId && activePlayer.currentMediaItem?.mediaId == trackId) {
                activePlayer.currentMediaItem?.let { startTransition(it, crossfade, startPositionMs, pickAtMs) }
                return@launch
            }
            // A warm standby already holds this track: its link is in hand. The engine never resolves (D74): what
            // it plays is the warm standby or the stream the controller resolved
            val warmUri = standbyPlayer.currentMediaItem?.localConfiguration?.uri?.toString()?.takeIf { isWarm(trackId) }
            val playUri = warmUri ?: stream?.uri?.takeIf { it.isNotBlank() }
            if (playUri == null) {
                Log.w(TAG, "playUri is blank for track $trackId")
                note(org.tegro.yamp.data.player.PlaybackJournal.Kind.ERROR, title, "no stream URL")
                _playbackErrorEvent.tryEmit(PlaybackFailure(trackId, startPositionMs, "EMPTY_URL"))
                return@launch
            }
            val cacheKey = if (warmUri != null) standbyPlayer.currentMediaItem?.cacheKey() else stream?.cacheKey
            startTransition(buildMediaItem(trackId, playUri, title, artist, coverUrl, cacheKey), crossfade, startPositionMs, pickAtMs)
        }
    }

    override fun noteStartRequested() = diagnostics.requested()

    override fun bufferedPositionMs(): Long = activePlayer.bufferedPosition

    /** Publishes which track a warm standby holds ready (the Next button, D80). */
    private fun refreshWarm() {
        _warmTrackId.value = preloadedTrackId?.takeIf { isWarm(it) }
    }

    override fun isWarm(trackId: String): Boolean = gaplessArrived == trackId || standbyPlayer.let {
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
        // A new track ends any pause / play / seek ramp of the old one
        val rise = riseNextStart
        riseNextStart = false
        endRamp()
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
        val isCurrentAudible = current.isPlaying && current.playerError == null && !isParked(current)
        pickAt = if (isCurrentAudible) pickAtMs else 0L
        // A pick writes its START once it is ready, with the wait
        if (pickAt == 0L) note(org.tegro.yamp.data.player.PlaybackJournal.Kind.START, item.mediaMetadata.title, if (crossfade) "crossfade" else "cut", startPositionMs)

        val incoming = standbyPlayer
        val reusePreloaded = preloadedTrackId == trackId &&
            incoming.currentMediaItem?.mediaId == trackId &&
            incoming.playerError == null &&
            incoming.playbackState != Player.STATE_IDLE
        preloadedTrackId = null

        // An idling standby (both players running) takes a track from the start behind its silence; a start
        // mid-track would need a seek, which drops the AudioTrack anyway, so it is loaded as usual
        val behindSilence = !reusePreloaded && startPositionMs == 0L && (idler.isIdling(incoming) || idler.isQueued(incoming, trackId))
        if (reusePreloaded) {
            // A kept player may still hold this track where it was left (played earlier, then skipped away):
            // a skip back to it starts from the asked position (0 unless a session restore), not from there
            if (kotlin.math.abs(incoming.currentPosition - startPositionMs) > REUSE_POSITION_SLACK_MS) incoming.seekTo(startPositionMs)
        } else if (behindSilence) {
            idler.queueBehind(incoming, item)
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
            skipTail.pause()
            clearPlayer(skipTail)
            restoreVolumeLater(skipTail)
        }
        if (skipTail === current) {
            setVolume(incoming, 1f)
            incoming.playWhenReady = true
        } else if (pickAt > 0L || crossfadesFrom(isCurrentAudible, crossfade)) {
            wakeLockHelper.acquireTransitionLock(30_000L)
            outgoingPlayer = current
            setVolume(incoming, 0f)
            // Silent and still until it can sound without a stop; the outgoing track plays its tail meanwhile.
            // Nothing of the new track is in hand yet: the cache waits until it has its own buffer
            awaitingIncoming = true
            bufferedAhead = 0L
            // Behind silence it keeps playing (at volume 0) until the track arrives, which then holds it
            if (!behindSilence) incoming.playWhenReady = false
            crossfadeJob = scope.launch { runCrossfade(current, incoming) }
        } else {
            // A cut: the old track is silent before the new one sounds (no overlap, see above)
            if (current.playWhenReady) current.pause()
            setVolume(current, 1f)
            val riseMs = crossfadeDurationMs / 2
            if (rise && !isCurrentAudible && riseMs > 0L) {
                setVolume(incoming, 0f)
                incoming.playWhenReady = true
                riseTo(incoming, from = 0f, durationMs = riseMs, what = "play")
            } else {
                setVolume(incoming, 1f)
                incoming.playWhenReady = true
            }
            clearPlayer(current) // after the new one plays: with both players running, the old one idles
        }
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
        setVolume(p, 1f)
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
        // Queued behind silence: the silence plays out first (at most its length)
        while (stillMine() && idler.handover === incoming && SystemClock.elapsedRealtime() - waitStart < SILENCE_HANDOVER_MAX_MS) delay(WAIT_STEP_MS)
        if (stillMine() && idler.handover === incoming) loadPastSilence(incoming)
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
                Log.w(TAG, "Incoming track $id brought no data in ${NO_DATA_TIMEOUT_MS / 1000} s")
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
        awaitingIncoming = false
        if (pickAt > 0L) {
            // The pick is ready: the current track fades out quickly, as for Next, and stops — only then does the
            // pick sound. Never both at once: under Poweramp's DVC the fade is not heard and the old track would leak
            note(org.tegro.yamp.data.player.PlaybackJournal.Kind.START, incoming.mediaMetadata.title, "pick, tap→ready ${SystemClock.elapsedRealtime() - pickAt} ms")
            pickAt = 0L
            fade(outgoing, from = logicalVolume[indexOf(outgoing)], to = 0f, durationMs = SKIP_FADE_MS)
            if (!stillMine()) return
            outgoing.pause()
            setVolume(incoming, 1f)
            incoming.playWhenReady = true
            refreshPlaybackState()
        } else {
            // A crossfade at a track's natural end overlaps the two on purpose. It needs each player's own volume:
            // with an effect that takes over the session's volume (Poweramp's DVC; set "Без DVC" for YAMP) the
            // blend is not heard as a fade
            incoming.playWhenReady = true
            refreshPlaybackState()
            crossfadeTail(outgoing, incoming, late, waitStart)
        }
        if (!stillMine()) return
        outgoingPlayer = null
        clearPlayer(outgoing)
        restoreVolumeLater(outgoing)
        setVolume(incoming, 1f)
        wakeLockHelper.releaseTransitionLock()
    }

    /**
     * The silence on [incoming] did not hand over to the track queued behind it in time: the track is loaded the
     * usual way instead. The silent clip must never count as the track — it would "start" with no title and nothing
     * would play (b83: a pick after quick skips).
     */
    private fun loadPastSilence(incoming: ExoPlayer) {
        val item = (incoming.mediaItemCount - 1 downTo 0).map { incoming.getMediaItemAt(it) }.firstOrNull { it.mediaId != PARKED_ID } ?: return
        note(org.tegro.yamp.data.player.PlaybackJournal.Kind.ERROR, item.mediaMetadata.title, "handover from silence timed out, loading the track directly")
        releaseForReuse(incoming)
        incoming.setMediaItem(item, 0L)
        incoming.prepare()
        incoming.playWhenReady = false
    }

    private suspend fun crossfadeTail(outgoing: ExoPlayer, incoming: ExoPlayer, late: Boolean, waitStart: Long) {
        note(if (late) org.tegro.yamp.data.player.PlaybackJournal.Kind.CROSSFADE_LATE else org.tegro.yamp.data.player.PlaybackJournal.Kind.CROSSFADE, incoming.mediaMetadata.title, valueMs = SystemClock.elapsedRealtime() - waitStart)

        if (late) {
            fade(incoming, from = 0f, to = 1f, durationMs = LATE_FADE_IN_MS)
        } else {
            equalPowerFade(outgoing, incoming, crossfadeDurationMs)
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
        alongTheClock(durationMs) { part -> setVolume(player, FadeCurve.level(from, to, part)) }
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
        // Cancelled before the early return: a reverted pick leaves no outgoing player but may leave its job (F2)
        crossfadeJob?.cancel()
        crossfadeJob = null
        val out = outgoingPlayer ?: return
        awaitingIncoming = false
        if (skipFading === out) cancelSkipFade() // a skip tail: this quick fade takes over its end
        try {
            fade(out, from = logicalVolume[indexOf(out)], to = 0f, durationMs = QUICK_FADE_MS)
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

    override fun fadeOutForSkip() {
        val player = activePlayer
        if (skipFading === player) return // skipped again while the fade runs: nothing new to do
        endRamp()
        diagnostics.skipRequested()
        fadeJob?.cancel()
        lastStartAt = SystemClock.elapsedRealtime()
        // The fading track must not play on into a track queued behind it (F4); the controller starts the target
        dropQueuedOnActive()
        gaplessArrived = null
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
            restoreVolumeLater(player)
            _currentTrackId.value = null
            _progress.value = 0L
            _bufferedProgress.value = 0L
            if (tail != null && tail.isPlaying) {
                skipFading = tail
                skipFadeJob = scope.launch {
                    fade(tail, from = logicalVolume[indexOf(tail)], to = 0f, durationMs = SKIP_FADE_MS)
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
            fade(player, from = logicalVolume[indexOf(player)], to = 0f, durationMs = SKIP_FADE_MS)
            // Faded: it stops now rather than playing on at volume 0. A player's own volume is not trusted — under
            // an effect that takes over the session's volume (Poweramp's DVC) a "silent" old track is still heard.
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

    override fun pause() {
        // Mid-ramp the level stays where it is (no jump up just before the pause); a resume sets it again
        val ramping = rampKind != null
        endRamp()
        fadeJob?.cancel()
        skipTargetArrived()
        cancelSkipFade()
        if (ramping) finishCrossfadeNow(restoreActiveVolume = false) else finishCrossfadeNow(restoreActiveVolume = true)
        idler.handover = null
        activePlayer.pause()
        idler.setRunning(standbyPlayer, false, outgoingPlayer)
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
            val start = logicalVolume[indexOf(player)]
            val steps = (durationMs / FADE_STEP_MS).toInt().coerceAtLeast(1)
            for (step in 1..steps) {
                setVolume(player, start * (1f - step.toFloat() / steps))
                delay(FADE_STEP_MS)
            }
            player.pause()
            idler.setRunning(standbyPlayer, false, outgoingPlayer)
            setVolume(player, 1f)
            refreshPlaybackState()
        }
    }

    override fun resume(): Boolean {
        val player = activePlayer
        if (player.mediaItemCount == 0 || isParked(player)) return false
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
        idler.setRunning(standbyPlayer, true, outgoingPlayer)
        refreshPlaybackState()
        return true
    }

    override fun stop() {
        endRamp()
        playJob?.cancel()
        skipTargetArrived()
        cancelSkipFade()
        finishCrossfadeNow(restoreActiveVolume = true)
        for (p in listOf(playerA, playerB)) {
            clearPlayer(p, idle = false)
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
        p.seekTo(position)
        // Seeking back out of the crossfade window must allow the triggers to fire again.
        val duration = p.duration
        if (duration > 0L && duration - position > crossfadeDurationMs) hasEmittedTrackEndedForCurrent = false
        if (duration > 0L && duration - position > PREPARE_STANDBY_THRESHOLD_MS) hasEmittedPrepareForCurrent = false
    }

    /** Running smooth pause / play / seek ramp (tied to the crossfade length) and what it does. */
    private var rampJob: Job? = null
    private var rampKind: Ramp? = null
    private enum class Ramp { PAUSE, PLAY, SEEK_OUT, SEEK_IN }
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

    private fun level(player: ExoPlayer) = logicalVolume[indexOf(player)]

    private fun fadeNote(what: String, ms: Long) =
        note(org.tegro.yamp.data.player.PlaybackJournal.Kind.FADE, activePlayer.currentMediaItem?.mediaMetadata?.title, "$what $ms ms")

    /** Waits (bounded) until [player] actually sounds, then raises it from [from] to full over [durationMs]. */
    private fun riseTo(player: ExoPlayer, from: Float, durationMs: Long, what: String, kind: Ramp = Ramp.PLAY) {
        fadeNote(what, durationMs)
        rampKind = kind
        rampJob = scope.launch {
            var waited = 0L
            while (!player.isPlaying && waited < RISE_WAIT_MAX_MS) {
                delay(CROSSFADE_STEP_MS)
                waited += CROSSFADE_STEP_MS
            }
            fade(player, from, 1f, durationMs.coerceAtLeast(1L))
            rampKind = null
        }
    }

    override fun pauseSmoothly() {
        val x = crossfadeDurationMs
        val p = activePlayer
        if (pausing) return
        if (x <= 0L || !p.isPlaying || isParked(p)) {
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
        val ms = (x / 2 * from).toLong().coerceAtLeast(1L)
        fadeNote("pause", x / 2)
        rampKind = Ramp.PAUSE
        rampJob = scope.launch {
            fade(p, from, 0f, ms)
            rampKind = null
            rampJob = null
            pausing = false
            if (p !== activePlayer) return@launch
            idler.handover = null
            p.pause() // stays at 0: a resume rises from silence, a plain one sets full volume first
            idler.setRunning(standbyPlayer, false, outgoingPlayer)
            refreshPlaybackState()
        }
    }

    override fun resumeSmoothly(): Boolean {
        val x = crossfadeDurationMs
        val p = activePlayer
        if (x <= 0L) return resume()
        if (pausing && rampKind == Ramp.PAUSE) {
            // Play during the pause fade: back up from where the sound is, no restart
            rampJob?.cancel()
            pausing = false
            val from = level(p)
            riseTo(p, from, (x / 2 * (1f - from)).toLong(), "play")
            refreshPlaybackState()
            return true
        }
        if (p.mediaItemCount == 0 || isParked(p)) return false
        if (p.playerError != null || p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED) {
            riseNextStart = true // the restart the controller makes rises too
            return resume()
        }
        endRamp()
        setVolume(p, 0f)
        p.play()
        idler.setRunning(standbyPlayer, true, outgoingPlayer)
        refreshPlaybackState()
        riseTo(p, from = 0f, durationMs = x / 2, what = "play")
        return true
    }

    override fun seekSmoothly(position: Long) {
        val x = crossfadeDurationMs
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
                riseTo(p, from, (x * 2 / 3 * (1f - from)).toLong(), "seek", Ramp.SEEK_IN)
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
            fade(p, from, 0f, (x / 3 * from).toLong().coerceAtLeast(1L))
            if (p !== activePlayer) return@launch
            seekNow(seekTarget)
            rampKind = Ramp.SEEK_IN
            var waited = 0L
            while (!p.isPlaying && waited < RISE_WAIT_MAX_MS) {
                delay(CROSSFADE_STEP_MS)
                waited += CROSSFADE_STEP_MS
            }
            fade(p, 0f, 1f, (x * 2 / 3).coerceAtLeast(1L))
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
        if (crossfadeDurationMs > 0L) scope.launch { if (queuedOnActive != null) { dropQueuedOnActive(); hasEmittedPrepareForCurrent = false } }
    }

    private fun indexOf(player: ExoPlayer) = if (player === playerA) 0 else 1

    /**
     * Stops and empties [player]; a pending audio-session change is applied while it holds nothing.
     * With both players running and the other one playing, it idles on silence instead ([SilentIdler]) — that
     * setting wins over "keep audio streams", which only parks a player paused; [idle] false (a full stop)
     * never idles.
     */
    private fun clearPlayer(player: ExoPlayer, idle: Boolean = true) {
        if (idler.handover === player) idler.handover = null
        // In a burst of skips a silent clip would be one more AudioTrack per skip (Media3 opens a new one for every
        // item set): the player just stops, and an idling standby comes back once things are calm (F4)
        val calm = calm()
        val idleNow = calm && idle && idler.enabled && (if (player === playerA) playerB else playerA).let { it.playWhenReady && it.mediaItemCount > 0 }
        // Looping the silence until a track is queued behind it, or parked on it, or stopped
        val keptOnSilence = idleNow && idler.idle(player) || calm && idler.park(player)
        if (!keptOnSilence) {
            player.stop()
            player.clearMediaItems()
        }
    }

    /** Before a player gets a new item: a stopped player is stopped again (harmless); a parked one is left as is. */
    private fun releaseForReuse(player: ExoPlayer) {
        idler.forget(player)
        if (!(idler.keepStreams && isParked(player))) player.stop()
    }

    private fun setVolume(player: ExoPlayer, volume: Float) {
        logicalVolume[indexOf(player)] = volume
        volumeHeld[indexOf(player)] = false
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
        /** A cleared player stays at its faded volume this long, while its AudioTrack drains (no pop). */
        private const val VOLUME_HOLD_MS = 500L
        /** A preloaded player this close to the asked start position is used as is (no seek, no AudioTrack drop). */
        private const val REUSE_POSITION_SLACK_MS = 1_000L
        /** A track that brings no data for this long stops waiting: playback pauses (D67). */
        const val NO_DATA_TIMEOUT_MS = 20_000L
        /** The silent clip ahead of a queued track plays out in at most this long. */
        const val SILENCE_HANDOVER_MAX_MS = 8_000L
        /** How long a ready track waits for [CROSSFADE_HEADROOM_MS] while the previous one still sounds. */
        private const val HEADROOM_MAX_WAIT_MS = 8_000L
        const val DUCK_VOLUME = 0.2f
        const val QUICK_FADE_MS = 150L
        /**
         * A manual skip silences the current track this fast, then stops it; the next one starts after it, never
         * under it. Short: the new track waits for it, and long enough to avoid a click.
         */
        const val SKIP_FADE_MS = 150L
        /** How long after a skip the engine reports "playing, loading" with nothing started. */
        const val SKIP_WAIT_MAX_MS = 30_000L
        /** The media id of the silent clip a parked player holds (never a track id). */
        const val PARKED_ID = "~parked"
        /** No track change for this long counts as calm: a player set aside may idle or park on the silent clip (F4). */
        const val SETTLE_MS = 3_000L
        private const val FADE_STEP_MS = 50L
        /** A play / seek rise waits this long at most for the player to sound (it may still be loading). */
        const val RISE_WAIT_MAX_MS = 10_000L
        private const val PROGRESS_INTERVAL_MS = 100L
        private const val IDLE_INTERVAL_MS = 500L
        private const val EVENT_BUFFER = 16
    }
}
