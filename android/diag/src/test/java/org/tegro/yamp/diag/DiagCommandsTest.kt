package org.tegro.yamp.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

class DiagCommandsTest {
    private val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse("2026-11-15 18:30:00")!!

    @Test
    fun theCommandSetIsFixed() {
        assertEquals(
            listOf(
                "audio_flinger.txt", "audio.txt", "media_session.txt", "getprop.txt", "logcat.txt",
                "yamp_package.txt", "yamp_pid.txt", "processes.txt",
                "dropbox/data_app_anr.txt", "dropbox/data_app_crash.txt", "dropbox/data_app_native_crash.txt",
                "dropbox/SYSTEM_TOMBSTONE.txt", "dropbox/system_app_anr.txt",
                "yamp/yamp.log", "yamp/yamp.1.log", "yamp/crashes.txt",
            ),
            DiagCommands.items(now).map { it.file },
        )
        assertEquals("dumpsys media.audio_flinger", DiagCommands.items(now).first().command)
    }

    @Test
    fun everyItemHasATimeoutAndACap() {
        DiagCommands.items(now).forEach {
            assertTrue(it.file, it.timeoutMs in 1..60_000)
            assertTrue(it.file, it.capBytes in 1..DiagItem.DEFAULT_CAP_BYTES)
        }
    }

    @Test
    fun runAsReadsOnlyYampsLogsAndCrashes() {
        DiagCommands.items(now).filter { "run-as" in it.command }.forEach {
            assertTrue(it.command, it.command.startsWith("run-as ${DiagCommands.YAMP_PACKAGE} "))
            assertTrue(it.command, "files/logs/" in it.command || "files/crashes/" in it.command)
        }
    }

    @Test
    fun logcatCoversTheLastTwoHours() {
        assertEquals("11-15 16:30:00.000", DiagCommands.logcatSince(now))
        assertTrue(DiagCommands.items(now).single { it.file == "logcat.txt" }.command.endsWith("-T '11-15 16:30:00.000'"))
    }

    @Test
    fun logcatReadsAllFourBuffersKeepsTheNewestAndDropsTheCarPlaySpam() {
        val logcat = DiagCommands.items(now).single { it.file == "logcat.txt" }
        listOf("main", "system", "events", "crash").forEach { assertTrue(it, "-b $it" in logcat.command) }
        assertTrue(logcat.keepNewest)
        assertEquals(listOf("CarPlayService not found"), logcat.dropContaining)
    }

    @Test
    fun theExitMarkerIsSplitOffAndTheCapApplies() {
        val ok = AdbSession.parse("hello\n${AdbSession.EXIT_MARKER}0\n".toByteArray(), cutByStream = false, cap = 100)
        assertEquals("hello\n", String(ok.bytes))
        assertEquals(0, ok.exitCode)
        val failed = AdbSession.parse("run-as: denied\n${AdbSession.EXIT_MARKER}1\n".toByteArray(), false, 100)
        assertEquals(1, failed.exitCode)
        val capped = AdbSession.parse("0123456789${AdbSession.EXIT_MARKER}0".toByteArray(), false, cap = 4)
        assertEquals("0123", String(capped.bytes))
        assertTrue(capped.truncated)
    }
}
