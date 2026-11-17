package org.tegro.yamp.data.provider.jellyfin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tegro.yamp.data.provider.jellyfin.PlayReportPlanner.Report
import org.tegro.yamp.data.provider.jellyfin.PlayReportPlanner.Snapshot
import org.tegro.yamp.domain.account.AccountId

class PlayReportPlannerTest {

    private val account = AccountId.jellyfin("srv", "user")
    private var sessions = 0
    private val planner = PlayReportPlanner { "s${++sessions}" }

    private fun at(t: Long, item: String? = "a", playing: Boolean = true, pos: Long = t) =
        planner.next(Snapshot(item, if (item != null) account else null, playing, pos, t))

    @Test
    fun aTrackIsStartedOnceItSoundsThenReportedEveryTenSeconds() {
        assertTrue(at(0, playing = false, pos = 0).isEmpty()) // loading: nothing yet
        assertEquals(listOf(Report.Started(account, "a", 1_000, "s1")), at(1_000))
        assertTrue((2_000L..10_000L step 1_000).all { at(it).isEmpty() })
        assertEquals(listOf(Report.Progress(account, "a", 11_000, "s1", paused = false)), at(11_000))
    }

    @Test
    fun pauseResumeAndSeekAreReported() {
        at(0); at(1_000)
        assertEquals(Report.Progress(account, "a", 2_000, "s1", paused = true, change = PlayReportPlanner.Change.PAUSED), at(2_000, playing = false).single())
        assertTrue(at(5_000, playing = false, pos = 2_000).isEmpty())
        assertEquals(Report.Progress(account, "a", 2_000, "s1", paused = false, change = PlayReportPlanner.Change.RESUMED), at(6_000, pos = 2_000).single())
        assertEquals(Report.Progress(account, "a", 60_000, "s1", paused = false, change = PlayReportPlanner.Change.SEEK), at(7_000, pos = 60_000).single())
    }

    @Test
    fun aTrackChangeStopsTheOldOneAndStartsTheNewWithItsOwnSession() {
        at(0); at(4_000)
        val reports = at(5_000, item = "b", pos = 0)
        assertEquals(Report.Stopped(account, "a", 4_000, "s1"), reports[0])
        assertEquals(Report.Started(account, "b", 0, "s2"), reports[1])
    }

    @Test
    fun leavingJellyfinStopsAndATrackThatNeverSoundedIsNeverReported() {
        at(0); at(3_000)
        assertEquals(listOf(Report.Stopped(account, "a", 3_000, "s1")), at(4_000, item = null))
        assertTrue(at(5_000, item = "c", playing = false, pos = 0).isEmpty())
        assertTrue(at(6_000, item = null).isEmpty())
    }
}
