package org.tegro.yamp.data.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.14.12, the report: 599 of 608 liked tracks downloaded and the last 9 sat «Ожидание сети» forever on a good
 * connection — their tracks were gone from the catalogue and failed every run. Only a missing network is retried.
 */
class DownloadOutcomeTest {

    @Test
    fun aTrackThatFailedOnAConnectionIsRetriedOnceTheNetworkIsBack() {
        assertTrue(DownloadOutcome.shouldRetry(failed = 1, online = false))
        assertTrue(DownloadOutcome.shouldRetry(failed = 608, online = false))
    }

    @Test
    fun withTheNetworkOnAFailureIsFinal() {
        assertFalse(DownloadOutcome.shouldRetry(failed = 9, online = true))
    }

    @Test
    fun nothingFailedMeansNothingToRetry() {
        assertFalse(DownloadOutcome.shouldRetry(failed = 0, online = true))
        assertFalse(DownloadOutcome.shouldRetry(failed = 0, online = false))
    }
}
