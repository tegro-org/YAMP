package org.tegro.yamp.data.player

import android.app.Application
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** When a broken download gives up on its link (b83 ride: a dead FLAC connection retried for a minute). */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PlaybackBufferingTest {

    private fun retryDelayFor(error: java.io.IOException, position: Long = 1_000_000L, loaded: Long = 500_000L): Long {
        val event = LoadEventInfo(1L, DataSpec.Builder().setUri(Uri.parse("https://jf.example/a")).setPosition(position).build(), 0L)
        val info = LoadErrorHandlingPolicy.LoadErrorInfo(
            LoadEventInfo(event.loadTaskId, event.dataSpec, event.uri, emptyMap(), 0L, 0L, loaded),
            MediaLoadData(C.DATA_TYPE_MEDIA), error, 1
        )
        return PlaybackBuffering.PatientLoadErrors().getRetryDelayMsFor(info)
    }

    /** D91: a growing file's failed download goes straight to the controller — a retry would restart the track at 0. */
    @Test
    fun aProgressiveDownloadFailureIsNeverRetried() {
        val failed = ProgressiveDownloadException("download failed", java.net.SocketTimeoutException(), PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        assertEquals(C.TIME_UNSET, retryDelayFor(failed))
        assertTrue("a plain mid-track break still is", retryDelayFor(java.io.IOException("reset")) > 0L)
    }

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
