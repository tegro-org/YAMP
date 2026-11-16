package org.tegro.yamp.data.player

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioFocusPolicyTest {

    @Test
    fun anAnnouncementDucksWhenTheSettingIsOn() {
        assertEquals(AudioFocusPolicy.TransientLoss.DUCK, AudioFocusPolicy.onTransientLoss(true, AudioManager.MODE_NORMAL))
    }

    @Test
    fun aCallAlwaysPauses() {
        for (mode in listOf(AudioManager.MODE_RINGTONE, AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION)) {
            assertEquals("mode $mode", AudioFocusPolicy.TransientLoss.PAUSE, AudioFocusPolicy.onTransientLoss(true, mode))
        }
    }

    @Test
    fun withTheSettingOffEveryTransientLossPauses() {
        assertEquals(AudioFocusPolicy.TransientLoss.PAUSE, AudioFocusPolicy.onTransientLoss(false, AudioManager.MODE_NORMAL))
    }

    private fun afterLoss(
        enabled: Boolean = true,
        wasPlaying: Boolean = true,
        userActed: Boolean = false,
        mode: Int = AudioManager.MODE_NORMAL,
        otherPlays: Boolean = false,
        recent: Int = 0
    ) = AudioFocusPolicy.afterLoss(enabled, wasPlaying, userActed, mode, otherPlays, recent)

    @Test
    fun aLossWhileNothingElsePlaysResumes() {
        assertEquals(AudioFocusPolicy.AfterLoss.RESUME, afterLoss())
    }

    @Test
    fun aPausedAppNeverStartsByItself() {
        assertEquals(AudioFocusPolicy.AfterLoss.STAY_WAS_PAUSED, afterLoss(wasPlaying = false))
    }

    @Test
    fun theUsersWordStands() {
        assertEquals(AudioFocusPolicy.AfterLoss.STAY_USER_ACTED, afterLoss(userActed = true))
    }

    @Test
    fun anotherPlayerOrACallKeepsItPaused() {
        assertEquals(AudioFocusPolicy.AfterLoss.STAY_OTHER_PLAYS, afterLoss(otherPlays = true))
        assertEquals(AudioFocusPolicy.AfterLoss.STAY_CALL, afterLoss(mode = AudioManager.MODE_IN_CALL))
    }

    @Test
    fun atMostThreeResumesAMinuteAndNoneWithTheSettingOff() {
        assertEquals(AudioFocusPolicy.AfterLoss.STAY_TOO_OFTEN, afterLoss(recent = AudioFocusPolicy.MAX_RESUMES_PER_MINUTE))
        assertEquals(AudioFocusPolicy.AfterLoss.RESUME, afterLoss(recent = AudioFocusPolicy.MAX_RESUMES_PER_MINUTE - 1))
        assertEquals(AudioFocusPolicy.AfterLoss.STAY_DISABLED, afterLoss(enabled = false))
    }
}
