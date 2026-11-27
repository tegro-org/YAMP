package org.tegro.yamp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tegro.yamp.data.updater.GitHubUpdateManager
import org.tegro.yamp.data.updater.UpdateSchedule

/** The update check without Android: the release page's tag and when the app looks again. */
class UpdateCheckTest {

    @Test
    fun theTagComesFromTheReleasePageAddress() {
        assertEquals(
            "v1.16.0",
            GitHubUpdateManager.tagFromLocation("https://github.com/tegro-org/YAMP/releases/tag/v1.16.0")
        )
        assertEquals(
            "v1.16.0",
            GitHubUpdateManager.tagFromLocation("https://github.com/tegro-org/YAMP/releases/tag/v1.16.0?expanded=true")
        )
        assertEquals(
            "v1.16.0",
            GitHubUpdateManager.tagFromLocation("https://github.com/tegro-org/YAMP/releases/tag/v1.16.0#notes")
        )
    }

    @Test
    fun notAReleasePageHasNoTag() {
        assertNull(GitHubUpdateManager.tagFromLocation("https://github.com/tegro-org/YAMP/releases"))
        assertNull(GitHubUpdateManager.tagFromLocation("https://github.com/tegro-org/YAMP/releases/tag/"))
        assertNull(GitHubUpdateManager.tagFromLocation("https://github.com/tegro-org/YAMP/releases/tag/v1.16.0/x"))
        assertNull(GitHubUpdateManager.tagFromLocation("https://github.com/login"))
        assertNull(GitHubUpdateManager.tagFromLocation(null))
    }

    @Test
    fun neverCheckedIsDue() {
        assertTrue(UpdateSchedule.due(now = 1_000_000L, lastAt = 0L, lastOk = true))
        assertTrue(UpdateSchedule.due(now = 1_000_000L, lastAt = 0L, lastOk = false))
    }

    @Test
    fun afterAGoodCheckDueInSixHours() {
        val lastAt = 1_000_000L
        val fiveHours = lastAt + 5 * 60 * 60_000L
        val sixHours = lastAt + UpdateSchedule.AFTER_OK_MS
        assertFalse(UpdateSchedule.due(now = fiveHours, lastAt = lastAt, lastOk = true))
        assertTrue(UpdateSchedule.due(now = sixHours, lastAt = lastAt, lastOk = true))
    }

    @Test
    fun afterAFailedCheckDueInFifteenMinutes() {
        val lastAt = 1_000_000L
        val tenMinutes = lastAt + 10 * 60_000L
        val fifteenMinutes = lastAt + UpdateSchedule.AFTER_FAILED_MS
        assertFalse(UpdateSchedule.due(now = tenMinutes, lastAt = lastAt, lastOk = false))
        assertTrue(UpdateSchedule.due(now = fifteenMinutes, lastAt = lastAt, lastOk = false))
    }

    @Test
    fun clockRolledBackIsDueAtOnce() {
        val lastAt = 1_000_000L
        assertTrue(UpdateSchedule.due(now = lastAt - 1L, lastAt = lastAt, lastOk = true))
        assertTrue(UpdateSchedule.due(now = lastAt - 1L, lastAt = lastAt, lastOk = false))
    }
}
