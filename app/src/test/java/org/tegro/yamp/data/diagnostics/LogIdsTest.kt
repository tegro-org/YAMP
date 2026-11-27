package org.tegro.yamp.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/phase-4d-local-files.md §10: a local file's id (its path) never reaches a log. */
class LogIdsTest {
    @Test
    fun aLocalIdIsHashedAndAServiceIdStays() {
        val id = "6917-1912/Music/Fleur/2005 - Эйфория/03 - Отречение.flac"
        val logged = logId(id)
        assertTrue(logged, logged.matches(Regex("^local:track:#[0-9a-f]{8}$")))
        assertFalse("no part of the path", logged.contains("Music") || logged.contains("Fleur"))
        assertEquals("the same file, the same mark", logged, logId(id))
        assertEquals("12345:678", logId("12345:678"))
        assertEquals("null", logId(null))
    }
}
