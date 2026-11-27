package org.tegro.yamp.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaButtonReceiver
import dagger.hilt.android.EntryPointAccessors
import org.tegro.yamp.R

/**
 * «Строка» 4×1: the cover, the track over its progress, back, play, forward and — from 300 dp — the heart.
 * Play is an ordinary media button for [MediaButtonReceiver] (like a Bluetooth headset), so it works after the app's
 * process was closed: the player service starts and resumes the saved session. Its name stays as it was: the widgets
 * people have placed keep working after the update.
 */
class NowPlayingWidget : HubWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(DpSize(250.dp, 60.dp), DpSize(320.dp, 60.dp)))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        awaitWidgetReady(context)
        provideContent { LineWidget(rememberWidgetNow(context, id)) }
    }
}

@Composable
fun LineWidget(now: WidgetNow) {
    val context = LocalContext.current
    val wide = LocalSize.current.width >= 300.dp
    Row(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .cornerRadius(24.dp)
            .background(WidgetColors.ground)
            .padding(4.dp)
            .clickable(if (now.hasTrack) WidgetActions.openPlayer(context) else WidgetActions.invite(context, now.canWave)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(GlanceModifier.size(52.dp).cornerRadius(12.dp).background(WidgetColors.chip), contentAlignment = Alignment.Center) {
            if (now.cover != null) {
                Image(ImageProvider(now.cover), contentDescription = null, modifier = GlanceModifier.size(52.dp).cornerRadius(12.dp), contentScale = ContentScale.Crop)
            } else {
                Image(ImageProvider(R.drawable.ic_notification), contentDescription = null, modifier = GlanceModifier.size(24.dp))
            }
        }
        Spacer(GlanceModifier.width(10.dp))
        Column(modifier = GlanceModifier.defaultWeight()) {
            WidgetText(now.title ?: context.getString(if (now.canWave) R.string.widget_wave_title else R.string.widget_open_title), 15.sp, bold = true)
            WidgetText(now.artist ?: context.getString(if (now.canWave) R.string.widget_wave_hint else R.string.widget_open_hint), 12.sp, WidgetColors.textSoft)
            now.progress?.let {
                Spacer(GlanceModifier.height(4.dp))
                LinearProgressIndicator(progress = it, modifier = GlanceModifier.fillMaxWidth().height(3.dp), color = WidgetColors.text, backgroundColor = WidgetColors.track)
            }
        }
        Spacer(GlanceModifier.width(4.dp))
        if (!now.hasTrack) {
            WidgetRound(
                R.drawable.ic_widget_play,
                context.getString(if (now.canWave) R.string.widget_wave_start else R.string.widget_open_start),
                WidgetActions.invite(context, now.canWave), 52.dp, 24.dp, WidgetColors.knob, WidgetColors.onKnob
            )
            return@Row
        }
        if (wide && now.canLike) {
            WidgetRound(
                if (now.isLiked) R.drawable.ic_notification_liked else R.drawable.ic_notification_like,
                context.getString(R.string.widget_cd_like), WidgetActions.like(context, now.trackId), 44.dp, 22.dp, null, WidgetColors.heart(now.isLiked)
            )
        }
        WidgetRound(R.drawable.ic_widget_prev, context.getString(R.string.widget_cd_prev), WidgetActions.previous(context), 44.dp, 22.dp, WidgetColors.chip)
        WidgetRound(
            if (now.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
            context.getString(R.string.widget_cd_play_pause), WidgetActions.playPause(context), 52.dp, 24.dp, WidgetColors.knob, WidgetColors.onKnob
        )
        WidgetRound(R.drawable.ic_widget_next, context.getString(R.string.widget_cd_next), WidgetActions.next(context), 44.dp, 22.dp, WidgetColors.chip)
    }
}

// MediaButtonReceiver is marked unstable in media3; it is the documented receiver for media buttons
@OptIn(markerClass = [UnstableApi::class])
internal fun mediaButtonIntent(context: Context, keyCode: Int): Intent =
    Intent(Intent.ACTION_MEDIA_BUTTON)
        .setComponent(ComponentName(context, MediaButtonReceiver::class.java))
        .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))

internal fun skipIntent(context: Context, next: Boolean): Intent =
    Intent(context, WidgetSkipReceiver::class.java)
        .setAction(if (next) WidgetSkipReceiver.ACTION_NEXT else WidgetSkipReceiver.ACTION_PREVIOUS)

/** "Next" / "previous" from the widget: skips within the current session (nothing to skip without one). */
class WidgetSkipReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val controller = EntryPointAccessors.fromApplication(context.applicationContext, WidgetDependencies::class.java)
            .playbackController()
        if (controller.currentTrack.value == null) return
        when (intent.action) {
            ACTION_NEXT -> controller.playNext()
            ACTION_PREVIOUS -> controller.playPrev()
        }
    }

    companion object {
        const val ACTION_NEXT = "org.tegro.yamp.widget.NEXT"
        const val ACTION_PREVIOUS = "org.tegro.yamp.widget.PREVIOUS"
    }
}

class NowPlayingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NowPlayingWidget()

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        noteWidgetsChanged(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        noteWidgetsChanged(context)
    }
}
