package org.tegro.yamp.data.provider.jellyfin

import org.tegro.yamp.domain.account.AccountId
import kotlin.math.abs

/**
 * Decides which play reports a Jellyfin server gets (docs/phase-3-providers.md step 26), from snapshots of the
 * player taken about once a second: "started" when a Jellyfin track first sounds, "progress" on pause / resume /
 * seek and every [PROGRESS_EVERY_MS] while playing, "stopped" when the track changes or playback ends. Pure: the
 * runner ([JellyfinPlayReporting]) sends what it returns.
 */
class PlayReportPlanner(private val newSessionId: () -> String) {

    /** What the player shows now; [itemId] is set only for a Jellyfin track, with its [account]. */
    data class Snapshot(val itemId: String?, val account: AccountId?, val playing: Boolean, val positionMs: Long, val nowMs: Long)

    /** Why a progress report goes out. */
    enum class Change { TIME, SEEK, PAUSED, RESUMED }

    sealed interface Report {
        val account: AccountId
        val itemId: String
        val positionMs: Long
        val session: String

        data class Started(override val account: AccountId, override val itemId: String, override val positionMs: Long, override val session: String) : Report
        data class Progress(
            override val account: AccountId,
            override val itemId: String,
            override val positionMs: Long,
            override val session: String,
            val paused: Boolean,
            val change: Change = Change.TIME
        ) : Report
        data class Stopped(override val account: AccountId, override val itemId: String, override val positionMs: Long, override val session: String) : Report
    }

    private class Current(val account: AccountId, val itemId: String, val session: String) {
        var started = false
        var playing = false
        var positionMs = 0L
        var atMs = 0L
        var lastProgressAtMs = 0L
    }

    private var current: Current? = null

    fun next(now: Snapshot): List<Report> {
        val out = mutableListOf<Report>()
        val same = current?.let { it.itemId == now.itemId && it.account == now.account } == true
        if (!same) {
            current?.takeIf { it.started }?.let { out += Report.Stopped(it.account, it.itemId, it.positionMs, it.session) }
            current = if (now.itemId != null && now.account != null) Current(now.account, now.itemId, newSessionId()) else null
        }
        val c = current ?: return out
        if (!c.started) {
            if (now.playing) {
                out += Report.Started(c.account, c.itemId, now.positionMs, c.session)
                c.started = true
                c.lastProgressAtMs = now.nowMs
            }
        } else {
            val expected = c.positionMs + if (c.playing) now.nowMs - c.atMs else 0L
            val seeked = abs(now.positionMs - expected) > SEEK_TOLERANCE_MS
            val due = now.playing && now.nowMs - c.lastProgressAtMs >= PROGRESS_EVERY_MS
            val change = when {
                now.playing != c.playing -> if (now.playing) Change.RESUMED else Change.PAUSED
                seeked -> Change.SEEK
                due -> Change.TIME
                else -> null
            }
            if (change != null) {
                out += Report.Progress(c.account, c.itemId, now.positionMs, c.session, paused = !now.playing, change = change)
                c.lastProgressAtMs = now.nowMs
            }
        }
        c.playing = now.playing
        c.positionMs = now.positionMs
        c.atMs = now.nowMs
        return out
    }

    companion object {
        const val PROGRESS_EVERY_MS = 10_000L
        const val SEEK_TOLERANCE_MS = 3_000L
    }
}
