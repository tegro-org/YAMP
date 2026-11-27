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
class FakePlayerRepository(private val context: Context) : PlayerRepository, org.tegro.yamp.data.player.SessionPlayers {
    data class Call(val name: String, val trackId: String? = null, val url: String? = null, val positionMs: Long? = null, val pick: Boolean = false)

    /** Tracks the standby holds ready ([isWarm]). */
    val warm = mutableSetOf<String>()

    /** One entry per engine call, in order. Copy-on-write: the controller's queue operations finish on IO threads. */
    val calls = java.util.concurrent.CopyOnWriteArrayList<Call>()

    override val currentTrackId = MutableStateFlow<String?>(null)
    override val isPlaying = MutableStateFlow(false)
    override val isBuffering = MutableStateFlow(false)
    override val soundingTrackId = MutableStateFlow<String?>(null)
    override val progress = MutableStateFlow(0L)
    override val bufferedProgress = MutableStateFlow(0L)

    /** How far the loaded track is loaded: a test moves it to show a slow load still bringing data. */
    @Volatile
    var buffered = 0L
    override fun bufferedPositionMs(): Long = buffered

    /** Bytes a converted stream's download wrote while a reopen waits for it (D91). */
    var written = 0L
    override fun loadingProgress(): Long = buffered + written
    override val trackEndedEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    override val prepareNextTrackEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    override val skipNextEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    override val skipPrevEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    override val playbackErrorEvent = MutableSharedFlow<PlaybackFailure>(extraBufferCapacity = 8)
    override val staleStreamEvent = MutableSharedFlow<String>(extraBufferCapacity = 8)
    override val unseekableStreamEvent = MutableSharedFlow<String>(extraBufferCapacity = 8)
    override val seekLock = MutableStateFlow<Float?>(null)
    override val warmTrackId = MutableStateFlow<String?>(null)
    override val standbyFailedEvent = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 8)

    /** A burst of skips still runs: the controller holds back the next track's preparation (F4, D80). */
    @Volatile
    var unsettled = false
    override val settled: Boolean get() = !unsettled

    /** Only MusicService reads this; built on first use so the tests never pay for an ExoPlayer. */
    override val activePlayerState: StateFlow<ExoPlayer> by lazy { MutableStateFlow(ExoPlayer.Builder(context).build()) }

    val names: List<String> get() = calls.map { it.name }
    val played: List<Call> get() = calls.filter { it.name == "playTrack" }
    val prepared: List<String?> get() = calls.filter { it.name == "prepareNextTrack" }.map { it.trackId }

    /** The loaded track renders audio. */
    fun sounds() {
        isBuffering.value = false
        soundingTrackId.value = currentTrackId.value
    }

    /** A player error on the loaded track, as the real engine reports it: it no longer wants to play. */
    fun fail(trackId: String, positionMs: Long, reason: String = "ERROR_CODE_IO_BAD_HTTP_STATUS", download: Boolean = false, httpCode: Int? = null) {
        isPlaying.value = false
        isBuffering.value = false
        soundingTrackId.value = null
        check(playbackErrorEvent.tryEmit(PlaybackFailure(trackId, positionMs, reason, download, httpCode)))
    }

    override fun playTrack(
        trackId: String,
        stream: org.tegro.yamp.domain.provider.ResolvedStream?,
        title: String?,
        artist: String?,
        coverUrl: String?,
        crossfade: Boolean,
        startPositionMs: Long,
        pickAtMs: Long
    ) {
        calls += Call("playTrack", trackId, stream?.uri.orEmpty(), startPositionMs, pick = pickAtMs > 0L)
        currentTrackId.value = trackId
        soundingTrackId.value = null
        progress.value = startPositionMs
        isBuffering.value = true
        isPlaying.value = true
    }

    override fun isWarm(trackId: String): Boolean = trackId in warm

    override fun prepareNextTrack(trackId: String, stream: org.tegro.yamp.domain.provider.ResolvedStream, title: String?, artist: String?, coverUrl: String?) {
        calls += Call("prepareNextTrack", trackId, stream.uri)
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
        soundingTrackId.value = null
    }

    override fun fadeOutForSkip(overlap: Boolean) {
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

    /** Which of the smooth variants the controller used (the plain call is still logged in [calls]). */
    val smoothCalls = java.util.concurrent.CopyOnWriteArrayList<String>()
    var riseOnNextStartCalls = 0

    override fun pauseSmoothly() {
        smoothCalls += "pause"
        pause()
    }

    override fun resumeSmoothly(): Boolean {
        smoothCalls += "resume"
        return resume()
    }

    override fun seekSmoothly(position: Long) {
        smoothCalls += "seek"
        seekTo(position)
    }

    override fun riseOnNextStart() {
        riseOnNextStartCalls++
    }

    override fun setDucked(ducked: Boolean) {
        calls += Call("setDucked")
    }

    /** The last crossfade length the controller set, in ms (−1: never set). */
    var crossfadeMs = -1L

    override fun setCrossfadeDuration(durationMs: Long) {
        crossfadeMs = durationMs
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
        soundingTrackId.value = null
    }

    override fun release() {
        calls += Call("release")
    }

    override fun getPlayer(): Player = activePlayerState.value
}
