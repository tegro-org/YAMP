package org.tegro.yamp.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.graphics.scale
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.tegro.yamp.R
import org.tegro.yamp.data.account.SignedInAccounts
import org.tegro.yamp.data.player.PlaybackController
import org.tegro.yamp.data.player.PlaylistSource
import org.tegro.yamp.data.player.TrackReactions
import org.tegro.yamp.domain.media.Track
import org.tegro.yamp.domain.repository.AuthRepository
import org.tegro.yamp.presentation.common.coverUri
import org.tegro.yamp.utils.CoverSize
import org.tegro.yamp.utils.toCoverUrl
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** A cover and the soft glow made of it; both null while there is none. */
internal class WidgetArt(val cover: Bitmap?, val glow: Bitmap?) {
    companion object {
        val None = WidgetArt(null, null)
    }
}

/**
 * A small in-memory cache that runs one load per key however many ask at once, and drops the oldest key past
 * [capacity]. A load nobody waits for any more (every caller gave up, or the key was dropped meanwhile) is cancelled.
 * A result that is not [worthKeeping] (a failed load) is forgotten, so the next ask tries again. Pure of Android;
 * unit-tested.
 */
internal class SingleFlightCache<K : Any, V : Any>(
    private val capacity: Int,
    private val scope: CoroutineScope,
    private val worthKeeping: (V) -> Boolean,
    private val load: suspend (K) -> V
) {
    private class Entry<V>(val load: Deferred<V>) {
        var waiters = 0
    }

    private val entries = LinkedHashMap<K, Entry<V>>(capacity + 1, 0.75f, true)

    suspend fun get(key: K): V {
        val entry = synchronized(entries) {
            val found = entries.getOrPut(key) { Entry(scope.async { load(key) }) }
            found.waiters++
            while (entries.size > capacity) entries.remove(entries.keys.first())
            found
        }
        try {
            val value = entry.load.await()
            if (!worthKeeping(value)) synchronized(entries) { if (entries[key] === entry) entries.remove(key) }
            return value
        } finally {
            synchronized(entries) {
                entry.waiters--
                if (entry.waiters == 0 && entry.load.isActive) {
                    entry.load.cancel()
                    if (entries[key] === entry) entries.remove(key)
                }
            }
        }
    }
}

/**
 * What each placed widget last drew (by app widget id), told by its composition, and the draws that failed after that.
 * A widget is current only when its last draw is the current state and no failure came since. Pure; unit-tested.
 */
internal class RenderLedger {
    private val drawn = ConcurrentHashMap<Int, WidgetNow>()

    fun drew(appWidgetId: Int, state: WidgetNow) {
        drawn[appWidgetId] = state
    }

    fun failed(appWidgetId: Int) {
        drawn.remove(appWidgetId)
    }

    /** The placed widgets that do not show [current] (one that never drew, or whose draw failed, is among them). */
    fun staleAmong(placed: Collection<Int>, current: WidgetNow): List<Int> {
        drawn.keys.retainAll(placed.toSet())
        return placed.filter { drawn[it] != current }
    }
}

/** The state without its position: what a re-ask is counted against, so a tick of the clock is not a new state. Pure. */
internal fun widgetSyncKey(state: WidgetNow): WidgetNow = state.copy(progress = null, positionMs = 0)

/** At most [max] re-asks for one state: a widget that never draws it is not asked for ever. Pure; unit-tested. */
internal class RetryBudget(private val max: Int) {
    private var key: Any? = null
    private var used = 0

    /** Whether one more re-ask for [key] is allowed (and counts it). A new key starts afresh. */
    fun take(key: Any?): Boolean {
        if (key != this.key) {
            this.key = key
            used = 0
        }
        if (used >= max) return false
        used++
        return true
    }
}

/**
 * Whether a press on a widget's heart or thumb is meant for the track that is playing: the widget names the track it
 * showed, and a widget that was a moment behind a change of track must not react to the new one. A press from before
 * the widgets named a track ([displayed] null) is taken as meant for the current one. Pure; unit-tested.
 */
internal fun widgetReactionTargets(displayed: String?, current: String?): Boolean = displayed == null || displayed == current

/**
 * Runs [update] for every target at once, each bounded by [timeoutMs]; one that throws or times out gets [onError] and
 * never stops the others. A cancellation of the whole is not an error: it is passed on. Pure of Android; unit-tested.
 */
internal suspend fun <T> updateTogether(
    targets: List<T>,
    timeoutMs: Long = Long.MAX_VALUE,
    onError: (T, Exception) -> Unit = { _, _ -> },
    update: suspend (T) -> Unit
) = coroutineScope {
    targets.forEach { target ->
        launch {
            try {
                withTimeout(timeoutMs) { update(target) }
            } catch (timedOut: TimeoutCancellationException) {
                onError(target, timedOut)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
                onError(target, failure)
            }
        }
    }
}

/** Where our widgets are and whether anybody can see them. */
internal object WidgetPlacement {
    private val receivers = listOf(NowPlayingWidgetReceiver::class.java, PlayerWidgetReceiver::class.java, CoverWidgetReceiver::class.java)

    fun count(context: Context): Int {
        val manager = AppWidgetManager.getInstance(context)
        return receivers.sumOf { manager.getAppWidgetIds(ComponentName(context, it)).size }
    }

    fun screenOn(context: Context): Boolean = (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive != false

    fun visible(context: Context): Boolean = widgetRedrawDue(count(context), screenOn(context))
}

/**
 * The one state every widget draws: [WidgetNow] is made here, once, from the player and the library, with one cover
 * load per track in a small cache. A widget's composition only reads [now], so two widgets can never disagree, and a
 * composition that starts late (the system had closed the widget's session) begins at the latest state.
 */
@Singleton
class WidgetStateHub @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val controller: PlaybackController,
    private val reactions: TrackReactions,
    authRepository: AuthRepository,
    signedInAccounts: SignedInAccounts
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val covers = SingleFlightCache<String, WidgetArt>(COVER_CACHE, scope, { it.cover != null }) { loadArt(it) }

    private val ledger = RenderLedger()

    private val visibilityPulse = MutableStateFlow(0)

    /** A widget is placed and the screen is on: only then is a cover worth loading and a position worth reading. */
    val visible: StateFlow<Boolean> = visibilityPulse.map { WidgetPlacement.visible(context) }.distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, WidgetPlacement.visible(context))

    init {
        val screen = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = refreshVisibility()
        }
        ContextCompat.registerReceiver(
            context, screen,
            IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_SCREEN_OFF) },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    /** Called when a widget is placed or removed (the receivers) and when the screen turns on or off. */
    fun refreshVisibility() {
        visibilityPulse.value++
    }

    private class Shown(val track: Track?, val art: WidgetArt)
    private class Playing(val shown: Shown, val playing: Boolean, val source: PlaylistSource, val positionMs: Long)
    private class Library(val liked: Set<String>, val stations: Set<String>, val yandex: Boolean, val anyAccount: Boolean)

    // A new track is shown together with its cover (the cover's wait is bounded), so the widgets draw once, not twice.
    // A track that goes away is not shown as "nothing playing" at once: a queue that is being switched has a gap.
    // With no widget in sight no cover is loaded; a failed load is tried once more at the next tick of the clock
    private val shown: Flow<Shown> = flow {
        var last: Track? = null
        combine(controller.currentTrack, visible) { track, visible -> track to visible }.transformLatest { (track, visible) ->
            if (track == null && last != null) delay(EMPTY_SETTLE_MS)
            val url = track?.coverUri.toCoverUrl(CoverSize.Medium)
            if (url == null || !visible) {
                emit(Shown(track, WidgetArt.None))
                return@transformLatest
            }
            coroutineScope {
                val pending = async { covers.get(url) }
                val quick = withTimeoutOrNull(ART_WAIT_MS) { pending.await() }
                emit(Shown(track, quick ?: WidgetArt.None))
                val art = quick ?: pending.await().also { emit(Shown(track, it)) }
                if (art.cover == null) {
                    WidgetClock.tick.drop(1).first()
                    val again = covers.get(url)
                    if (again.cover != null) emit(Shown(track, again))
                }
            }
        }.collect {
            last = it.track
            emit(it)
        }
    }

    private val yandex = authRepository.isAuthorized().stateIn(scope, SharingStarted.Eagerly, authRepository.getToken() != null)

    // The player's position lags a change of track by a moment: the first draw of a new track starts at zero
    private var positionOf: String? = null

    private val player = combine(shown, controller.isPlaying, controller.currentSource, WidgetClock.tick) { shown, playing, source, _ ->
        val ref = shown.track?.ref?.value
        val position = if (ref != null && positionOf != null && positionOf != ref) 0L else controller.progress.value
        positionOf = ref ?: positionOf
        Playing(shown, playing, source, position)
    }
    private val library = combine(reactions.likedTrackIds, reactions.favoriteStationIds, yandex, signedInAccounts.accounts) { liked, stations, yandex, accounts ->
        Library(liked, stations, yandex, accounts.isNotEmpty())
    }

    val now: StateFlow<WidgetNow> = combine(player, library, ::build).stateIn(
        scope,
        SharingStarted.Eagerly,
        build(
            Playing(Shown(controller.currentTrack.value, WidgetArt.None), controller.isPlaying.value, controller.currentSource.value, controller.progress.value),
            Library(reactions.likedTrackIds.value, reactions.favoriteStationIds.value, yandex.value, signedInAccounts.accounts.value.isNotEmpty())
        )
    )

    private fun build(player: Playing, library: Library): WidgetNow = widgetNowOf(
        track = player.shown.track,
        playing = player.playing,
        playlistSource = player.source,
        likedIds = library.liked,
        stationIds = library.stations,
        positionMs = player.positionMs,
        liveLabel = context.getString(R.string.widget_live),
        canWave = library.yandex,
        cover = player.shown.art.cover,
        glow = player.shown.art.glow,
        hasAccount = library.anyAccount
    )

    /**
     * Waits (the caller bounds it) until the saved session is back, the cached likes are in and [now] has caught up
     * with the player — so a composition that starts in a fresh process never draws "nothing playing" over a session
     * that is only being restored.
     */
    suspend fun awaitSettled() {
        refreshVisibility()
        controller.awaitRestoredSession()
        reactions.loadCachedLikes()
        now.first { it.hasTrack == (controller.currentTrack.value != null) }
    }

    /** A widget's composition has this state; its draw to the launcher follows at once. */
    fun markRendered(appWidgetId: Int, state: WidgetNow) = ledger.drew(appWidgetId, state)

    /** The widget's draw to the launcher failed: whatever it composed is not on screen. */
    fun markFailed(appWidgetId: Int) = ledger.failed(appWidgetId)

    fun staleAmong(placed: Collection<Int>): List<Int> = ledger.staleAmong(placed, now.value)

    private suspend fun loadArt(url: String): WidgetArt = try {
        val request = ImageRequest.Builder(context).data(url).size(COVER_PX).allowHardware(false).build()
        val cover = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
        // A widget cannot blur: the cover brought down to a few pixels and drawn large smooths into its own colours
        if (cover == null) WidgetArt.None else WidgetArt(cover, cover.scale(GLOW_TINY, GLOW_TINY).scale(GLOW_PX, GLOW_PX))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
        Log.w(TAG, "Widget cover failed", failure)
        WidgetArt.None
    }

    private companion object {
        const val TAG = "WidgetState"
        const val COVER_CACHE = 3
        const val ART_WAIT_MS = 800L
        const val EMPTY_SETTLE_MS = 1_500L
        const val COVER_PX = 360
        const val GLOW_TINY = 6
        const val GLOW_PX = 96
    }
}
