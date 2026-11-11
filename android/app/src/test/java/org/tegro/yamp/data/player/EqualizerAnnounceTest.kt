package org.tegro.yamp.data.player

import android.app.Application
import android.media.audiofx.AudioEffect
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Equalizer apps hear about the session when the sound starts and after a real pause, never per track: each
 * announcement makes them re-attach their effects, which cut the sound at almost every track start in 1.13.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class EqualizerAnnounceTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private var clock = 100_000L
    private val equalizer = SystemEqualizer(app, audioSessionId = 77, now = { clock })

    private fun sent(action: String) = shadowOf(app).broadcastIntents.count {
        it.action == action && it.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0) == 77
    }

    private val opened get() = sent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)

    @Test
    fun announcedWhenTheSoundStartsAndAfterARealPauseOnly() = runTest(UnconfinedTestDispatcher()) {
        val playing = MutableStateFlow(false)
        val job = equalizer.keepAnnounced(backgroundScope, playing)

        assertEquals("nothing while paused", 0, opened)
        playing.value = true
        assertEquals("the sound starts", 1, opened)
        // The blink between two tracks (and the players swapping in a crossfade)
        clock += 30_000
        playing.value = false
        clock += 200
        playing.value = true
        assertEquals("a new track is not announced", 1, opened)
        // A real pause: a call, the driver pressing pause
        clock += 10_000
        playing.value = false
        clock += SystemEqualizer.RESUME_GAP_MS
        playing.value = true
        assertEquals("the sound back after a pause", 2, opened)
        job.cancel()
    }

    @Test
    fun theFocusComingBackAndTheSoundResumingAnnounceOnce() {
        equalizer.announce()
        clock += 300
        equalizer.announce()
        assertEquals(1, opened)
        clock += 2_000
        equalizer.announce()
        assertEquals(2, opened)
    }

    @Test
    fun theMomentsByThemselves() {
        val m = SystemEqualizer.AnnounceMoments()
        assertFalse("paused at start", m.onPlaying(false, 0))
        assertTrue("first sound", m.onPlaying(true, 1_000))
        assertFalse(m.onPlaying(false, 2_000))
        assertFalse("4.9 s is a blink", m.onPlaying(true, 6_900))
        assertFalse(m.onPlaying(false, 7_000))
        assertTrue("5 s is a pause", m.onPlaying(true, 12_000))
    }

    @Test
    fun announcingByHandAndClosing() {
        equalizer.announce()
        assertEquals(1, opened)
        equalizer.close()
        assertEquals(1, sent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION))
    }

    /** "Separate audio sessions" (D45): the second player's session is announced and closed along with the first. */
    @Test
    fun theSecondSessionIsAnnouncedAndClosedWhileInUse() {
        val second = equalizer.secondAudioSessionId
        org.junit.Assume.assumeTrue("the test device gives out session ids", second > 0)
        fun openedSecond() = shadowOf(app).broadcastIntents.count {
            it.action == AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION && it.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0) == second
        }
        equalizer.announce()
        assertEquals("only the shared session by default", 0, openedSecond())
        equalizer.useSecondSession(true)
        assertEquals("switching on announces it", 1, openedSecond())
        clock += 2_000
        equalizer.announce()
        assertEquals("both sessions from then on", 2, openedSecond())
        assertEquals(2, opened)
        equalizer.useSecondSession(false)
        assertEquals(1, shadowOf(app).broadcastIntents.count {
            it.action == AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION && it.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0) == second
        })
        clock += 2_000
        equalizer.announce()
        assertEquals("switched off: not announced any more", 2, openedSecond())
    }

    @Test
    fun withoutASessionNothingIsSent() {
        val none = SystemEqualizer(app, audioSessionId = 0)
        none.announce()
        none.close()
        assertEquals(0, shadowOf(app).broadcastIntents.size)
    }
}
