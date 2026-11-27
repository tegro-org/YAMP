package org.tegro.yamp.data.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "Resume when nothing else plays" (D66). After a permanent focus loss the service pauses as Android expects; a
 * moment later this looks whether anything actually took the sound and, if not, plays on. On the head unit an
 * equalizer app takes the focus for a moment whenever a session closes, and the unit turns that into a full loss
 * for us — twelve auto-pauses in one day (2026-11-16) with nothing else playing.
 *
 * Hard guard: only a loss that came while YAMP was playing (sounding, or loading to play) can lead to a resume,
 * and only if the user did nothing since — a paused, idle or just-opened app never starts by itself.
 *
 * The decision itself is [AudioFocusPolicy.afterLoss]; the inputs are read when the wait is over.
 */
class FocusResumer(
    private val scope: CoroutineScope,
    private val nowMs: () -> Long,
    private val audioMode: () -> Int,
    /** Another app plays music now ([android.media.AudioManager.isMusicActive], ours being paused). */
    private val otherMusicActive: () -> Boolean,
    /** Changes with every play, pause or stop: the user acted (see [PlaybackController.userIntentEpoch]). */
    private val intentEpoch: () -> Long,
    /** YAMP plays (or loads to play) again — e.g. the user picked a track during the wait. */
    private val playingNow: () -> Boolean,
    private val resume: () -> Unit,
    private val log: (String) -> Unit
) {
    private var waitJob: Job? = null
    private val recentResumes = ArrayDeque<Long>()

    /**
     * A permanent loss has just paused playback. [wasPlaying]: YAMP was playing at the moment of the loss;
     * [epochAfterPause]: [intentEpoch] right after that pause.
     */
    fun onLoss(enabled: Boolean, wasPlaying: Boolean, epochAfterPause: Long) {
        cancel()
        when {
            !enabled -> log("lost")
            !wasPlaying -> log("lost, not resuming: was paused")
            else -> {
                log("lost, will resume when nothing else plays")
                waitJob = scope.launch {
                    delay(RESUME_CHECK_DELAY_MS)
                    decide(epochAfterPause)
                }
            }
        }
    }

    /** The service goes away, or a new focus change replaces the wait. */
    fun cancel() {
        waitJob?.cancel()
        waitJob = null
    }

    private fun decide(epochAfterPause: Long) {
        val now = nowMs()
        while (recentResumes.isNotEmpty() && now - recentResumes.first() > RATE_WINDOW_MS) recentResumes.removeFirst()
        val decision = AudioFocusPolicy.afterLoss(
            enabled = true,
            wasPlayingAtLoss = true,
            userActedSince = intentEpoch() != epochAfterPause || playingNow(),
            audioMode = audioMode(),
            otherMusicActive = otherMusicActive(),
            resumesInLastMinute = recentResumes.size
        )
        when (decision) {
            AudioFocusPolicy.AfterLoss.RESUME -> {
                recentResumes.addLast(now)
                log("resumed, nothing else playing")
                resume()
            }
            AudioFocusPolicy.AfterLoss.STAY_USER_ACTED -> log("not resuming: played or paused since")
            AudioFocusPolicy.AfterLoss.STAY_CALL -> log("not resuming: call")
            AudioFocusPolicy.AfterLoss.STAY_OTHER_PLAYS -> log("not resuming: other music plays")
            AudioFocusPolicy.AfterLoss.STAY_TOO_OFTEN -> log("not resuming: resumed ${AudioFocusPolicy.MAX_RESUMES_PER_MINUTE} times in a minute")
            AudioFocusPolicy.AfterLoss.STAY_DISABLED, AudioFocusPolicy.AfterLoss.STAY_WAS_PAUSED -> Unit // decided at the loss
        }
    }

    companion object {
        /** Long enough for an app that really takes the sound to start playing. */
        const val RESUME_CHECK_DELAY_MS = 1_500L
        private const val RATE_WINDOW_MS = 60_000L
    }
}
