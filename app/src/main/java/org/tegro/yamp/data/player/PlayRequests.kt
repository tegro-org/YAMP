package org.tegro.yamp.data.player

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Who started the music. The user tapping a track, a playlist or an album may open the full player;
 * the app moving on by itself, a start on launch or the car never do.
 */
@Singleton
class PlayRequests @Inject constructor() {
    /** One start of the music by the user: its number and when ([SystemClock.elapsedRealtime]). */
    data class Start(val id: Long, val atMs: Long)

    // Kept, not fired: the screen that opens the player may not listen yet at that moment (the music on launch
    // starts as the screen appears) — a fired event was lost then, and the player stayed folded only now and then
    private val _starts = MutableStateFlow<Start?>(null)
    val starts: StateFlow<Start?> = _starts.asStateFlow()
    private val counter = AtomicLong()

    fun userStarted() {
        _starts.value = Start(counter.incrementAndGet(), SystemClock.elapsedRealtime())
    }

    private val claimedLaunch = AtomicLong(NO_LAUNCH)
    private val played = AtomicBoolean(false)

    /** Music has sounded in this process: from now on a play after a pause is a resume, not a start. */
    fun markPlayed() = played.set(true)

    val hasPlayed: Boolean get() = played.get()

    /**
     * True only the first time for this [launch] (the app opened afresh, see MainActivity): the music on launch plays
     * once per opening, not on every return to a screen still open. Once per process before: opened again after Back
     * while the process lived on (a head unit after its sleep, the music's service), the app neither started the
     * music nor opened the player.
     */
    fun claimLaunchStart(launch: Long): Boolean = claimedLaunch.getAndSet(launch) != launch

    companion object {
        private const val NO_LAUNCH = Long.MIN_VALUE

        /** A start older than this is not the one the screen should open the player for (a previous opening's). */
        const val FRESH_MS = 10_000L

        /** Whether [start] still asks for the player at [nowMs]. Pure; unit-tested. */
        fun isFresh(start: Start, nowMs: Long): Boolean = nowMs - start.atMs in 0..FRESH_MS
    }
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
