package org.tegro.yamp.data.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a broken download gives up on its link (b83 ride: a dead FLAC connection retried for a minute). */
class PlaybackBufferingTest {

    @Test
    fun aStartThatBroughtNoBytesAsksForAFreshLinkAfterOneTry() {
        assertTrue(PlaybackBuffering.giveUpOnLink(openedAt = 0L, bytesLoaded = 0L, errorCount = 1))
    }

    @Test
    fun aStartThatIsLoadingKeepsRetrying() {
        assertFalse(PlaybackBuffering.giveUpOnLink(openedAt = 0L, bytesLoaded = 64_000L, errorCount = 3))
    }

    @Test
    fun midTrackTheDownloadStaysPatient() {
        // The download resumes from where it stopped: bytes were loaded before
        assertFalse(PlaybackBuffering.giveUpOnLink(openedAt = 2_000_000L, bytesLoaded = 0L, errorCount = 5))
    }

    @Test
    fun theRetryPausesGrowAndCap() {
        assertTrue(PlaybackBuffering.retryDelayMs(1) == 1_000L)
        assertTrue(PlaybackBuffering.retryDelayMs(9) == 5_000L)
    }
}
