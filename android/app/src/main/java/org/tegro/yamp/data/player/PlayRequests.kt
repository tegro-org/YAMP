package org.tegro.yamp.data.player

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Who started the music. The user tapping a track, a playlist or an album may open the full player;
 * the app moving on by itself, a start on launch or the car never do.
 */
@Singleton
class PlayRequests @Inject constructor() {
    private val _userStarted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val userStarted: SharedFlow<Unit> = _userStarted.asSharedFlow()

    fun userStarted() {
        _userStarted.tryEmit(Unit)
    }

    private val startHandled = AtomicBoolean(false)
    private val played = AtomicBoolean(false)

    /** Music has sounded in this process: from now on a play after a pause is a resume, not a start. */
    fun markPlayed() = played.set(true)

    val hasPlayed: Boolean get() = played.get()

    /** True only the first time in this process: the music on launch plays once, not on every return to the app. */
    fun claimLaunchStart(): Boolean = startHandled.compareAndSet(false, true)
}

/**
 * What the big knob on the first tab does, and whether that counts as switching music on. The tab is the
 * active queue's page (D49): the wave's, or a queue of the user's own.
 */
object HomeKnob {
    enum class Action { START_WAVE, START_QUEUE, TOGGLE }
    data class Press(val action: Action, val opensPlayer: Boolean)

    /**
     * [wavePage]: the tab shows the wave (the WAVE queue is active, or none is). [pagePlays]: what the page shows
     * is what the player holds — the wave itself, or the queue's track rather than a live station or nothing.
     */
    fun press(wavePage: Boolean, pagePlays: Boolean, isPlaying: Boolean, hasPlayed: Boolean): Press = when {
        // Another source or nothing: the knob starts what the page shows
        !pagePlays -> Press(if (wavePage) Action.START_WAVE else Action.START_QUEUE, opensPlayer = true)
        // Playing: a pause never opens anything
        isPlaying -> Press(Action.TOGGLE, opensPlayer = false)
        // Paused: the session restored at launch starts; a play after the user's own pause only resumes
        else -> Press(Action.TOGGLE, opensPlayer = !hasPlayed)
    }
}
