package org.tegro.yamp.data.player

import android.app.Application
import android.media.audiofx.AudioEffect
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * One session, one OPEN / CLOSE pair per playback-service lifetime (D70): equalizer apps such as Poweramp
 * Equalizer briefly take the audio focus whenever a session they equalize closes or is announced again, and a
 * head unit turns that into a permanent focus loss for the player.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class EqualizerAnnounceTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val equalizer = SystemEqualizer(app, audioSessionId = 77)

    private fun sent(action: String) = shadowOf(app).broadcastIntents.count {
        it.action == action && it.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0) == 77
    }

    private val opened get() = sent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
    private val closed get() = sent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)

    @Test
    fun openedOnceWhenTheSoundFirstStartsNeverPerTrackOrPause() = runTest(UnconfinedTestDispatcher()) {
        val playing = MutableStateFlow(false)
        val job = equalizer.openWhenTheSoundStarts(backgroundScope, playing)

        assertEquals("nothing while paused", 0, opened)
        playing.value = true
        assertEquals("the sound starts", 1, opened)
        // A track change, a crossfade swapping players, a long pause, a call: the session never closed
        repeat(5) {
            playing.value = false
            playing.value = true
        }
        assertEquals("never announced again", 1, opened)
        assertEquals("never closed while the service lives", 0, closed)
        job.cancel()
    }

    @Test
    fun theOpenGoesOutWithThePackageAndMusicContent() {
        equalizer.openOnce()
        val open = shadowOf(app).broadcastIntents.single { it.action == AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION }
        assertEquals(app.packageName, open.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME))
        assertEquals(AudioEffect.CONTENT_TYPE_MUSIC, open.getIntExtra(AudioEffect.EXTRA_CONTENT_TYPE, -1))
    }

    @Test
    fun closedOnlyOnceAndOnlyAfterAnOpen() {
        equalizer.close()
        assertEquals("nothing to close before an open", 0, closed)
        equalizer.openOnce()
        equalizer.openOnce()
        assertEquals(1, opened)
        equalizer.close()
        equalizer.close()
        assertEquals(1, closed)
        // The next service lifetime announces the session again
        equalizer.openOnce()
        assertEquals(2, opened)
    }

    @Test
    fun switchedOffSendsNeitherAndCanStillOpenLater() {
        equalizer.announcing = false
        equalizer.openOnce()
        equalizer.close()
        assertEquals(0, opened)
        assertEquals(0, closed)
        equalizer.announcing = true
        equalizer.openOnce()
        assertEquals(1, opened)
    }

    @Test
    fun withoutASessionNothingIsSent() {
        val none = SystemEqualizer(app, audioSessionId = 0)
        none.openOnce()
        none.close()
        assertEquals(0, shadowOf(app).broadcastIntents.size)
    }
}
