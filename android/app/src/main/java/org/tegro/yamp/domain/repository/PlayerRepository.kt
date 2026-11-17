package org.tegro.yamp.domain.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.tegro.yamp.domain.provider.ResolvedStream

/** Playback failure of the active track; [positionMs] lets the controller retry from the same place. */
data class PlaybackFailure(val trackId: String, val positionMs: Long, val reason: String) {
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
     * The user skipped away from the audible track: it fades out at once (about half a second) while the
     * next one is being requested. A [playTrack] that arrives during the fade comes in under its tail;
     * after the fade the engine is silent and reports nothing playing until the next track starts.
     */
    fun fadeOutForSkip()
    /** Music in hand ahead of the playing position; as good as unlimited when unknown. */
    val bufferedAheadMs: Long get() = Long.MAX_VALUE
    /** The track one actually hears now: during a crossfade's wait still the previous one; null in silence. */
    val soundingTrackId: StateFlow<String?>
    /** A track change (a crossfade, its wait) is under way: the line belongs to it. */
    val inTransition: Boolean get() = false
    /** Lowers the volume to silence over [durationMs], then pauses (sleep timer). */
    fun fadeOutAndPause(durationMs: Long)
    /** Resumes the loaded item. Returns false when nothing is loaded and the caller must start the track itself. */
    fun resume(): Boolean
    fun seekTo(position: Long)
    /**
     * Smooth variants, tied to the crossfade length X (crossfade off: same as the plain ones). Pause fades out
     * over X/2 and reports "paused" at once; resume rises over X/2 (a pause still fading is taken back from its
     * current level); seek fades out over X/3, jumps, rises over 2X/3.
     */
    fun pauseSmoothly() = pause()
    fun resumeSmoothly(): Boolean = resume()
    fun seekSmoothly(position: Long) = seekTo(position)
    /** The next track started from silence (a play with nothing loaded) rises over X/2 instead of starting at full. */
    fun riseOnNextStart() {}
    fun setDucked(ducked: Boolean)
    /** 0 disables crossfading: tracks switch immediately and play to their natural end. */
    fun setCrossfadeDuration(durationMs: Long)
    fun triggerSkipNext()
    fun triggerSkipPrev()
    fun stop()
    fun release()
}
