package org.tegro.yamp.data.player

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/** How a run of the app ended, as Android tells it: [reason] is one of [ProcessExits.reasonName]. */
data class ProcessExit(val atMs: Long, val reason: String)

/**
 * Android's own word on why the app's process ended — the journal's [PlaybackJournal.Kind.KILLED] line, so "it stops by
 * itself" can tell the device from the app. Nothing before Android 11: the system keeps no such history there (the
 * head unit is Android 9), and the line is simply not written.
 */
class ProcessExits(private val context: Context) {

    /** The last exit of this app's process at or after [sinceMs]; null when Android does not say. */
    fun lastSince(sinceMs: Long): ProcessExit? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        val exit = am.getHistoricalProcessExitReasons(context.packageName, 0, LOOK_BACK)
            .filter { it.processName == context.packageName }
            .maxByOrNull { it.timestamp }
            ?.takeIf { it.timestamp >= sinceMs } ?: return null
        return ProcessExit(exit.timestamp, reasonName(exit.reason))
    }

    companion object {
        /** How many of the last exits are looked at: the app's own is normally the first. */
        private const val LOOK_BACK = 5

        /** Android's reason, as a stable name the journal keeps and the developer reads. Pure; unit-tested. */
        fun reasonName(reason: Int): String = when (reason) {
            REASON_LOW_MEMORY -> LOW_MEMORY
            REASON_CRASH, REASON_CRASH_NATIVE -> CRASH
            REASON_ANR -> ANR
            REASON_USER_REQUESTED, REASON_USER_STOPPED -> USER
            REASON_PACKAGE_UPDATED, REASON_PACKAGE_STATE_CHANGE -> UPDATED
            REASON_EXCESSIVE_RESOURCE_USAGE, REASON_FREEZER -> BATTERY
            REASON_SIGNALED, REASON_OTHER -> SYSTEM
            else -> UNKNOWN
        }

        // ApplicationExitInfo's reasons; some came after Android 11, so they are written out
        private const val REASON_SIGNALED = 2
        private const val REASON_LOW_MEMORY = 3
        private const val REASON_CRASH = 4
        private const val REASON_CRASH_NATIVE = 5
        private const val REASON_ANR = 6
        private const val REASON_EXCESSIVE_RESOURCE_USAGE = 9
        private const val REASON_USER_REQUESTED = 10
        private const val REASON_USER_STOPPED = 11
        private const val REASON_OTHER = 13
        private const val REASON_FREEZER = 14
        private const val REASON_PACKAGE_STATE_CHANGE = 15
        private const val REASON_PACKAGE_UPDATED = 16

        const val LOW_MEMORY = "low_memory"
        const val CRASH = "crash"
        const val ANR = "anr"
        const val USER = "user"
        const val UPDATED = "updated"
        const val BATTERY = "battery"
        const val SYSTEM = "system"
        const val UNKNOWN = "unknown"
    }
}
