package org.tegro.yamp.data.player

import android.media.AudioDeviceInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRouterWatchTest {

    @Test
    fun theRouterCountsOnlyWhenInstalledAndAUsbOutputIsThere() {
        val speakers = listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        assertFalse("not installed", AudioRouterWatch.isActive(false, speakers + AudioDeviceInfo.TYPE_USB_DEVICE))
        assertFalse("no USB output", AudioRouterWatch.isActive(true, speakers))
        assertTrue("a USB amplifier", AudioRouterWatch.isActive(true, speakers + AudioDeviceInfo.TYPE_USB_DEVICE))
        assertTrue("a USB headset", AudioRouterWatch.isActive(true, listOf(AudioDeviceInfo.TYPE_USB_HEADSET)))
    }
}
