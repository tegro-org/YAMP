package org.tegro.yamp.data.provider.jellyfin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.tegro.yamp.data.player.PlaybackController
import org.tegro.yamp.data.player.PlaybackJournal
import org.tegro.yamp.domain.account.Provider
import org.tegro.yamp.domain.provider.Outcome
import org.tegro.yamp.domain.queue.service
import org.tegro.yamp.provider.jellyfin.JellyfinPlayReporter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends Jellyfin play reports (step 26) for whatever the player plays, under the playing queue's account: a
 * once-a-second look at the player, [PlayReportPlanner] decides, the reports go out fire-and-forget. A failed
 * report is one journal line; it never touches playback.
 */
@Singleton
class JellyfinPlayReporting @Inject constructor(
    private val playback: PlaybackController,
    private val clients: JellyfinClients,
    private val journal: PlaybackJournal
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val planner = PlayReportPlanner { UUID.randomUUID().toString().replace("-", "") }
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                planner.next(snapshot()).forEach(::send)
                delay(TICK_MS)
            }
        }
    }

    private fun snapshot(): PlayReportPlanner.Snapshot {
        val track = playback.currentTrack.value?.takeIf { it.ref.service == Provider.JELLYFIN }
        val account = playback.playingAccount?.takeIf { it.provider == Provider.JELLYFIN }
        return PlayReportPlanner.Snapshot(
            itemId = track?.takeIf { account != null }?.ref?.id,
            account = account,
            playing = playback.isPlaying.value,
            positionMs = playback.progress.value,
            nowMs = System.currentTimeMillis()
        )
    }

    private fun send(report: PlayReportPlanner.Report) {
        val api = clients.api(report.account) ?: return
        scope.launch(Dispatchers.IO) {
            val reporter = JellyfinPlayReporter(api)
            val outcome = when (report) {
                is PlayReportPlanner.Report.Started -> reporter.started(report.itemId, report.positionMs, report.session)
                is PlayReportPlanner.Report.Progress -> reporter.progress(
                    report.itemId, report.positionMs, report.paused, report.session,
                    when (report.change) {
                        PlayReportPlanner.Change.PAUSED -> JellyfinPlayReporter.Event.PAUSE
                        PlayReportPlanner.Change.RESUMED -> JellyfinPlayReporter.Event.UNPAUSE
                        else -> JellyfinPlayReporter.Event.TIME_UPDATE
                    }
                )
                is PlayReportPlanner.Report.Stopped -> reporter.stopped(report.itemId, report.positionMs, report.session)
            }
            if (outcome is Outcome.Err) {
                journal.record(PlaybackJournal.Kind.REPORT, null, "${report.javaClass.simpleName.lowercase()} not sent: ${outcome.error}")
            }
        }
    }

    private companion object {
        const val TICK_MS = 1_000L
    }
}
