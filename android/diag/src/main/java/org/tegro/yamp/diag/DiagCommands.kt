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
    /** Long logs: read to the end and keep the newest lines within the cap (see [LogTail]). */
    val keepNewest: Boolean = false,
    /** Lines containing any of these are left out (counted in the header). */
    val dropContaining: List<String> = emptyList(),
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
    const val PID_FILE = "yamp_pid.txt"
    const val PROCESSES_FILE = "processes.txt"
    const val AUDIO_FLINGER_FILE = "audio_flinger.txt"

    /** How far back the system log goes: a ride's worth. */
    const val LOGCAT_WINDOW_MS = 2L * 60 * 60 * 1000
    private const val LOGCAT_TIMEOUT_MS = 30_000L

    /** The head unit's log noise: ~51k lines a ride that pushed everything useful out of the cap. */
    val LOGCAT_SPAM = listOf("CarPlayService not found")

    /** System crash/ANR record kinds kept by DropBoxManager. */
    val DROPBOX_TAGS = listOf("data_app_anr", "data_app_crash", "data_app_native_crash", "SYSTEM_TOMBSTONE", "system_app_anr")
    private const val DROPBOX_CAP_BYTES = 1024 * 1024

    fun items(now: Date): List<DiagItem> = listOf(
        DiagItem(AUDIO_FLINGER_FILE, "dumpsys media.audio_flinger"),
        DiagItem("audio.txt", "dumpsys audio"),
        DiagItem("media_session.txt", "dumpsys media_session"),
        DiagItem("getprop.txt", "getprop"),
        DiagItem(
            "logcat.txt",
            // All four buffers: the 2026-11-16 snapshots had only system + crash (the default on the unit)
            "logcat -d -v threadtime -b main -b system -b events -b crash -T '${logcatSince(now)}'",
            timeoutMs = LOGCAT_TIMEOUT_MS,
            keepNewest = true,
            dropContaining = LOGCAT_SPAM,
        ),
        DiagItem("yamp_package.txt", "dumpsys package $YAMP_PACKAGE"),
        // Who is who in the audio_flinger dump: YAMP's pid, and the apps attaching effects to its session
        DiagItem(PID_FILE, "pidof $YAMP_PACKAGE"),
        DiagItem(PROCESSES_FILE, "ps -A -o PID,NAME"),
    ) + DROPBOX_TAGS.map { tag ->
        // The system's own crash records: an ANR kill or a native crash leaves no trace in YAMP's crash log
        // (the 2026-11-16 Back-button death). Entries print oldest first, so a capped file keeps the newest.
        DiagItem("dropbox/$tag.txt", "dumpsys dropbox --print $tag", capBytes = DROPBOX_CAP_BYTES, keepNewest = true)
    } + listOf(
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
