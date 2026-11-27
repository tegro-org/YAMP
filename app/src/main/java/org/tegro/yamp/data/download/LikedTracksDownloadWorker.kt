package org.tegro.yamp.data.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import org.tegro.yamp.R
import org.tegro.yamp.data.network.DownloadProgressInfo
import org.tegro.yamp.data.network.TrackDownloadManager
import org.tegro.yamp.data.provider.yandex.network.StreamVariants
import org.tegro.yamp.data.network.TrackPreloadManager
import org.tegro.yamp.data.provider.yandex.repository.LibraryRepository
import org.tegro.yamp.domain.provider.Outcome
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Downloads liked tracks for offline listening. Runs in WorkManager, so it survives closing the app and
 * a reboot, waits for a connection and shows progress in a notification.
 * With [KEY_TRACK_ID] it downloads that single track (auto-download after a like). With [KEY_UPGRADE] it
 * re-downloads already saved tracks the server now offers in a better quality. With [KEY_AUTO] only the accounts whose
 * own auto-download is on (Settings → Accounts → the account) take part.
 */
class LikedTracksDownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun libraryRepository(): LibraryRepository
        fun downloadManager(): TrackDownloadManager
        fun preloadManager(): TrackPreloadManager
        fun soundMode(): org.tegro.yamp.data.player.SoundMode
        fun accountContext(): org.tegro.yamp.data.account.AccountContext
        fun providerRegistry(): org.tegro.yamp.data.provider.ProviderRegistry
        fun jellyfinClients(): org.tegro.yamp.data.provider.jellyfin.JellyfinClients
        fun settings(): org.tegro.yamp.domain.repository.SettingsRepository
        fun plusGate(): org.tegro.yamp.data.account.PlusGate
    }

    private val deps = EntryPointAccessors.fromApplication(context.applicationContext, Dependencies::class.java)

    /** One file to fetch: its title for the progress, and how. */
    private class Job(val title: String, val artist: String, val run: suspend () -> Boolean)

    override suspend fun doWork(): Result {
        val single = inputData.getString(KEY_TRACK_ID)
        val upgrade = inputData.getBoolean(KEY_UPGRADE, false)
        // An automatic run covers only the accounts whose own auto-download is on; the user's button covers all
        val auto = inputData.getBoolean(KEY_AUTO, false)
        // The Jellyfin favourites join the whole-collection run (gap b); a single track and the upgrade are Yandex's
        val jobs = yandexJobs(single, upgrade, auto) + if (single == null && !upgrade) jellyfinJobs(auto) else emptyList()
        if (jobs.isEmpty()) return Result.success()

        val downloads = deps.downloadManager()
        var failed = 0
        try {
            for ((index, job) in jobs.withIndex()) {
                if (isStopped) break
                if (single == null) {
                    downloads.setDownloadProgress(DownloadProgressInfo(index, jobs.size, job.title, job.artist))
                    setForeground(foregroundInfo(index, jobs.size, job.title, upgrade))
                }
                if (!job.run()) failed++
            }
        } finally {
            if (single == null) downloads.setDownloadProgress(null)
        }
        // A track that cannot be had (gone from the catalogue, not for this region) failed every run, so with the
        // rest done the job waited "for the network" forever on a good connection. Retried only when it is gone
        // (upstream 1.14.12)
        if (failed > 0 && DownloadOutcome.shouldRetry(failed, online = isOnline())) return Result.retry()
        val done = DownloadOutcome(done = jobs.size - failed, failed = failed)
        return Result.success(workDataOf(KEY_DONE to done.done, KEY_FAILED to done.failed))
    }

    /** Whether the device has a network now. Unknown counts as online, so a broken check never retries forever. */
    private fun isOnline(): Boolean = runCatching {
        val manager = applicationContext.getSystemService(android.net.ConnectivityManager::class.java)
        manager.getNetworkCapabilities(manager.activeNetwork)
            ?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(true)

    private suspend fun yandexJobs(single: String?, upgrade: Boolean, auto: Boolean): List<Job> {
        val downloads = deps.downloadManager()
        val library = deps.libraryRepository()
        // The account in force now: its links, and its client fetches the files (D60)
        val account = deps.accountContext().current()
        if (auto && (account == null || !deps.settings().autoDownloadFor(account.value))) return emptyList()
        // An account without Plus downloads nothing by itself until Plus is back; its files stay (D101)
        if (auto && deps.plusGate().blocks(account)) return emptyList()
        library.loadCached()
        return library.likedTracks.value
            .filter { single == null || it.idString == single }
            .filter { downloads.isTrackDownloaded(it.idString) == upgrade }
            .map { track ->
                Job(track.title.orEmpty(), track.artists.firstOrNull()?.name.orEmpty()) {
                    val stream = deps.preloadManager().resolveForDownload(track.idString, account)
                    when {
                        stream == null -> false
                        !upgrade -> downloads.downloadTrack(track.idString, stream.url, stream.quality, account = account)
                        else -> {
                            val saved = downloads.downloadedQuality(track.idString) ?: stream.bestMp3
                            // A FLAC file is silent on a device in compatible sound, so the ordinary one is an upgrade
                            val silentFile = saved?.isLossless == true && deps.soundMode().isCompatible()
                            saved != null && !silentFile && !StreamVariants.isBetter(stream.quality, saved) ||
                                downloads.downloadTrack(track.idString, stream.url, stream.quality, replace = true, account = account)
                        }
                    }
                }
            }
    }

    /**
     * Every signed-in Jellyfin account's favourites not on the device yet (gap b), each saved for its account: the
     * file as the account's quality now plays it, fetched with its auth header for its server alone.
     */
    private suspend fun jellyfinJobs(auto: Boolean): List<Job> {
        val downloads = deps.downloadManager()
        val registry = deps.providerRegistry()
        val settings = deps.settings()
        val accounts = registry.jellyfin().filter { !auto || settings.autoDownloadFor(it.account.value) }
        android.util.Log.i(TAG, "Jellyfin accounts: ${accounts.size}")
        return accounts.flatMap { provider ->
            val account = provider.account
            val result = provider.library?.likedTracks()
            val favourites = (result as? Outcome.Ok)?.value.orEmpty()
            android.util.Log.i(TAG, "Jellyfin favourites of ${account.value}: ${favourites.size}" + if (result is Outcome.Err) " (${result.error})" else "")
            favourites.mapNotNull { track ->
                val itemId = track.ref.id
                if (downloads.isTrackDownloaded(itemId) &&
                    org.tegro.yamp.data.provider.jellyfin.JellyfinOnDevice.sameServer(downloads.ownersOf(itemId), account)
                ) {
                    downloads.addOwner(itemId, account)
                    return@mapNotNull null
                }
                Job(track.title, track.artists.firstOrNull()?.name.orEmpty()) {
                    when (val stream = registry.jellyfinStreams(account)?.downloadStream(track.ref)) {
                        is Outcome.Ok -> downloads.downloadTrack(
                            itemId, stream.value.uri, stream.value.quality?.let { TrackPreloadManager.StreamQuality(it.codec, it.bitrateKbps) },
                            headers = stream.value.headers, http = deps.jellyfinClients().downloads, owner = account,
                            cacheKey = stream.value.cacheKey
                        )
                        else -> false
                    }
                }
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0, 0, "", inputData.getBoolean(KEY_UPGRADE, false))

    private fun foregroundInfo(done: Int, total: Int, title: String, upgrade: Boolean): ForegroundInfo {
        val context = applicationContext
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.download_channel_name), NotificationManager.IMPORTANCE_LOW)
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(if (upgrade) R.string.download_upgrade_title else R.string.download_notification_title))
            .setContentText(if (total > 0) context.getString(R.string.download_progress_count, done, total) + " · " + title else null)
            .setProgress(total, done, total == 0)
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
        const val KEY_TRACK_ID = "track_id"
        const val KEY_UPGRADE = "upgrade_quality"
        /** An automatic run (not the user's button): only the accounts with auto-download on. */
        const val KEY_AUTO = "auto"

        /** In the finished job's output: how many were downloaded, how many could not be. */
        const val KEY_DONE = "done"
        const val KEY_FAILED = "failed"
        private const val TAG = "LikedTracksDownload"
        private const val CHANNEL_ID = "yandex_music_lite_downloads"
        private const val NOTIFICATION_ID = 4201
    }
}
