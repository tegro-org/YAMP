package org.tegro.yamp.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.action.actionSendBroadcast
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.size
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.media3.common.util.UnstableApi
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.tegro.yamp.MainActivity
import org.tegro.yamp.R
import org.tegro.yamp.data.account.SignedInAccounts
import org.tegro.yamp.data.player.PlaybackController
import org.tegro.yamp.data.player.PlaylistSource
import org.tegro.yamp.data.player.SessionButtons
import org.tegro.yamp.data.player.TrackReactions
import org.tegro.yamp.domain.media.Track
import org.tegro.yamp.domain.repository.AuthRepository
import org.tegro.yamp.presentation.common.artistNames
import org.tegro.yamp.presentation.common.isRadio
import org.tegro.yamp.presentation.hifi.HiFi

/** The source line of the Плеер widget: the wave and FM radio have one, the rest of the music none. */
enum class WidgetSource { WAVE, RADIO }

/**
 * What every widget shows: the track, its cover and a soft glow made of it, the state of play. The buttons
 * ([canLike], [canDislike]) are what the notification offers for this track, so a widget never disagrees with it.
 */
data class WidgetNow(
    val title: String?,
    val artist: String?,
    val isPlaying: Boolean,
    val isLiked: Boolean,
    val isRadio: Boolean,
    val canLike: Boolean,
    val canDislike: Boolean,
    /** 0..1; null for a station, a track of unknown length, or nothing. */
    val progress: Float?,
    val positionMs: Long,
    val durationMs: Long,
    val source: WidgetSource?,
    val cover: Bitmap?,
    val glow: Bitmap?,
    /** A Yandex account is signed in: with nothing playing the widget invites to the wave, else to the app. */
    val canWave: Boolean = true,
    /** Some account is signed in: without one every tab of the app leads to the sign-in, so no chips are offered. */
    val hasAccount: Boolean = true,
    /** The track this state shows (its ref), so a press on a heart or thumb can be checked against the playing one. */
    val trackId: String? = null
) {
    val hasTrack: Boolean get() = title != null
}

/**
 * A widget of ours: when its draw to the launcher fails, the hub is told, so the widget counts as behind and is asked
 * again (a composition alone does not say that the launcher got the result).
 */
abstract class HubWidget : GlanceAppWidget() {
    override fun onCompositionError(context: Context, glanceId: GlanceId, appWidgetId: Int, throwable: Throwable) {
        EntryPointAccessors.fromApplication(context.applicationContext, WidgetDependencies::class.java).widgetHub().markFailed(appWidgetId)
        super.onCompositionError(context, glanceId, appWidgetId, throwable)
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetDependencies {
    fun playbackController(): PlaybackController
    fun reactions(): TrackReactions
    fun authRepository(): AuthRepository
    fun signedInAccounts(): SignedInAccounts
    fun widgetHub(): WidgetStateHub
}

/** The widgets' clock: moved every second while music plays (see [NowPlayingWidgetUpdater]), never faster. */
object WidgetClock {
    private val _tick = MutableStateFlow(0L)
    val tick: StateFlow<Long> = _tick.asStateFlow()
    fun advance() {
        _tick.value = _tick.value + 1
    }
}

/** Whether the heart of [track] is filled: a liked track, or a favourite station (as [TrackReactions.isLiked]). Pure. */
internal fun widgetLiked(track: Track, likedIds: Set<String>, stationIds: Set<String>): Boolean =
    if (track.isRadio) track.ref.id.removePrefix("radio_") in stationIds else track.ref.id in likedIds

/** The display state of a widget for what the player holds. Pure: the glue below only feeds it. */
@OptIn(UnstableApi::class)
internal fun widgetNowOf(
    track: Track?,
    playing: Boolean,
    playlistSource: PlaylistSource,
    likedIds: Set<String>,
    stationIds: Set<String>,
    positionMs: Long,
    liveLabel: String,
    canWave: Boolean,
    cover: Bitmap? = null,
    glow: Bitmap? = null,
    hasAccount: Boolean = true
): WidgetNow {
    if (track == null) {
        return WidgetNow(null, null, playing, false, false, false, false, null, 0, 0, null, null, null, canWave, hasAccount)
    }
    val kinds = SessionButtons.kindsFor(track, widgetLiked(track, likedIds, stationIds))
    val duration = track.durationMs ?: 0L
    return WidgetNow(
        title = track.title.ifBlank { "—" },
        artist = if (track.isRadio) liveLabel else track.artistNames,
        isPlaying = playing,
        isLiked = SessionButtons.Kind.UNLIKE in kinds,
        isRadio = track.isRadio,
        canLike = SessionButtons.Kind.LIKE in kinds || SessionButtons.Kind.UNLIKE in kinds,
        canDislike = SessionButtons.Kind.DISLIKE in kinds,
        progress = if (track.isRadio || duration <= 0) null else (positionMs.toFloat() / duration).coerceIn(0f, 1f),
        positionMs = positionMs,
        durationMs = duration,
        source = when {
            track.isRadio -> WidgetSource.RADIO
            playlistSource == PlaylistSource.VIBE -> WidgetSource.WAVE
            else -> null
        },
        cover = cover,
        glow = glow,
        canWave = canWave,
        hasAccount = hasAccount,
        trackId = track.ref.value
    )
}

/**
 * Waits for the saved session and the cached likes to come back (a short while at most), so a redraw — or a heart
 * pressed — in a process that has just started does not see "nothing playing" and "nothing liked".
 */
internal suspend fun awaitWidgetSession(context: Context, waitMs: Long = SESSION_WAIT_MS) {
    val deps = EntryPointAccessors.fromApplication(context.applicationContext, WidgetDependencies::class.java)
    withTimeoutOrNull(waitMs) {
        deps.playbackController().awaitRestoredSession()
        deps.reactions().loadCachedLikes()
    }
}

/** Before a widget's first composition: the restored session, the likes and the hub, for a short while at most. */
internal suspend fun awaitWidgetReady(context: Context) {
    val deps = EntryPointAccessors.fromApplication(context.applicationContext, WidgetDependencies::class.java)
    withTimeoutOrNull(READY_WAIT_MS) { deps.widgetHub().awaitSettled() }
}

private const val SESSION_WAIT_MS = 2_000L
private const val REACTION_WAIT_MS = 4_000L
private const val READY_WAIT_MS = 4_000L

/**
 * What the widget draws: the one state of [WidgetStateHub], followed as it changes. Every widget reads the same value,
 * and the composition notes what it drew, so a widget left behind can be found and redrawn.
 */
@Composable
internal fun rememberWidgetNow(context: Context, id: GlanceId): WidgetNow {
    val hub = remember { EntryPointAccessors.fromApplication(context.applicationContext, WidgetDependencies::class.java).widgetHub() }
    val appWidgetId = remember(id) { GlanceAppWidgetManager(context).getAppWidgetId(id) }
    val now by hub.now.collectAsState()
    SideEffect { hub.markRendered(appWidgetId, now) }
    return now
}

/** The widgets' colours, taken from the app's tokens: its dark ground, its white and its heart. */
internal object WidgetColors {
    val ground = ColorProvider(HiFi.SheetFill)
    val shade = ColorProvider(HiFi.WidgetShade)
    val text = ColorProvider(HiFi.TextPrimary)
    val textSoft = ColorProvider(HiFi.TextSecondary)
    val chip = ColorProvider(HiFi.GlassFill)
    val knob = ColorProvider(HiFi.OnMetal)
    val onKnob = HiFi.Ground
    val love = HiFi.Love
    val track = ColorProvider(HiFi.SheetHandle)

    fun heart(liked: Boolean): Color = if (liked) love else HiFi.TextPrimary
}

/** A round button with an icon: [size] to touch (44 dp where the widget has room), [iconSize] to draw. */
@Composable
internal fun WidgetRound(
    icon: Int,
    description: String,
    action: Action,
    size: Dp,
    iconSize: Dp,
    fill: ColorProvider?,
    tint: Color = HiFi.TextPrimary
) {
    val base = GlanceModifier.size(size).cornerRadius(size / 2).clickable(action).semantics { contentDescription = description }
    Box(modifier = if (fill != null) base.background(fill) else base, contentAlignment = Alignment.Center) {
        Image(
            ImageProvider(icon),
            contentDescription = null,
            modifier = GlanceModifier.size(iconSize),
            colorFilter = ColorFilter.tint(ColorProvider(tint))
        )
    }
}

@Composable
internal fun WidgetText(
    text: String,
    size: TextUnit,
    color: ColorProvider = WidgetColors.text,
    bold: Boolean = false,
    modifier: GlanceModifier = GlanceModifier,
    lines: Int = 1
) {
    Text(text, modifier = modifier, maxLines = lines, style = TextStyle(color = color, fontSize = size, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal))
}

/** The actions every widget offers. */
internal object WidgetActions {
    private fun appIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun openPlayer(context: Context): Action = actionStartActivity(appIntent(context).putExtra(MainActivity.EXTRA_OPEN_PLAYER, true))

    /** Nothing plays and a Yandex account is signed in: the app opens and the wave starts (as the Home knob does). */
    fun startMusic(context: Context): Action = actionStartActivity(appIntent(context).putExtra(MainActivity.EXTRA_START_MUSIC, true))

    fun openApp(context: Context): Action = actionStartActivity(appIntent(context))

    /** The empty widget's start: the wave where there is a Yandex account, else the app itself. */
    fun invite(context: Context, canWave: Boolean): Action = if (canWave) startMusic(context) else openApp(context)

    fun open(context: Context, tab: String): Action = actionStartActivity(appIntent(context).putExtra(MainActivity.EXTRA_OPEN_TAB, tab))

    fun playPause(context: Context): Action = actionSendBroadcast(mediaButtonIntent(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
    fun next(context: Context): Action = actionSendBroadcast(skipIntent(context, next = true))
    fun previous(context: Context): Action = actionSendBroadcast(skipIntent(context, next = false))
    fun like(context: Context, trackId: String?): Action = actionSendBroadcast(reactionIntent(context, WidgetActionReceiver.ACTION_LIKE, trackId))
    fun dislike(context: Context, trackId: String?): Action = actionSendBroadcast(reactionIntent(context, WidgetActionReceiver.ACTION_DISLIKE, trackId))

    private fun reactionIntent(context: Context, action: String, trackId: String?): Intent =
        Intent(context, WidgetActionReceiver::class.java).setAction(action).putExtra(WidgetActionReceiver.EXTRA_TRACK, trackId)
}

/** What a press on a widget's heart or thumb does, once the buttons allowed for the track are known. */
internal enum class WidgetReaction { TOGGLE_LIKE, DISLIKE }

/**
 * The reaction an action asks for, or null when it is unknown or the track allows none (a stale widget must not
 * like a Jellyfin track). Pure; unit-tested.
 */
@OptIn(UnstableApi::class)
internal fun widgetReactionOf(action: String?, allowed: List<SessionButtons.Kind>): WidgetReaction? = when {
    action == WidgetActionReceiver.ACTION_LIKE &&
        (SessionButtons.Kind.LIKE in allowed || SessionButtons.Kind.UNLIKE in allowed) -> WidgetReaction.TOGGLE_LIKE
    action == WidgetActionReceiver.ACTION_DISLIKE && SessionButtons.Kind.DISLIKE in allowed -> WidgetReaction.DISLIKE
    else -> null
}

/**
 * Like and dislike from a widget: the same as anywhere in the app (see [TrackReactions]). In a process that has just
 * started there is no track and no likes yet, so the press waits (briefly) for the restored session and the cached likes.
 */
class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        val action = intent.action
        val displayed = intent.getStringExtra(EXTRA_TRACK)
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            try {
                awaitWidgetSession(app, REACTION_WAIT_MS)
                react(EntryPointAccessors.fromApplication(app, WidgetDependencies::class.java), action, displayed)
            } finally {
                pending.finish()
            }
        }
    }

    @OptIn(UnstableApi::class)
    private fun react(deps: WidgetDependencies, action: String?, displayed: String?) {
        val track = deps.playbackController().currentTrack.value ?: return
        if (!widgetReactionTargets(displayed, track.ref.value)) return
        val reactions = deps.reactions()
        when (widgetReactionOf(action, SessionButtons.kindsFor(track, reactions.isLiked(track)))) {
            WidgetReaction.TOGGLE_LIKE -> reactions.toggleLike(track)
            WidgetReaction.DISLIKE -> reactions.dislike(track)
            null -> Unit
        }
    }

    companion object {
        const val ACTION_LIKE = "org.tegro.yamp.widget.LIKE"
        const val ACTION_DISLIKE = "org.tegro.yamp.widget.DISLIKE"

        /** The ref of the track the widget showed when it was pressed. */
        const val EXTRA_TRACK = "org.tegro.yamp.widget.TRACK"
    }
}

/** "1:22" of a position; a negative one is "0:00". Pure. */
internal fun widgetClock(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

/** Tells the hub that a widget was placed or removed: the covers and the position are wanted only while one is in sight. */
internal fun noteWidgetsChanged(context: Context) =
    EntryPointAccessors.fromApplication(context.applicationContext, WidgetDependencies::class.java).widgetHub().refreshVisibility()
