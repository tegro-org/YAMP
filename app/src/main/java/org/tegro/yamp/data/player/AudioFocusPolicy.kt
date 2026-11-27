package org.tegro.yamp.data.player

import android.media.AudioManager

/** What the service does with a transient audio-focus loss (another app wants the sound for a while). */
object AudioFocusPolicy {

    enum class TransientLoss {
        /** Keep playing, quieter: a navigator or a radar detector speaking. */
        DUCK,
        /** Pause and resume afterwards: a call, or the user wants the old behaviour. */
        PAUSE
    }

    /**
     * With "Keep playing under announcements" on, a transient loss only ducks — unless the phone rings or a
     * call is on ([audioMode] from [AudioManager.getMode]), which always pauses. Other players keep playing under
     * a navigator's prompt even when it asks for the sound to itself; pausing for every prompt was heard as the
     * music stopping in the car (2026-11-14).
     */
    fun onTransientLoss(keepPlayingUnderAnnouncements: Boolean, audioMode: Int): TransientLoss =
        if (keepPlayingUnderAnnouncements && !isCall(audioMode)) TransientLoss.DUCK else TransientLoss.PAUSE

    /** What happens a moment after a permanent focus loss (D66). */
    enum class AfterLoss {
        /** Nothing else plays: take the focus back and play on. */
        RESUME,
        /** The setting "Resume when nothing else plays" is off. */
        STAY_DISABLED,
        /** YAMP was not playing at the loss: it never starts by itself. */
        STAY_WAS_PAUSED,
        /** The user paused, played or stopped since the loss: their word stands. */
        STAY_USER_ACTED,
        /** A call or a ringing phone. */
        STAY_CALL,
        /** Another app plays music: it took the sound for real. */
        STAY_OTHER_PLAYS,
        /** Resumed [MAX_RESUMES_PER_MINUTE] times within a minute already: something keeps taking it. */
        STAY_TOO_OFTEN
    }

    const val MAX_RESUMES_PER_MINUTE = 3

    /**
     * A permanent loss is not always another player: on the head unit an equalizer app asks for the focus for a
     * moment when a session closes and the unit turns that into a full loss for us (2026-11-16). So after a short
     * wait YAMP plays on — but only if it was playing at the loss ([wasPlayingAtLoss]), the user has not acted
     * since, no call is on and no other app plays music. Pure; unit-tested.
     */
    fun afterLoss(
        enabled: Boolean,
        wasPlayingAtLoss: Boolean,
        userActedSince: Boolean,
        audioMode: Int,
        otherMusicActive: Boolean,
        resumesInLastMinute: Int
    ): AfterLoss = when {
        !enabled -> AfterLoss.STAY_DISABLED
        !wasPlayingAtLoss -> AfterLoss.STAY_WAS_PAUSED
        userActedSince -> AfterLoss.STAY_USER_ACTED
        isCall(audioMode) -> AfterLoss.STAY_CALL
        otherMusicActive -> AfterLoss.STAY_OTHER_PLAYS
        resumesInLastMinute >= MAX_RESUMES_PER_MINUTE -> AfterLoss.STAY_TOO_OFTEN
        else -> AfterLoss.RESUME
    }

    fun isCall(audioMode: Int): Boolean =
        audioMode == AudioManager.MODE_RINGTONE || audioMode == AudioManager.MODE_IN_CALL || audioMode == AudioManager.MODE_IN_COMMUNICATION
}
