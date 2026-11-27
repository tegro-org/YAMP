package org.tegro.yamp.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
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
import androidx.glance.layout.RowScope
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import org.tegro.yamp.R
import org.tegro.yamp.presentation.main.components.MainTab

/*
 * «Плеер» and «Обложка». Both are Responsive: Glance draws them once per listed size and hands that size out as
 * LocalSize, so each layout below is fitted by arithmetic to exactly those sizes (the dp budgets are in the comments).
 */

private val PlayerTallFrom = 150.dp

/**
 * The sizes the Плеер is drawn for. Glance picks the largest that fits the widget, so a size is listed wherever the
 * layout changes: the least the picker allows (180×110), the cover (280), the chips (150 high), the full row of buttons.
 */
internal val PlayerSizes = setOf(
    DpSize(180.dp, 110.dp), DpSize(200.dp, 130.dp), DpSize(280.dp, 130.dp),
    DpSize(200.dp, 160.dp), DpSize(280.dp, 160.dp), DpSize(320.dp, 180.dp)
)

/** The buttons the Плеер has room for: the thumbs beside the transport only where the row is wide enough. */
internal data class PlayerControls(val dislike: Boolean, val like: Boolean)

private val TransportRow = 136.dp
private val ThumbSide = 40.dp

/** [avail]: the width of the row of buttons. A thumb needs 40 dp beside the 136 dp of transport. Pure; unit-tested. */
internal fun playerControlsFor(avail: Dp, canDislike: Boolean, canLike: Boolean) = PlayerControls(
    dislike = canDislike && avail >= TransportRow + ThumbSide * 2,
    like = canLike && avail >= TransportRow + ThumbSide
)

/**
 * «Плеер» 4×2: the glow of the cover behind everything, the cover, the source, the title, the artist, the progress
 * (with its times where the widget is tall), and dislike, back, play, forward, like. Narrow, the cover gives way;
 * a thumb that does not fit gives way before the transport does. With nothing playing: the wave (or the app) to
 * start, and — where there is room — the app's pages a tap away.
 */
class PlayerWidget : HubWidget() {
    override val sizeMode = SizeMode.Responsive(PlayerSizes)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        awaitWidgetReady(context)
        provideContent { PlayerWidgetContent(rememberWidgetNow(context, id)) }
    }
}

class PlayerWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PlayerWidget()

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        noteWidgetsChanged(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        noteWidgetsChanged(context)
    }
}

@Composable
fun PlayerWidgetContent(now: WidgetNow) {
    val context = LocalContext.current
    val size = LocalSize.current
    Box(GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(28.dp).background(WidgetColors.ground)) {
        if (!now.hasTrack) {
            PlayerInvite(context, now, withChips = size.height >= PlayerTallFrom && now.hasAccount)
            return@Box
        }
        if (now.glow != null) {
            Image(ImageProvider(now.glow), contentDescription = null, modifier = GlanceModifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            Box(GlanceModifier.fillMaxSize().background(WidgetColors.shade)) {}
        }
        if (size.height >= PlayerTallFrom) PlayerTall(context, now, size) else PlayerCompact(context, now, size)
    }
}

/**
 * 110–130 dp high, 98–118 dp inside the padding: title 20, artist 15, progress 4, buttons 48 and the gaps, and from 125 dp
 * the source line (14). Width 180 holds the transport (136), 200 a heart too.
 */
@Composable
private fun PlayerCompact(context: Context, now: WidgetNow, size: DpSize) {
    val withCover = size.width >= 260.dp
    val coverSide = 72.dp
    val avail = size.width - 20.dp - if (withCover) coverSide + 10.dp else 0.dp
    Row(GlanceModifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (withCover) {
            PlayerCover(context, now, coverSide, 14.dp)
            Spacer(GlanceModifier.width(10.dp))
        }
        Column(GlanceModifier.defaultWeight().fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
            Column(GlanceModifier.fillMaxWidth().clickable(WidgetActions.openPlayer(context))) {
                if (size.height >= 125.dp) now.source?.let { WidgetText(widgetSourceLabel(context, it), 11.sp, WidgetColors.textSoft, bold = true) }
                WidgetText(now.title.orEmpty(), 16.sp, bold = true)
                WidgetText(now.artist.orEmpty(), 12.sp, WidgetColors.textSoft)
                now.progress?.let {
                    Spacer(GlanceModifier.height(4.dp))
                    PlayerProgress(it)
                }
            }
            Spacer(GlanceModifier.height(4.dp))
            PlayerButtons(context, now, playerControlsFor(avail, now.canDislike, now.canLike), play = 48.dp)
        }
    }
}

/**
 * 160–180 dp high: the cover and the text take what is left of the padding, a gap 8 and the buttons 56 — 64 dp at 160
 * (source, title, artist, progress), 84 at 180 (and the times). Narrower than 260 dp the padding is 12 so a heart fits.
 */
@Composable
private fun PlayerTall(context: Context, now: WidgetNow, size: DpSize) {
    val width = size.width
    val withCover = width >= 260.dp
    val pad = if (width < 260.dp) 12.dp else 16.dp
    val top = size.height - pad * 2 - 8.dp - 56.dp
    Column(GlanceModifier.fillMaxSize().padding(pad)) {
        Row(GlanceModifier.fillMaxWidth().height(top), verticalAlignment = Alignment.CenterVertically) {
            if (withCover) {
                PlayerCover(context, now, top, 18.dp)
                Spacer(GlanceModifier.width(16.dp))
            }
            Column(GlanceModifier.defaultWeight().clickable(WidgetActions.openPlayer(context)), verticalAlignment = Alignment.CenterVertically) {
                now.source?.let { WidgetText(widgetSourceLabel(context, it), 11.sp, WidgetColors.textSoft, bold = true) }
                WidgetText(now.title.orEmpty(), 18.sp, bold = true)
                WidgetText(now.artist.orEmpty(), 13.sp, WidgetColors.textSoft)
                now.progress?.let { p ->
                    Spacer(GlanceModifier.height(4.dp))
                    PlayerProgress(p)
                    if (top >= 80.dp) {
                        Row(GlanceModifier.fillMaxWidth()) {
                            WidgetText(widgetClock(now.positionMs), 11.sp, WidgetColors.textSoft, modifier = GlanceModifier.defaultWeight())
                            WidgetText("−" + widgetClock(now.durationMs - now.positionMs), 11.sp, WidgetColors.textSoft)
                        }
                    }
                }
            }
        }
        Spacer(GlanceModifier.height(8.dp))
        PlayerButtons(context, now, playerControlsFor(width - pad * 2, now.canDislike, now.canLike), play = 56.dp)
    }
}

@Composable
private fun PlayerProgress(progress: Float) {
    LinearProgressIndicator(progress = progress, modifier = GlanceModifier.fillMaxWidth().height(4.dp), color = WidgetColors.text, backgroundColor = WidgetColors.track)
}

@Composable
private fun PlayerCover(context: Context, now: WidgetNow, side: Dp, corner: Dp) {
    Box(
        GlanceModifier.size(side).cornerRadius(corner).background(WidgetColors.chip).clickable(WidgetActions.openPlayer(context)),
        contentAlignment = Alignment.Center
    ) {
        if (now.cover != null) {
            Image(ImageProvider(now.cover), contentDescription = now.title, modifier = GlanceModifier.size(side).cornerRadius(corner), contentScale = ContentScale.Crop)
        } else {
            Image(ImageProvider(R.drawable.ic_notification), contentDescription = null, modifier = GlanceModifier.size(32.dp))
        }
    }
}

/** Dislike, back, play, forward, like — the thumbs only where the track allows them and the row has room. */
@Composable
private fun PlayerButtons(context: Context, now: WidgetNow, controls: PlayerControls, play: Dp) {
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Spacer(GlanceModifier.defaultWeight())
        if (controls.dislike) {
            WidgetRound(R.drawable.ic_notification_dislike, context.getString(R.string.widget_cd_dislike), WidgetActions.dislike(context, now.trackId), ThumbSide, 20.dp, WidgetColors.chip)
            Spacer(GlanceModifier.defaultWeight())
        }
        WidgetRound(R.drawable.ic_widget_prev, context.getString(R.string.widget_cd_prev), WidgetActions.previous(context), 44.dp, 22.dp, WidgetColors.chip)
        Spacer(GlanceModifier.defaultWeight())
        WidgetRound(
            if (now.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
            context.getString(R.string.widget_cd_play_pause), WidgetActions.playPause(context), play, 26.dp, WidgetColors.knob, WidgetColors.onKnob
        )
        Spacer(GlanceModifier.defaultWeight())
        WidgetRound(R.drawable.ic_widget_next, context.getString(R.string.widget_cd_next), WidgetActions.next(context), 44.dp, 22.dp, WidgetColors.chip)
        Spacer(GlanceModifier.defaultWeight())
        if (controls.like) {
            WidgetRound(
                if (now.isLiked) R.drawable.ic_notification_liked else R.drawable.ic_notification_like,
                context.getString(R.string.widget_cd_like), WidgetActions.like(context, now.trackId), ThumbSide, 20.dp, WidgetColors.chip, WidgetColors.heart(now.isLiked)
            )
            Spacer(GlanceModifier.defaultWeight())
        }
    }
}

/**
 * Nothing plays: the wave to start — or, without a Yandex account, the app itself (a wave button would lead nowhere) —
 * and, where there is room, the pages Music, Radio and Search a tap away.
 */
@Composable
private fun PlayerInvite(context: Context, now: WidgetNow, withChips: Boolean) {
    val start = WidgetActions.invite(context, now.canWave)
    val title = context.getString(if (now.canWave) R.string.widget_wave_title else R.string.widget_open_title)
    val hint = context.getString(if (now.canWave) R.string.widget_wave_hint else R.string.widget_open_hint)
    val cd = context.getString(if (now.canWave) R.string.widget_wave_start else R.string.widget_open_start)
    val knob = if (withChips) 64.dp else 72.dp
    Column(GlanceModifier.fillMaxSize().padding(14.dp).clickable(start), verticalAlignment = Alignment.CenterVertically) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (now.canWave) {
                WidgetRound(R.drawable.ic_widget_play, cd, start, knob, 30.dp, WidgetColors.knob, WidgetColors.onKnob)
            } else {
                Box(GlanceModifier.size(knob).cornerRadius(knob / 2).background(WidgetColors.chip).semantics { contentDescription = cd }, contentAlignment = Alignment.Center) {
                    Image(ImageProvider(R.drawable.ic_notification), contentDescription = null, modifier = GlanceModifier.size(32.dp))
                }
            }
            Spacer(GlanceModifier.width(14.dp))
            Column(GlanceModifier.defaultWeight()) {
                WidgetText(title, 20.sp, bold = true)
                WidgetText(hint, 13.sp, WidgetColors.textSoft, lines = 2)
            }
        }
        if (withChips) {
            Spacer(GlanceModifier.height(12.dp))
            Row(GlanceModifier.fillMaxWidth()) {
                PageChip(context.getString(R.string.widget_tab_music), WidgetActions.open(context, MainTab.LIBRARY.route))
                Spacer(GlanceModifier.width(6.dp))
                PageChip(context.getString(R.string.widget_tab_radio), WidgetActions.open(context, MainTab.RADIO.route))
                Spacer(GlanceModifier.width(6.dp))
                PageChip(context.getString(R.string.widget_tab_search), WidgetActions.open(context, MainTab.SEARCH.route))
            }
        }
    }
}

@Composable
private fun RowScope.PageChip(label: String, action: Action) {
    Box(
        GlanceModifier.defaultWeight().height(44.dp).cornerRadius(22.dp).background(WidgetColors.chip).clickable(action),
        contentAlignment = Alignment.Center
    ) {
        WidgetText(label, 12.sp, bold = true)
    }
}

private fun widgetSourceLabel(context: Context, source: WidgetSource): String =
    context.getString(if (source == WidgetSource.RADIO) R.string.widget_source_radio else R.string.widget_source_wave)

private val CoverWideFrom = 150.dp

/**
 * «Обложка» 2×2: the cover over all of it, the title and the artist (from 150 dp) over a shade at its foot, back,
 * play, forward. The cover opens the player. With nothing playing: the wave (or the app) to start.
 */
class CoverWidget : HubWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(DpSize(110.dp, 110.dp), DpSize(170.dp, 170.dp)))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        awaitWidgetReady(context)
        provideContent { CoverWidgetContent(rememberWidgetNow(context, id)) }
    }
}

class CoverWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CoverWidget()

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        noteWidgetsChanged(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        noteWidgetsChanged(context)
    }
}

@Composable
fun CoverWidgetContent(now: WidgetNow) {
    val context = LocalContext.current
    val small = LocalSize.current.width < CoverWideFrom
    Box(GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(28.dp).background(WidgetColors.ground)) {
        if (!now.hasTrack) {
            val start = WidgetActions.invite(context, now.canWave)
            val cd = context.getString(if (now.canWave) R.string.widget_wave_start else R.string.widget_open_start)
            Column(
                GlanceModifier.fillMaxSize().clickable(start),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalAlignment = Alignment.CenterVertically
            ) {
                WidgetRound(R.drawable.ic_widget_play, cd, start, if (small) 64.dp else 88.dp, if (small) 28.dp else 36.dp, WidgetColors.knob, WidgetColors.onKnob)
                Spacer(GlanceModifier.height(10.dp))
                WidgetText(context.getString(if (now.canWave) R.string.widget_wave_title else R.string.widget_open_title), 15.sp, bold = true)
            }
            return@Box
        }
        if (now.cover != null) {
            Image(ImageProvider(now.cover), contentDescription = now.title, modifier = GlanceModifier.fillMaxSize().clickable(WidgetActions.openPlayer(context)), contentScale = ContentScale.Crop)
        } else {
            Box(GlanceModifier.fillMaxSize().clickable(WidgetActions.openPlayer(context)), contentAlignment = Alignment.Center) {
                Image(ImageProvider(R.drawable.ic_notification), contentDescription = now.title, modifier = GlanceModifier.size(48.dp))
            }
        }
        Column(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.Bottom) {
            // 110 dp wide: 32 + 44 + 32 — the three buttons just fit; from 170 dp they have their usual 44 / 52 / 44
            Column(GlanceModifier.fillMaxWidth().background(ImageProvider(R.drawable.widget_scrim)).padding(start = if (small) 1.dp else 12.dp, end = if (small) 1.dp else 12.dp, top = 24.dp, bottom = if (small) 6.dp else 10.dp)) {
                if (!small) {
                    WidgetText(now.title.orEmpty(), 15.sp, bold = true)
                    WidgetText(now.artist.orEmpty(), 12.sp, WidgetColors.textSoft)
                    Spacer(GlanceModifier.height(8.dp))
                }
                Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val side = if (small) 32.dp else 44.dp
                    WidgetRound(R.drawable.ic_widget_prev, context.getString(R.string.widget_cd_prev), WidgetActions.previous(context), side, 20.dp, WidgetColors.shade)
                    Spacer(GlanceModifier.defaultWeight())
                    WidgetRound(
                        if (now.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
                        context.getString(R.string.widget_cd_play_pause), WidgetActions.playPause(context), if (small) 44.dp else 52.dp, 24.dp, WidgetColors.knob, WidgetColors.onKnob
                    )
                    Spacer(GlanceModifier.defaultWeight())
                    WidgetRound(R.drawable.ic_widget_next, context.getString(R.string.widget_cd_next), WidgetActions.next(context), side, 20.dp, WidgetColors.shade)
                }
            }
        }
    }
}
