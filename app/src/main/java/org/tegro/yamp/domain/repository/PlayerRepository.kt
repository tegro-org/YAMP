package org.tegro.yamp.domain.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.tegro.yamp.domain.provider.ResolvedStream

/**
 * Playback failure of the active track; [positionMs] lets the controller retry from the same place. [download]: it came
 * from a converted stream's download that already restarted what it could (D91).
 */
data class PlaybackFailure(
    val trackId: String,
    val positionMs: Long,
    val reason: String,
    val download: Boolean = false,
    /** The status the server answered with, when it did (a refused or missing link); null for a network error. */
    val httpCode: Int? = null
) {
    companion object {
        /** A track the user picked never got ready; the engine went back to the one that plays on. */
        const val PICK_FAILED = "PICK_FAILED"

        /** A track brought no data for a long while (D67): playback pauses — slowness never skips or goes back. */
        const val NO_DATA = "NO_DATA"
    }
}

interface PlayerRepository {
    val currentTrackId: StateFlow<String?>
    /** True while the user intends playback (playWhenReady), including buffering — drives the Play/Pause icon. */
    val isPlaying: StateFlow<Boolean>
    /** True while playback is requested but audio is not yet rendering. */
    val isBuffering: StateFlow<Boolean>
    val progress: StateFlow<Long>

    /** How far the current track is loaded (ms into it): the controller tells a slow load from a dead one (D67). */
    fun bufferedPositionMs(): Long

    /**
     * A mark that grows with every bit of the current track loaded: [bufferedPositionMs], plus — while a converted
     * stream's reopen waits for its whole download (D91) — the bytes it writes, so a moving download never counts as
     * "no data" (D67).
     */
    fun loadingProgress(): Long = bufferedPositionMs()

    /** [bufferedPositionMs] as a flow, ticking with [progress]: the progress bars draw it as a lighter part (D81). */
    val bufferedProgress: StateFlow<Long>
    val trackEndedEvent: Flow<Unit>
    val prepareNextTrackEvent: Flow<Unit>
    val skipNextEvent: Flow<Unit>
    val skipPrevEvent: Flow<Unit>
    val playbackErrorEvent: Flow<PlaybackFailure>

    /**
     * A player failed on [trackId]'s stream (the active one, a warm standby or a fading tail): its link is spent
     * and the controller asks the resolver afresh next time. The engine itself never resolves (D74).
     */
    val staleStreamEvent: Flow<String> get() = emptyFlow()

    /**
     * Starts [trackId] from [stream], resolved by the controller (D74). A null [stream] means "the warm standby or
     * the gapless arrival holds it" ([isWarm]); if neither does any more, the start fails with `EMPTY_URL` and the
     * controller resolves again. With [crossfade] the currently audible track keeps playing until the new one
     * is ready, then both are blended over [org.tegro.yamp.data.repository.PlayerRepositoryImpl.CROSSFADE_DURATION_MS].
     */
    fun playTrack(
        trackId: String,
        stream: ResolvedStream?,
        title: String?,
        artist: String?,
        coverUrl: String?,
        crossfade: Boolean = true,
        startPositionMs: Long = 0L,
        /**
         * When the user picked this track (elapsedRealtime), or 0. A pick loads first while the current track
         * plays on untouched; once ready, the current one fades out quickly and the pick starts at once. A pick
         * that fails leaves the current track playing and reports [PlaybackFailure.PICK_FAILED].
         */
        pickAtMs: Long = 0L
    )

    /** The standby holds [trackId] ready to sound at once (a skip to it needs no load). */
    fun isWarm(trackId: String): Boolean

    /** The track a warm standby holds ready right now ([isWarm]), or null: the Next button's "ready" (D80). */
    val warmTrackId: StateFlow<String?> get() = MutableStateFlow(null)

    /** A standby failed on the next track: (track id, error name). The Next button shows "failed" (D80). */
    val standbyFailedEvent: Flow<Pair<String, String>> get() = emptyFlow()

    /** No track change began within the last few seconds and none runs: a burst of skips is over (F4). */
    val settled: Boolean get() = true
    /**
     * A start was asked for, before its stream link is looked up: the journal's TIMING line counts from here,
     * so the link lookup shows in "→link" instead of hiding in "skip→request". Diagnostics only.
     */
    fun noteStartRequested() {}
    /** Readies [trackId] from [stream] (resolved by the controller) to follow the current track. */
    fun prepareNextTrack(trackId: String, stream: ResolvedStream, title: String?, artist: String?, coverUrl: String?)
    /** Volume multipliers by track id (1 = unchanged), for normalization; a track not listed plays as is. */
    fun setTrackGains(gains: Map<String, Float>)

    /** Forgets the warmed-up next track: what it was prepared with no longer holds. */
    fun dropStandby()
    fun pause()
    /**
     * The user skipped away from the audible track. With [overlap] (the next start crossfades), the crossfade
     * transition and nothing else in flight it stays audible and the next [playTrack] overlaps it; otherwise it fades
     * out over the crossfade length (a skip during a running transition: a short ramp) while the next one is being
     * requested. A [playTrack] that arrives during the fade comes in under its tail; after the fade the engine is
     * silent and reports nothing playing until the next track starts.
     */
    fun fadeOutForSkip(overlap: Boolean = false)
    /** Music in hand ahead of the playing position; as good as unlimited when unknown. */
    val bufferedAheadMs: Long get() = Long.MAX_VALUE
    /** The track one actually hears now: during a crossfade's wait still the previous one; null in silence. */
    val soundingTrackId: StateFlow<String?>
    /** The old track is fading out before the new, ready one can sound: a return trial's clock waits for it to end. */
    val inHandover: Boolean get() = false
    /** A track change (a crossfade, its wait) is under way: the line belongs to it. */
    val inTransition: Boolean get() = false
    /** Lowers the volume to silence over [durationMs], then pauses (sleep timer). */
    fun fadeOutAndPause(durationMs: Long)
    /** Resumes the loaded item. Returns false when nothing is loaded and the caller must start the track itself. */
    fun resume(): Boolean
    fun seekTo(position: Long)
    /**
     * Smooth variants (a 0 duration: same as the plain ones). Pause fades out over the play/pause length P and
     * reports "paused" at once; resume rises over P (a pause still fading is taken back from its current level);
     * seek fades out over D/2, jumps, rises over D/2 (D is the seek length).
     */
    fun pauseSmoothly() = pause()
    fun resumeSmoothly(): Boolean = resume()
    fun seekSmoothly(position: Long) = seekTo(position)
    /** The next track started from silence (a play with nothing loaded) rises over P instead of starting at full. */
    fun riseOnNextStart() {}
    fun setDucked(ducked: Boolean)
    /** The crossfade length C; 0 disables crossfading: tracks switch immediately and play to their natural end. */
    fun setCrossfadeDuration(durationMs: Long)

    /** The play/pause fade length P (see [pauseSmoothly]); 0 is instant. */
    fun setPlayPauseDuration(durationMs: Long) {}

    /** The seek fade length D, out and in in total (see [seekSmoothly]); 0 is instant. */
    fun setSeekDuration(durationMs: Long) {}

    /** The transition at a track's natural end: false = crossfade, true = fade then rise (see the setting). */
    fun setTransitionFadeThenRise(enabled: Boolean) {}

    /**
     * The playing track turned out to be unseekable (a progressive stream with no length — a server transcode),
     * once per item: its seeking waits for the whole download ([seekLock], D91).
     */
    val unseekableStreamEvent: Flow<String> get() = emptyFlow()

    /**
     * Seeking the playing track waits for its whole download: null when it seeks; else the share downloaded, 0..1
     * (0: nothing to estimate it by yet) — a converted stream before it is complete (D91). Once complete the lock goes
     * and the first seek reopens the item there ([seekByReopen]); the music never switches by itself. Ticks with
     * [progress].
     */
    val seekLock: StateFlow<Float?> get() = NO_SEEK_LOCK

    /**
     * The playing player can't seek its item, but its whole download is on disk: a seek reopens it at the target
     * (D91). The session then offers seeking although the player itself does not.
     */
    val seekByReopen: StateFlow<Boolean> get() = NO_SEEK_BY_REOPEN
    fun triggerSkipNext()
    fun triggerSkipPrev()
    fun stop()
    fun release()
}

/** [PlayerRepository.seekLock] of an engine that never locks seeking. */
private val NO_SEEK_LOCK: StateFlow<Float?> = MutableStateFlow(null)

/** [PlayerRepository.seekByReopen] of an engine whose players always seek by themselves. */
private val NO_SEEK_BY_REOPEN: StateFlow<Boolean> = MutableStateFlow(false)
