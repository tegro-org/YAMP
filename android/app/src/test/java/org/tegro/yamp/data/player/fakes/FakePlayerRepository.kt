package org.tegro.yamp.data.player.fakes

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.tegro.yamp.domain.repository.PlaybackFailure
import org.tegro.yamp.domain.repository.PlayerRepository

/**
 * The playback engine as the controller sees it: every call is recorded, the state flows follow the real
 * engine's rules. [playTrack] loads the track and starts buffering (wants to play, no sound yet); [sounds]
 * is the moment audio renders, [fail] a player error on the loaded track.
 */
class FakePlayerRepository(private val context: Context) : PlayerRepository {
    data class Call(val name: String, val trackId: String? = null, val url: String? = null, val positionMs: Long? = null)

    /** One entry per engine call, in order. Copy-on-write: the controller's queue operations finish on IO threads. */
    val calls = java.util.concurrent.CopyOnWriteArrayList<Call>()

    override val currentTrackId = MutableStateFlow<String?>(null)
    override val isPlaying = MutableStateFlow(false)
    override val isBuffering = MutableStateFlow(false)
    override val progress = MutableStateFlow(0L)
    override val trackEndedEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    override val prepareNextTrackEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    override val skipNextEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    override val skipPrevEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    override val playbackErrorEvent = MutableSharedFlow<PlaybackFailure>(extraBufferCapacity = 8)

    /** Only MusicService reads this; built on first use so the tests never pay for an ExoPlayer. */
    override val activePlayerState: StateFlow<ExoPlayer> by lazy { MutableStateFlow(ExoPlayer.Builder(context).build()) }

    val names: List<String> get() = calls.map { it.name }
    val played: List<Call> get() = calls.filter { it.name == "playTrack" }
    val prepared: List<String?> get() = calls.filter { it.name == "prepareNextTrack" }.map { it.trackId }

    /** The loaded track renders audio. */
    fun sounds() {
        isBuffering.value = false
    }

    /** A player error on the loaded track, as the real engine reports it: it no longer wants to play. */
    fun fail(trackId: String, positionMs: Long, reason: String = "ERROR_CODE_IO_BAD_HTTP_STATUS") {
        isPlaying.value = false
        isBuffering.value = false
        check(playbackErrorEvent.tryEmit(PlaybackFailure(trackId, positionMs, reason)))
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
        calls += Call("playTrack", trackId, url, startPositionMs)
        currentTrackId.value = trackId
        progress.value = startPositionMs
        isBuffering.value = true
        isPlaying.value = true
    }

    override fun prepareNextTrack(trackId: String, url: String, title: String?, artist: String?, coverUrl: String?) {
        calls += Call("prepareNextTrack", trackId, url)
    }

    override fun setTrackGains(gains: Map<String, Float>) {
        calls += Call("setTrackGains")
    }

    override fun dropStandby() {
        calls += Call("dropStandby")
    }

    override fun pause() {
        calls += Call("pause")
        isPlaying.value = false
        isBuffering.value = false
    }

    override fun fadeOutForSkip() {
        calls += Call("fadeOutForSkip")
    }

    override fun fadeOutAndPause(durationMs: Long) {
        calls += Call("fadeOutAndPause")
    }

    override fun resume(): Boolean {
        calls += Call("resume")
        if (currentTrackId.value == null) return false
        isPlaying.value = true
        return true
    }

    override fun seekTo(position: Long) {
        calls += Call("seekTo", positionMs = position)
        progress.value = position
    }

    override fun setDucked(ducked: Boolean) {
        calls += Call("setDucked")
    }

    override fun setCrossfadeDuration(durationMs: Long) {
        calls += Call("setCrossfadeDuration")
    }

    override fun triggerSkipNext() {
        calls += Call("triggerSkipNext")
    }

    override fun triggerSkipPrev() {
        calls += Call("triggerSkipPrev")
    }

    override fun stop() {
        calls += Call("stop")
        currentTrackId.value = null
        isPlaying.value = false
        isBuffering.value = false
    }

    override fun release() {
        calls += Call("release")
    }

    override fun getPlayer(): Player = activePlayerState.value
}
