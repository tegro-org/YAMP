package org.tegro.yamp.diag

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One item of the collection: a fixed shell command and the zip entry its output goes to. */
data class DiagItem(
    val file: String,
    val command: String,
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    val capBytes: Int = DEFAULT_CAP_BYTES,
) {
    companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000L
        const val DEFAULT_CAP_BYTES = 8 * 1024 * 1024
    }
}

/**
 * The fixed command set (docs/diag-tool.md). Nothing here is built from user input: the only variable part is the
 * logcat start time, which Diag formats itself.
 */
object DiagCommands {
    const val YAMP_PACKAGE = "org.tegro.music.yamp"

    /** How far back the system log goes: a ride's worth. */
    const val LOGCAT_WINDOW_MS = 2L * 60 * 60 * 1000
    private const val LOGCAT_TIMEOUT_MS = 20_000L

    fun items(now: Date): List<DiagItem> = listOf(
        DiagItem("audio_flinger.txt", "dumpsys media.audio_flinger"),
        DiagItem("audio.txt", "dumpsys audio"),
        DiagItem("media_session.txt", "dumpsys media_session"),
        DiagItem("getprop.txt", "getprop"),
        DiagItem(
            "logcat.txt",
            "logcat -d -v threadtime -T '${logcatSince(now)}'",
            timeoutMs = LOGCAT_TIMEOUT_MS,
        ),
        DiagItem("yamp_package.txt", "dumpsys package $YAMP_PACKAGE"),
        // run-as works only for a debuggable YAMP; it starts in YAMP's data directory. Only these two folders are read.
        DiagItem("yamp/yamp.log", "run-as $YAMP_PACKAGE cat files/logs/yamp.log"),
        DiagItem("yamp/yamp.1.log", "run-as $YAMP_PACKAGE cat files/logs/yamp.1.log"),
        DiagItem(
            "yamp/crashes.txt",
            "run-as $YAMP_PACKAGE sh -c 'for f in files/crashes/crash_*.txt; do " +
                "[ -f \"\$f\" ] || continue; echo \"=== \$f\"; cat \"\$f\"; done'",
        ),
    )

    /** logcat's `-T` time format, on the device's clock. */
    fun logcatSince(now: Date): String =
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date(now.time - LOGCAT_WINDOW_MS))
}
