package org.tegro.yamp.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.tegro.yamp.data.diagnostics.StallDetector.Event

class StallDetectorTest {
    private val detector = StallDetector()

    @Test
    fun nothingBelowTheThreshold() {
        (0L..3L).forEach { s -> assertNull(detector.update(now = s * 1000 + 999, lastAnswer = 0)) }
    }

    @Test
    fun aShortStallThatAnswersIsNotReported() {
        assertNull(detector.update(now = 3_000, lastAnswer = 0))
        assertNull(detector.update(now = 4_000, lastAnswer = 3_500))
    }

    @Test
    fun blockedOnceAtFourSecondsThenEveryFiveAndRecovery() {
        assertEquals(Event.Blocked(4), detector.update(now = 4_000, lastAnswer = 0))
        listOf(5_000L, 6_000L, 7_000L, 8_000L, 8_999L).forEach { assertNull(detector.update(now = it, lastAnswer = 0)) }
        assertEquals(Event.StillBlocked(9), detector.update(now = 9_000, lastAnswer = 0))
        assertNull(detector.update(now = 13_000, lastAnswer = 0))
        assertEquals(Event.StillBlocked(14), detector.update(now = 14_000, lastAnswer = 0))
        assertEquals(Event.Recovered(15), detector.update(now = 15_200, lastAnswer = 15_000))
        assertNull(detector.update(now = 16_000, lastAnswer = 15_000))
    }

    @Test
    fun aNewStallAfterRecoveryIsReportedAgain() {
        detector.update(now = 4_000, lastAnswer = 0)
        assertEquals(Event.Recovered(5), detector.update(now = 5_100, lastAnswer = 5_000))
        assertEquals(Event.Blocked(4), detector.update(now = 9_000, lastAnswer = 5_000))
    }

    @Test
    fun theStackIsTrimmedToTheTopFramesOnOneLine() {
        val frames = Array(30) { StackTraceElement("a.B", "m$it", "B.kt", it) }
        val line = MainThreadWatchdog.formatStack(frames)
        assertEquals(false, line.contains('\n'))
        assertEquals(true, line.startsWith("a.B.m0(B.kt:0) ← a.B.m1(B.kt:1)"))
        assertEquals(true, line.endsWith("a.B.m24(B.kt:24) ← … (+5 more)"))
    }
}
