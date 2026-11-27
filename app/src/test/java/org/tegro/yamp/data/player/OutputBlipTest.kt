package org.tegro.yamp.data.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.14.7: a car's Bluetooth drops for a second and the music stays paused for the rest of the drive. The pause the
 * player makes when its output goes away is kept as such, and an output back within the blip window plays on.
 */
class OutputBlipTest {

    private var now = 1_000_000L
    private val journal = PlaybackJournal(now = { now })

    private fun pauseOutput() {
        journal.onPaused(PlaybackJournal.Kind.OUTPUT_GONE, title = "Track 1")
    }

    @Test
    fun anOutputBackWithinTheWindowIsABlip() {
        pauseOutput()
        assertTrue("at once", journal.isOutputBlip())
        now += PlaybackJournal.OUTPUT_BLIP_MS
        assertTrue("at the end of the window", journal.isOutputBlip())
    }

    @Test
    fun tooLateIsNotABlip() {
        pauseOutput()
        now += PlaybackJournal.OUTPUT_BLIP_MS + 1
        assertFalse(journal.isOutputBlip())
    }

    @Test
    fun aPauseTheListenerAskedForIsNeverABlip() {
        pauseOutput()
        journal.onPaused(PlaybackJournal.Kind.PAUSE, detail = PlaybackController.PAUSE_BY_USER)
        assertFalse("the listener paused, the music must stay paused", journal.isOutputBlip())
    }

    @Test
    fun withoutAPauseThereIsNoBlip() {
        assertFalse(journal.isOutputBlip())
    }

    @Test
    fun thePauseItselfKnowsTheWindow() {
        val pause = PlaybackJournal.Pause(PlaybackJournal.Kind.OUTPUT_GONE, atMs = 500L)
        assertTrue(pause.isOutputBlip(500L))
        assertTrue(pause.isOutputBlip(500L + PlaybackJournal.OUTPUT_BLIP_MS))
        assertFalse(pause.isOutputBlip(501L + PlaybackJournal.OUTPUT_BLIP_MS))
        assertFalse("a clock that went back is not a blip", pause.isOutputBlip(499L))
        assertFalse(PlaybackJournal.Pause(PlaybackJournal.Kind.PAUSE, 500L).isOutputBlip(500L))
    }

    /** Only the two a listener asked for clear a pending resume on the sound's return; a focus loss or a stall does not. */
    @Test
    fun aListenerPauseIsTheAppOrTheDevice() {
        assertTrue(PlaybackController.isListenerPause(PlaybackController.PAUSE_BY_USER))
        assertTrue(PlaybackController.isListenerPause(PlaybackController.PAUSE_BY_DEVICE))
        assertFalse("a focus loss is not the listener", PlaybackController.isListenerPause("audio focus lost"))
        assertFalse("no data is not the listener", PlaybackController.isListenerPause("no data 20 s"))
        assertFalse(PlaybackController.isListenerPause("ERROR_CODE_TIMEOUT, too slow"))
    }

    /** A pause from outside says who sent it (the media session's controller package); it is still the listener's. */
    @Test
    fun aDevicePauseNamedByItsSenderIsStillTheListeners() {
        assertEquals(PlaybackController.PAUSE_BY_DEVICE, PlaybackController.devicePauseReason(null))
        assertEquals(PlaybackController.PAUSE_BY_DEVICE, PlaybackController.devicePauseReason(" "))
        val car = PlaybackController.devicePauseReason("com.android.bluetooth")
        assertEquals("media session (notification, headset, car) · com.android.bluetooth", car)
        assertTrue(PlaybackController.isDevicePause(car))
        assertTrue(PlaybackController.isListenerPause(car))
        assertFalse(PlaybackController.isDevicePause(PlaybackController.PAUSE_BY_USER))
        assertFalse(PlaybackController.isDevicePause("audio focus lost · call"))
    }
}
