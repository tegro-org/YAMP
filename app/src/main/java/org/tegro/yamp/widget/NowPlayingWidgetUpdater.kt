package org.tegro.yamp.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.tegro.yamp.data.player.PlaybackController
import org.tegro.yamp.data.player.TrackReactions
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether a tick of the widgets' clock is worth a redraw: music plays, a widget of ours is on a home screen and the
 * screen is on (nobody sees it otherwise; the next tick refreshes). Pure; unit-tested.
 */
internal fun widgetTickDue(playing: Boolean, widgetCount: Int, screenOn: Boolean): Boolean = playing && widgetRedrawDue(widgetCount, screenOn)

/** A redraw anybody can see: a widget of ours is placed and the screen is on. Pure; unit-tested. */
internal fun widgetRedrawDue(widgetCount: Int, screenOn: Boolean): Boolean = widgetCount > 0 && screenOn

/** A widget of ours on a home screen: which kind, and the system's id of it. */
private class PlacedWidget(val widget: GlanceAppWidget, val appWidgetId: Int)

/**
 * Redraws the placed widgets whenever [WidgetStateHub.now] changes (the track, play, the heart, the cover, who is signed
 * in, the clock) and sees that every one of them did draw it. The clock moves every second while music plays: the progress
 * moves only then. The tick also keeps each widget's Glance session alive — Glance closes a session after five seconds
 * without an update (its idle timeout), and once closed, [GlanceAppWidget.update] only queues a draw in a worker of the
 * widget's own session, a worker the system may start late (or never) in the background; a live session redraws at once.
 * So every placed widget is asked at once (each bounded, so a stuck one holds nobody), and a widget still showing an old
 * state is asked again — a few times per state, never while the screen is off. The position is read again a moment after
 * a track starts, when the screen turns on and when a widget is placed, since a paused or hidden player has no tick.
 */
@Singleton
class NowPlayingWidgetUpdater @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val playbackController: PlaybackController,
    private val reactions: TrackReactions,
    private val hub: WidgetStateHub
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false
    private val budget = RetryBudget(CHECK_WAITS_MS.size)
    private val kinds = listOf(
        NowPlayingWidgetReceiver::class.java to NowPlayingWidget(),
        PlayerWidgetReceiver::class.java to PlayerWidget(),
        CoverWidgetReceiver::class.java to CoverWidget()
    )

    @OptIn(FlowPreview::class)
    fun start() {
        if (started) return
        started = true
        scope.launch { runCatching { reactions.loadCachedLikes() } }
        scope.launch {
            hub.now.debounce(REDRAW_DEBOUNCE_MS).collectLatest {
                refresh(placed())
                checkDrawn()
            }
        }
        scope.launch {
            hub.now.map { it.trackId }.distinctUntilChanged().drop(1).collectLatest {
                delay(RESAMPLE_MS)
                if (WidgetPlacement.visible(context)) WidgetClock.advance()
            }
        }
        scope.launch {
            hub.visible.drop(1).collect { if (it) WidgetClock.advance() }
        }
        // The progress moves every second while music plays. The tick also keeps the widget's Glance session alive:
        // it closes after five seconds idle, and a redraw of a closed session goes through a WorkManager worker that
        // the background can delay — a live session redraws at once. It ticks only while a widget is visible and the
        // screen is on, so a hidden widget costs nothing.
        scope.launch {
            playbackController.isPlaying.collectLatest { playing ->
                // Paused, the position moves only by a seek: redraw on that, there is no ticking
                if (!playing) {
                    playbackController.progress.drop(1).debounce(SEEK_SETTLE_MS).collect {
                        if (WidgetPlacement.visible(context)) WidgetClock.advance()
                    }
                }
                while (playing) {
                    delay(TICK_MS)
                    if (widgetTickDue(playing, WidgetPlacement.count(context), WidgetPlacement.screenOn(context))) WidgetClock.advance()
                }
            }
        }
    }

    private suspend fun refresh(targets: List<PlacedWidget>) {
        if (targets.isEmpty()) return
        val glance = GlanceAppWidgetManager(context)
        updateTogether(targets, UPDATE_TIMEOUT_MS, { target, failure -> Log.w(TAG, "Widget ${target.appWidgetId} not redrawn", failure) }) {
            it.widget.update(context, glance.getGlanceIdBy(it.appWidgetId))
        }
    }

    /** A widget whose draw is still queued, failed or lost with its session shows an old state: ask it again. */
    private suspend fun checkDrawn() {
        for (wait in CHECK_WAITS_MS) {
            delay(wait)
            if (!WidgetPlacement.screenOn(context)) return
            val targets = placed()
            val stale = hub.staleAmong(targets.map { it.appWidgetId }).toSet()
            if (stale.isEmpty() || !budget.take(widgetSyncKey(hub.now.value))) return
            Log.i(TAG, "Widgets $stale still show an old state: asking again")
            refresh(targets.filter { it.appWidgetId in stale })
        }
    }

    private fun placed(): List<PlacedWidget> {
        val manager = AppWidgetManager.getInstance(context)
        return kinds.flatMap { (receiver, widget) ->
            manager.getAppWidgetIds(ComponentName(context, receiver)).map { PlacedWidget(widget, it) }
        }
    }

    private companion object {
        const val TAG = "WidgetUpdater"
        // Below Glance's five-second session idle timeout, so the session stays alive and a redraw is never deferred
        // to a WorkManager worker while music plays and a widget is in sight.
        const val TICK_MS = 1_000L
        const val SEEK_SETTLE_MS = 500L
        const val REDRAW_DEBOUNCE_MS = 100L
        const val RESAMPLE_MS = 1_000L
        const val UPDATE_TIMEOUT_MS = 5_000L
        val CHECK_WAITS_MS = listOf(2_000L, 6_000L, 20_000L)
    }
}
