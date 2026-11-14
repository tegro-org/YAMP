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
}
