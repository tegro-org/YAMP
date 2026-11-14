package org.tegro.yamp.data.player

import android.os.SystemClock
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener

/**
 * The engine's measurements for the journal: audio underruns (the sound card ran out of samples — an audible
 * click or stutter, as opposed to a STALL, which is the network running dry) and how long each start took.
 * Main thread only, like the engine.
 */
class EngineDiagnostics(
    private val journal: PlaybackJournal?,
    /** "active", "outgoing" or "standby": the player's part in the engine right now. */
    private val roleOf: (ExoPlayer) -> String
) {
    /** Underruns per player: the last one written to the journal, and how many were left out since. */
    private val underrunLoggedAt = longArrayOf(0L, 0L)
    private val underrunsSuppressed = intArrayOf(0, 0)

    /** Listens to [player]'s underruns and its start ([playerIdx]: 0 for A, 1 for B). */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun attach(player: ExoPlayer, playerIdx: Int) {
        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying && !isSilence(player)) sounding(player)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == androidx.media3.common.Player.STATE_READY && !isSilence(player)) ready(player)
            }
        })
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioUnderrun(eventTime: AnalyticsListener.EventTime, bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
                underrun(player, playerIdx, bufferSizeMs, elapsedSinceLastFeedMs)
            }
        })
    }

    /** At most one line per player per [UNDERRUN_LOG_INTERVAL_MS]; the ones in between are counted into the next line. */
    fun underrun(player: ExoPlayer, playerIdx: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
        val now = SystemClock.elapsedRealtime()
        if (now - underrunLoggedAt[playerIdx] < UNDERRUN_LOG_INTERVAL_MS) {
            underrunsSuppressed[playerIdx]++
            return
        }
        val more = underrunsSuppressed[playerIdx].takeIf { it > 0 }?.let { " (+$it more)" }.orEmpty()
        underrunLoggedAt[playerIdx] = now
        underrunsSuppressed[playerIdx] = 0
        val name = if (playerIdx == 0) "A" else "B"
        journal?.record(
            PlaybackJournal.Kind.UNDERRUN,
            player.mediaMetadata.title?.toString(),
            "player $name ${roleOf(player)} buf $bufferSizeMs ms gap $elapsedSinceLastFeedMs ms$more"
        )
    }

    /**
     * When a start was asked for and what it went through, written as one TIMING line once the track sounds
     * (plays, at whatever volume a crossfade gives it): from the user's skip (if one led here), from the request
     * to the engine, to the stream link in hand, to READY.
     */
    private class StartTiming(val player: ExoPlayer, val title: CharSequence?, val skipAt: Long?, val requestAt: Long, val linkAt: Long, var readyAt: Long = 0L)

    private var timing: StartTiming? = null

    /** When the user last skipped; taken by the next start. */
    private var skipRequestedAt: Long? = null

    fun skipRequested() {
        skipRequestedAt = SystemClock.elapsedRealtime()
    }

    /** When the engine was asked to play a track (before its link is in hand). */
    private var requestAt = 0L

    fun requested() {
        requestAt = SystemClock.elapsedRealtime()
    }

    /** The engine has the link and hands the track to [player], [ready] already (a warmed-up standby). */
    fun started(player: ExoPlayer, title: CharSequence?, ready: Boolean) {
        val now = SystemClock.elapsedRealtime()
        timing = StartTiming(player, title, skipRequestedAt, requestAt.takeIf { it > 0L } ?: now, now, if (ready) now else 0L)
        skipRequestedAt = null
    }

    /** The track queued behind silence has reached [player] (it was READY and playing all along, maybe). */
    fun arrived(player: ExoPlayer) {
        if (player.playbackState == androidx.media3.common.Player.STATE_READY) ready(player)
        if (player.isPlaying) sounding(player)
    }

    fun ready(player: ExoPlayer) {
        val t = timing ?: return
        if (t.player === player && t.readyAt == 0L) t.readyAt = SystemClock.elapsedRealtime()
    }

    fun sounding(player: ExoPlayer) {
        val t = timing ?: return
        if (t.player !== player) return
        timing = null
        val now = SystemClock.elapsedRealtime()
        val from = t.skipAt ?: t.requestAt
        val parts = listOfNotNull(
            t.skipAt?.let { "skip→request ${t.requestAt - it} ms" },
            "→link ${t.linkAt - from} ms",
            t.readyAt.takeIf { it > 0L }?.let { "→ready ${it - from} ms" },
            "→sounding ${now - from} ms"
        )
        journal?.record(PlaybackJournal.Kind.TIMING, t.title?.toString(), parts.joinToString(", "))
    }

    private fun isSilence(player: ExoPlayer) = player.currentMediaItem?.mediaId == org.tegro.yamp.data.repository.PlayerRepositoryImpl.PARKED_ID

    private companion object {
        const val UNDERRUN_LOG_INTERVAL_MS = 500L
    }
}
