package org.tegro.yamp.data.provider.local.scan

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.tegro.yamp.data.player.PlaybackController
import org.tegro.yamp.data.provider.local.LocalAccountSession
import org.tegro.yamp.data.provider.local.LocalJournal
import org.tegro.yamp.data.provider.local.LocalSources
import org.tegro.yamp.data.provider.local.VolumeMonitor
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * When the sources are scanned (docs/phase-4d-local-files.md §5 triggers; the scan itself is incremental and cheap
 * when nothing changed):
 * - **app start**, ~[START_DELAY_MS] after the queue restore (the restore never waits for a scan);
 * - **a volume mounts** that holds a source (VolumeMonitor): that stick's files are read as soon as it is there;
 * - **a media change hint**: a debounced ([HINT_DEBOUNCE_MS]) `ContentObserver` on `MediaStore.Audio` while the app
 *   runs — cheap "something changed somewhere";
 * - **daily**: a periodic job ([DailyScanWorker]) that enqueues the same scan;
 * - and the Rescan button (the Sources screen).
 * Every trigger goes through the one unique job [LocalScanWorker.NAME]: a second request while one runs is dropped.
 */
@Singleton
class LocalTriggers @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sources: LocalSources,
    private val volumes: VolumeMonitor,
    private val session: LocalAccountSession,
    private val playbackController: PlaybackController,
    private val journal: LocalJournal
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val hints = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var started: Job? = null

    @OptIn(FlowPreview::class)
    fun start() {
        if (started != null) return
        started = scope.launch {
            session.reconcile()
            // The restore first: a scan never competes with the queue coming back
            withTimeoutOrNull(RESTORE_WAIT_MS) { playbackController.awaitRestoredSession() }
            delay(START_DELAY_MS)
            if (sources.all().isNotEmpty()) scan()
        }
        // The journal: every change of what the app sees (a stick in, its mount point unreadable, …)
        volumes.volumes.map { it.values.map(LocalJournal::volume) }.distinctUntilChanged()
            .onEach { journal.volumes(volumes.volumes.value.values) }
            .launchIn(scope)
        // A volume that holds a source mounts: read what changed on it (after a scan that may be running — that one
        // skipped the absent volume)
        var before: Set<String>? = null
        volumes.volumes
            .map { all -> all.filterValues { it.mounted }.keys }
            .distinctUntilChanged()
            .onEach { mounted ->
                val came = before?.let { mounted - it }.orEmpty()
                before = mounted
                sources.all().filter { it.volumeId in came }.forEach { LocalScanWorker.enqueue(context, sourceId = it.id) }
            }
            .launchIn(scope)
        hints.debounce(HINT_DEBOUNCE_MS).onEach { if (sources.all().isNotEmpty()) scan() }.launchIn(scope)
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                hints.tryEmit(Unit)
            }
        }
        runCatching { context.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer) }
        DailyScanWorker.schedule(context)
    }

    private fun scan() = LocalScanWorker.enqueue(context)

    private companion object {
        const val START_DELAY_MS = 10_000L
        const val RESTORE_WAIT_MS = 30_000L
        const val HINT_DEBOUNCE_MS = 30_000L
    }
}

/** Once a day: the incremental scan (through the unique job, so it never runs twice at once). */
class DailyScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        LocalScanWorker.enqueue(applicationContext)
        return Result.success()
    }

    companion object {
        private const val NAME = "local-scan-daily"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<DailyScanWorker>(1, TimeUnit.DAYS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
