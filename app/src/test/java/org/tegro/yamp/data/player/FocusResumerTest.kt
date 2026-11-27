package org.tegro.yamp.data.player

import android.media.AudioManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * D66: after a permanent focus loss YAMP plays on once nothing else plays — only if it was playing at the loss and
 * the user did nothing since (2026-11-16: an equalizer on the head unit took the focus with nothing playing).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FocusResumerTest {

    private var mode = AudioManager.MODE_NORMAL
    private var otherPlays = false
    private var epoch = 0L
    private var playing = false
    private var resumes = 0
    private val log = mutableListOf<String>()

    private fun TestScope.resumer() = FocusResumer(
        scope = this,
        nowMs = { testScheduler.currentTime },
        audioMode = { mode },
        otherMusicActive = { otherPlays },
        intentEpoch = { epoch },
        playingNow = { playing },
        resume = { resumes++ },
        log = { log += it }
    )

    private fun TestScope.waitOut() {
        advanceTimeBy(FocusResumer.RESUME_CHECK_DELAY_MS + 1)
        runCurrent()
    }

    @Test
    fun aLossWhilePlayingResumesWhenNothingElsePlays() = runTest {
        resumer().onLoss(enabled = true, wasPlaying = true, epochAfterPause = epoch)
        waitOut()
        assertEquals(1, resumes)
        assertEquals("resumed, nothing else playing", log.last())
    }

    @Test
    fun aLossWhilePausedNeverStartsPlayback() = runTest {
        resumer().onLoss(enabled = true, wasPlaying = false, epochAfterPause = epoch)
        waitOut()
        assertEquals(0, resumes)
        assertEquals("lost, not resuming: was paused", log.last())
    }

    @Test
    fun aUserPauseDuringTheWaitCancelsTheResume() = runTest {
        resumer().onLoss(enabled = true, wasPlaying = true, epochAfterPause = epoch)
        epoch++ // the user pressed pause (or play) meanwhile
        waitOut()
        assertEquals(0, resumes)
    }

    @Test
    fun anotherAppPlayingOrACallKeepsItPaused() = runTest {
        val resumer = resumer()
        otherPlays = true
        resumer.onLoss(enabled = true, wasPlaying = true, epochAfterPause = epoch)
        waitOut()
        otherPlays = false
        mode = AudioManager.MODE_IN_CALL
        resumer.onLoss(enabled = true, wasPlaying = true, epochAfterPause = epoch)
        waitOut()
        assertEquals(0, resumes)
        assertEquals(listOf("not resuming: other music plays", "not resuming: call"), log.filter { it.startsWith("not") })
    }

    @Test
    fun withTheSettingOffItOnlyRecordsTheLoss() = runTest {
        resumer().onLoss(enabled = false, wasPlaying = true, epochAfterPause = epoch)
        waitOut()
        assertEquals(0, resumes)
        assertEquals(listOf("lost"), log)
    }

    @Test
    fun atMostThreeResumesAMinute() = runTest {
        val resumer = resumer()
        repeat(4) {
            resumer.onLoss(enabled = true, wasPlaying = true, epochAfterPause = epoch)
            waitOut()
        }
        assertEquals(AudioFocusPolicy.MAX_RESUMES_PER_MINUTE, resumes)
        assertEquals("not resuming: resumed 3 times in a minute", log.last())
    }
}
