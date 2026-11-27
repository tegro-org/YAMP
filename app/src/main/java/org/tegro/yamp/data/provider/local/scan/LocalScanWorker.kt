package org.tegro.yamp.data.provider.local.scan

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.tegro.yamp.R
import org.tegro.yamp.data.player.PlaybackController

/**
 * The scan as a unique WorkManager job [NAME] (docs/phase-4d-local-files.md §5): it survives the screen that started
 * it, runs one at a time, and is cancellable (the notification's Cancel, or a full rescan replacing it). It turns
 * into a foreground `dataSync` job only once there are files to read, so an unchanged library's quick pass shows no
 * notification. While the player starts a track (its buffering flag) the scanner waits.
 */
class LocalScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun scanner(): LocalScanner
        fun status(): LocalScanStatus
        fun playbackController(): PlaybackController
    }

    private val deps = EntryPointAccessors.fromApplication(context.applicationContext, Dependencies::class.java)

    override suspend fun doWork(): Result = coroutineScope {
        val full = inputData.getBoolean(KEY_FULL, false)
        val source = inputData.getLong(KEY_SOURCE, NO_SOURCE).takeIf { it != NO_SOURCE }
        val busy: StateFlow<Boolean> = deps.playbackController().isBuffering
        var shownAt = 0L
        val progress = launch {
            deps.status().state.collect { state ->
                val now = SystemClock.elapsedRealtime()
                if (state.phase == ScanState.Phase.READING && state.total > 0 && now - shownAt >= NOTIFY_EVERY_MS) {
                    shownAt = now
                    // Started from the background on Android 12+ a foreground service may be refused: scan anyway
                    runCatching { setForeground(foregroundInfo(state)) }
                }
            }
        }
        try {
            deps.scanner().scanAll(full, source, busy)
        } finally {
            progress.cancel()
        }
        Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(deps.status().state.value)

    private fun foregroundInfo(state: ScanState): ForegroundInfo {
        val context = applicationContext
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.local_scan_channel_name), NotificationManager.IMPORTANCE_LOW)
        )
        val text = if (state.total > 0) {
            context.getString(R.string.local_scan_progress, state.done, state.total, state.volumeLabel.orEmpty())
        } else {
            null
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.local_scan_title))
            .setContentText(text)
            .setProgress(state.total, state.done, state.total == 0)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, context.getString(R.string.download_cancel), WorkManager.getInstance(context).createCancelPendingIntent(id))
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val NAME = "local-scan"
        private const val KEY_FULL = "full"
        private const val KEY_SOURCE = "source"
        private const val NO_SOURCE = -1L
        private const val CHANNEL_ID = "local_scan"
        private const val NOTIFICATION_ID = 4301
        private const val NOTIFY_EVERY_MS = 1_000L

        /**
         * Starts a scan: an incremental one is dropped while one runs (it sees the same files); a [full] rescan
         * replaces a running scan; a [sourceId] scan (a newly added folder) runs after the running one.
         */
        fun enqueue(context: Context, full: Boolean = false, sourceId: Long? = null) {
            val request = OneTimeWorkRequestBuilder<LocalScanWorker>()
                .setInputData(workDataOf(KEY_FULL to full, KEY_SOURCE to (sourceId ?: NO_SOURCE)))
                .addTag(NAME)
                .build()
            val policy = when {
                full -> ExistingWorkPolicy.REPLACE
                sourceId != null -> ExistingWorkPolicy.APPEND_OR_REPLACE
                else -> ExistingWorkPolicy.KEEP
            }
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, policy, request)
        }
    }
}
