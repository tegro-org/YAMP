package org.tegro.yamp.data.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the previous run of the app ended: Android's reason turned into a stable name, and the journal's KILLED line only
 * when the music was mid-track (the position is saved every 15 s while it plays, so a kill that found it playing comes
 * close behind the save).
 */
class ProcessExitsTest {

    private var now = 1_000_000L

    private fun journal(exit: (Long) -> ProcessExit?) = PlaybackJournal(now = { now }, exitSince = exit)

    @Test
    fun theReasonBecomesAStableName() {
        assertEquals(ProcessExits.LOW_MEMORY, ProcessExits.reasonName(3))
        assertEquals(ProcessExits.CRASH, ProcessExits.reasonName(4))
        assertEquals(ProcessExits.CRASH, ProcessExits.reasonName(5))
        assertEquals(ProcessExits.ANR, ProcessExits.reasonName(6))
        assertEquals(ProcessExits.BATTERY, ProcessExits.reasonName(14))
        assertEquals(ProcessExits.USER, ProcessExits.reasonName(10))
        assertEquals(ProcessExits.UPDATED, ProcessExits.reasonName(16))
        assertEquals(ProcessExits.SYSTEM, ProcessExits.reasonName(13))
        assertEquals("a reason newer than this app is still a name", ProcessExits.UNKNOWN, ProcessExits.reasonName(99))
    }

    @Test
    fun aKillCloseBehindTheSavedPositionIsTold() {
        val journal = journal { since -> ProcessExit(since + 5_000, ProcessExits.LOW_MEMORY) }
        journal.checkProcessExit(savedAtMs = now)
        val killed = journal.state.value.events.single()
        assertEquals(PlaybackJournal.Kind.KILLED, killed.kind)
        assertEquals(ProcessExits.LOW_MEMORY, killed.detail)
        assertEquals("when it happened", now + 5_000, killed.valueMs)
        assertEquals(1, journal.state.value.summary.kills)
    }

    @Test
    fun aKillLongAfterTheMusicStoppedIsNotTold() {
        val journal = journal { since -> ProcessExit(since + PlaybackJournal.KILLED_WITHIN_MS + 1, ProcessExits.LOW_MEMORY) }
        journal.checkProcessExit(savedAtMs = now)
        assertTrue("the app was not playing then", journal.state.value.events.isEmpty())
    }

    @Test
    fun anExitAndroidDoesNotKnowIsNotTold() {
        val journal = journal { null }
        journal.checkProcessExit(savedAtMs = now)
        assertTrue(journal.state.value.events.isEmpty())
    }

    @Test
    fun onlyOncePerRun() {
        var asked = 0
        val journal = journal { since ->
            asked++
            ProcessExit(since, ProcessExits.LOW_MEMORY)
        }
        journal.checkProcessExit(savedAtMs = now)
        journal.checkProcessExit(savedAtMs = now)
        assertEquals(1, asked)
        assertEquals(1, journal.state.value.events.size)
    }
}
