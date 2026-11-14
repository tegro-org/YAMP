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

    fun isCall(audioMode: Int): Boolean =
        audioMode == AudioManager.MODE_RINGTONE || audioMode == AudioManager.MODE_IN_CALL || audioMode == AudioManager.MODE_IN_COMMUNICATION
}
