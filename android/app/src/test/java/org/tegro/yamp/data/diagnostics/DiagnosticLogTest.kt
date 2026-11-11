package org.tegro.yamp.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Secrets never reach the diagnostic log file, whatever a caller puts in a line. */
class DiagnosticLogTest {
    @Test
    fun tokensSignedStreamUrlsAndUidsAreScrubbed() {
        val line = "resolve failed OAuth y0_AgAAAABxyz-token for https://s1.storage.yandex.net/get-mp3/abc/1.mp3?track-id=42&sign=deadbeef&ts=12345&__uid=98765#encraw_key=0a1b2c"
        val out = DiagnosticLog.scrub(line)
        assertFalse(out.contains("y0_AgAAAABxyz"))
        assertFalse(out.contains("deadbeef"))
        assertFalse(out.contains("12345"))
        assertFalse(out.contains("98765"))
        assertFalse(out.contains("0a1b2c"))
        assertEquals("the track id and the host stay", true, out.contains("track-id=42") && out.contains("s1.storage.yandex.net"))
    }

    @Test
    fun plainLinesPassUnchanged() {
        assertEquals("PAUSE: audio focus lost «Echoes»", DiagnosticLog.scrub("PAUSE: audio focus lost «Echoes»"))
    }
}
