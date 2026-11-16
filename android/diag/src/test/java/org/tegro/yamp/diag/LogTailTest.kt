package org.tegro.yamp.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogTailTest {
    private fun line(n: Int, msg: String = "message $n") = "11-16 13:17:%02d.000  1234  1240 I Tag: $msg".format(n % 60)

    @Test
    fun overTheCapTheNewestLinesStayAndTheOldOnesAreCounted() {
        val tail = LogTail(capBytes = 200)
        (1..20).forEach { tail.accept(line(it)) }
        val out = tail.result().lines()
        assertTrue(out.first(), out.first().startsWith("[YAMP Diag:") && out.first().contains("older lines left out"))
        assertTrue("the last line is kept", out.any { it.endsWith("message 20") })
        assertFalse("the first line is gone", out.any { it.endsWith("message 1") })
    }

    @Test
    fun spamIsDroppedAndCounted() {
        val tail = LogTail(capBytes = 10_000, dropContaining = listOf("CarPlayService not found"))
        tail.accept(line(1))
        repeat(3) { tail.accept(line(2, "CarPlayService not found")) }
        tail.accept(line(3))
        val out = tail.result()
        assertFalse(out.contains("Tag: CarPlayService not found"))
        assertTrue(out.contains("[YAMP Diag: 3 lines containing \"CarPlayService not found\" left out]"))
        assertTrue(out.contains("message 1") && out.contains("message 3"))
    }

    @Test
    fun aRunOfTheSameMessageIsFoldedWithACount() {
        val tail = LogTail(capBytes = 10_000)
        tail.accept(line(1, "same"))
        tail.accept(line(2, "same"))
        tail.accept(line(3, "same"))
        tail.accept(line(4, "other"))
        val lines = tail.result().trimEnd().lines()
        assertEquals(3, lines.size)
        assertTrue(lines[0].endsWith("Tag: same"))
        assertEquals("    … the same message repeated 2 more times", lines[1])
        assertTrue(lines[2].endsWith("Tag: other"))
    }

    @Test
    fun theExitMarkerSurvivesAsTheLastLine() {
        val tail = LogTail(capBytes = 100)
        (1..10).forEach { tail.accept(line(it)) }
        tail.accept("${AdbSession.EXIT_MARKER}0")
        val parsed = AdbSession.parse(tail.result().toByteArray(), cutByStream = false, cap = 4096)
        assertEquals(0, parsed.exitCode)
    }
}
