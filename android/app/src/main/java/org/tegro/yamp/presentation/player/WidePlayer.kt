package org.tegro.yamp.presentation.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.HeartBroken
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.Icon
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.delay
import org.tegro.yamp.R
import org.tegro.yamp.domain.media.Track
import org.tegro.yamp.domain.model.WaveTuning
import org.tegro.yamp.presentation.account.AccountMark
import org.tegro.yamp.presentation.account.AccountTag
import org.tegro.yamp.presentation.car.CAR_MAX_FONT_SCALE
import org.tegro.yamp.presentation.car.CarSwipeThreshold
import org.tegro.yamp.presentation.car.LocalCarClock
import org.tegro.yamp.presentation.car.carSwipeStep
import org.tegro.yamp.presentation.common.artistNames
import org.tegro.yamp.presentation.common.coverUri
import org.tegro.yamp.presentation.common.id
import org.tegro.yamp.presentation.common.isRadio
import org.tegro.yamp.presentation.common.supportsLikes
import org.tegro.yamp.presentation.hifi.HiFi
import org.tegro.yamp.presentation.hifi.HiFiKnob
import org.tegro.yamp.presentation.hifi.HiFiLiveLine
import org.tegro.yamp.presentation.hifi.HiFiMotion
import org.tegro.yamp.presentation.hifi.LocalArtworkTint
import org.tegro.yamp.presentation.hifi.LocalHiFiLite
import org.tegro.yamp.presentation.hifi.PlayPauseGlyph
import org.tegro.yamp.presentation.hifi.RingMode
import org.tegro.yamp.presentation.hifi.SpectrumRing
import org.tegro.yamp.presentation.hifi.hifiAnodized
import org.tegro.yamp.presentation.hifi.hifiGlass
import org.tegro.yamp.presentation.hifi.hifiSoftShadow
import org.tegro.yamp.presentation.main.components.WavePill
import org.tegro.yamp.presentation.main.components.WaveTuneSheet
import org.tegro.yamp.presentation.main.components.waveTuningSummary
import org.tegro.yamp.utils.CoverSize
import org.tegro.yamp.utils.browserUserAgent
import org.tegro.yamp.utils.cover
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * The sizes of the wide player in its design's units: a phone on its side (canvas `pl-land-*`, 915×412) or the
 * Car layout (canvas `plc-*`, 1920×720). The screen is fitted by one scale, [WideSpec.scaleFor].
 */
internal data class WideSpec(
    val baseW: Float,
    val baseH: Float,
    val pad: Float,
    val top: Float,
    val driverW: Float,
    val gap: Float,
    val bar: Float,
    val barFont: Float,
    val barGap: Float,
    val like: Float,
    val likeIcon: Float,
    val caps: Float,
    val title: Float,
    val titleLine: Float,
    val artist: Float,
    val timelineUnit: Float,
    val timeSize: Float,
    val skip: Float,
    val skipIcon: Float,
    val play: Float,
    /** The spectrum ring around Play; 0 — a plain knob (the phone). */
    val ring: Float,
    val transportGap: Float,
    val coverMax: Float,
    val card: Float,
    val cardTitle: Float,
    val cardArtist: Float,
    val nextCaps: Float,
    val oneHeight: Float,
    /** The car layout: the clock in the far corner, a horizontal swipe switches tracks, the screen stays on. */
    val car: Boolean
) {
    /** One scale for the whole layout: whichever side runs out first. Pure; unit-tested. */
    fun scaleFor(widthDp: Float, heightDp: Float): Float = minOf(widthDp / baseW, heightDp / baseH).coerceIn(0.4f, 2f)

    companion object {
        val Phone = WideSpec(
            baseW = 915f, baseH = 412f, pad = 24f, top = 12f, driverW = 520f, gap = 30f,
            bar = 44f, barFont = 16f, barGap = 8f, like = 44f, likeIcon = 20f,
            caps = 11f, title = 24f, titleLine = 30f, artist = 15f, timelineUnit = 1f, timeSize = 13f,
            skip = 68f, skipIcon = 30f, play = 98f, ring = 0f, transportGap = 40f,
            coverMax = 262f, card = 92f, cardTitle = 11f, cardArtist = 10f, nextCaps = 10f, oneHeight = 63f, car = false
        )
        val Car = WideSpec(
            baseW = 1920f, baseH = 720f, pad = 48f, top = 26f, driverW = 820f, gap = 50f,
            bar = 60f, barFont = 20f, barGap = 14f, like = 80f, likeIcon = 36f,
            caps = 17f, title = 46f, titleLine = 54f, artist = 26f, timelineUnit = 1.4f, timeSize = 22f,
            skip = 124f, skipIcon = 48f, play = 212f, ring = 290f, transportGap = 40f,
            coverMax = 458f, card = 150f, cardTitle = 18f, cardArtist = 15f, nextCaps = 15f, oneHeight = 108f, car = true
        )
    }
}

/** The top row's (⋮ · queue) smallest height: a touch target, whatever the layout's scale. */
internal val MIN_TOP_ROW = 48.dp

/**
 * The top row's visual height per sp of its label — the portrait player's pill proportions (17 sp in a 44 dp pill).
 * The visuals hug the label; the 48 dp touch target comes from [minimumInteractiveComponentSize], not from a fatter
 * pill (user, 2026-11-18: a big circle and pill around small text looked odd).
 */
private const val TOP_ROW_PER_SP = 44f / 17f

/** The top row's smallest visual height (a 14 sp label still gets a readable pill). */
private val MIN_TOP_ROW_VISUAL = 36.dp

/** The gap between the three next-track cards, in layout units. */
internal const val NEXT_CARD_GAP = 20f

/** The clock's inset from the minimum right padding and from the top padding, in layout units (canvas `plWideLayout`). */
internal const val CLOCK_INSET = 20f

/**
 * Where everything goes in a wide player of [widthU] × [heightU] layout units (canvas `plWideLayout()`, approved
 * 2026-11-18), measured from the controls' side (mirrored by the caller for the right-hand wheel):
 * - the cover + next block spans the full height: from [WideSpec.top] (the ⋮ · queue row's top padding) down to the
 *   same margin at the bottom; the cover is square and takes what the next block leaves;
 * - the far side keeps at least [WideSpec.pad] (the progress bar's edge padding) as its padding;
 * - a taller screen (w/h < [TALLER_RATIO]): the controls take all that is left and the block ends at that minimum padding;
 * - a wider screen: the controls stop at their share of the width ([WideSpec.driverW] of [WideSpec.baseW]) and the
 *   block is centred in the space beside them (if that share leaves spare width);
 * - the clock has one fixed place on every screen: [CLOCK_INSET] inside the minimum right padding and the top padding —
 *   inside the cover's corner when the cover reaches that padding (taller screens), wherever it falls otherwise.
 * Pure; unit-tested.
 */
internal data class WideGeometry(
    val controlsX: Float,
    val controlsW: Float,
    val coverX: Float,
    val coverTop: Float,
    val cover: Float,
    val card: Float,
    /** The clock's offsets from the far edge and from the top. */
    val clockEnd: Float,
    val clockTop: Float
)

/**
 * Below this width/height the wide player counts as "taller" (user, 2026-11-19): after a full-height cover block the
 * controls would keep only about a third of the width, so they take all that is left instead of a capped share.
 * 16:10 (1.6) is taller, 16:9 (1.78) is wide. Layout units keep the screen's aspect ratio, so the ratio is the same.
 */
internal const val TALLER_RATIO = 1.66f

/** The gap between the cover and the next block, in layout units. */
internal const val NEXT_BLOCK_GAP = 22f

/** What the three cards add besides their own square: the "Далее" line, its gap, the title and artist lines, air. */
internal fun nextCardsExtra(spec: WideSpec): Float = spec.nextCaps * 1.4f + 10f + 6f + spec.cardTitle * 1.3f + spec.cardArtist * 1.3f + 8f

internal fun wideGeometryOf(spec: WideSpec, widthU: Float, heightU: Float, nextThree: Boolean, isRadio: Boolean): WideGeometry {
    val edge = spec.pad
    val gapMin = spec.gap
    val minControls = spec.driverW * 0.68f
    val avail = heightU - 2 * spec.top
    val byHeight = when {
        isRadio -> avail
        // cover + gap + extra + card = avail, card = (cover − 2 · cardGap) / 3
        nextThree -> (avail - NEXT_BLOCK_GAP - nextCardsExtra(spec) + 2 * NEXT_CARD_GAP / 3f) * 3f / 4f
        else -> avail - NEXT_BLOCK_GAP - spec.oneHeight
    }
    val cover = minOf(byHeight, widthU - 2 * edge - gapMin - minControls).coerceAtLeast(120f)
    val card = if (nextThree && !isRadio) ((cover - 2 * NEXT_CARD_GAP) / 3f).coerceAtLeast(0f) else 0f
    val cap = spec.driverW / spec.baseW * widthU
    val fit = widthU - 2 * edge - gapMin - cover
    // Taller screens (w/h below TALLER_RATIO, e.g. 16:10, 4:3, a foldable's inner screen): the controls take what is
    // left and the block ends at the minimum padding. Wider ones (16:9 and up): the controls stop at their share and
    // the block is centred beside them — when the spare is worth it (two minimum gaps or more; less goes to the controls)
    val centred = widthU / heightU >= TALLER_RATIO && fit - cap >= 2 * gapMin
    val controlsW = if (centred) cap else fit
    val areaStart = edge + controlsW
    val areaEnd = widthU - edge
    val coverX = if (centred) (areaStart + areaEnd - cover) / 2f else areaEnd - cover
    // A near-square screen (a foldable's inner screen on its side) lets the width stop the cover before the height:
    // the block is then centred vertically instead of leaving all the spare height at the bottom
    val blockH = cover + when {
        isRadio -> 0f
        nextThree -> NEXT_BLOCK_GAP + nextCardsExtra(spec) + card
        else -> NEXT_BLOCK_GAP + spec.oneHeight
    }
    val coverTop = if (cover < byHeight - 0.5f) ((heightU - blockH) / 2f).coerceAtLeast(spec.top) else spec.top
    return WideGeometry(
        controlsX = edge, controlsW = controlsW, coverX = coverX, coverTop = coverTop, cover = cover, card = card,
        clockEnd = edge + CLOCK_INSET, clockTop = spec.top + CLOCK_INSET
    )
}

/**
 * The wide player (canvas `pl-land-*` and the Car layout `plc-*`, approved 2026-11-18). The wheel side: one top row —
 * the queue dropdown → "Tune the wave" → search; lower down the labels centred between dislike / like, the progress
 * with times and Prev · Play · Next (the car: bigger, with the ring around Play). The far side: the cover centred
 * and the next track(s) under it (one row or three cards); the car shows the clock in the corner. No buttons bar.
 * Everything mirrors for the right-hand wheel.
 */
// coverGestures belongs to the cover, not to the whole screen
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod", "ModifierParameter")
@Composable
internal fun WidePlayer(
    spec: WideSpec,
    track: Track,
    isPlaying: Boolean,
    isLoading: Boolean,
    isLiked: Boolean,
    progress: () -> Long,
    durationMs: Long,
    caption: String,
    upcoming: List<Track>,
    upcomingLoading: Boolean,
    nextThree: Boolean,
    controlsOnLeft: Boolean,
    likeButtons: Boolean,
    queueName: String,
    queueAccount: AccountTag?,
    onQueues: () -> Unit,
    onSearch: () -> Unit,
    onPlayPause: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onLike: () -> Unit,
    onDislike: () -> Unit,
    onSeek: (Long) -> Unit,
    onUpcomingClick: (Track) -> Unit,
    onOpenQueue: () -> Unit,
    onNextBounds: (Rect) -> Unit,
    coverGestures: Modifier = Modifier,
    coverOverlay: @Composable BoxScope.() -> Unit = {},
    showClock: Boolean = true,
    waveActive: Boolean = false,
    waveTuning: WaveTuning = WaveTuning(),
    trackWaveSeed: Track? = null,
    onWaveTuningChange: (WaveTuning) -> Unit = {},
    onTrackWave: (() -> Unit)? = null,
    onBackToMyWave: () -> Unit = {},
    initialTuneOpen: Boolean = false,
    sleepTimer: org.tegro.yamp.data.player.SleepTimerState? = null,
    onSleepTimer: (Int) -> Unit = {},
    onSleepAtEndOfTrack: () -> Unit = {},
    onCancelSleepTimer: () -> Unit = {},
    onAddToPlaylist: () -> Unit = {},
    onQuality: () -> Unit = {},
    /** The ⋮ menu's Dislike (likes by gestures): with Undo, as the cover's hold. */
    onDislikeWithUndo: () -> Unit = onDislike,
    /** Opens with the ⋮ menu down (screenshots). */
    initialMoreOpen: Boolean = false,
    /** ⋮ → «Музыка» (the car layout, gap (f)). */
    onMusic: () -> Unit = {}
) {
    var tuneOpen by rememberSaveable { mutableStateOf(initialTuneOpen) }
    if (tuneOpen) androidx.activity.compose.BackHandler { tuneOpen = false }
    if (spec.car) {
        val view = androidx.compose.ui.platform.LocalView.current
        DisposableEffect(Unit) {
            view.keepScreenOn = true
            onDispose { view.keepScreenOn = false }
        }
    }
    val latestPrev by rememberUpdatedState(onPrev)
    val latestNext by rememberUpdatedState(onNext)
    val isRadio = track.isRadio
    val ringMode = when {
        isLoading -> RingMode.Loading
        isPlaying -> RingMode.Playing
        else -> RingMode.Paused
    }
    val fraction = { if (durationMs > 0 && !isRadio) progress().toFloat() / durationMs else 0f }

    // The car's type is already sized for a glance: Android's "font size" does not grow it
    val systemDensity = LocalDensity.current
    val density = remember(systemDensity, spec.car) {
        if (!spec.car) systemDensity else androidx.compose.ui.unit.Density(systemDensity.density, systemDensity.fontScale.coerceAtMost(CAR_MAX_FONT_SCALE))
    }
    CompositionLocalProvider(LocalDensity provides density) {
    Box(
        Modifier
            .fillMaxSize()
            .then(
                if (!spec.car) Modifier else Modifier.pointerInput(Unit) {
                    // One track per swipe: the distance adds up over the whole gesture and counts once the finger lifts
                    var travelled = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { travelled = 0f },
                        onDragEnd = {
                            when (carSwipeStep(travelled, CarSwipeThreshold.toPx())) {
                                1 -> latestPrev()
                                -1 -> latestNext()
                            }
                            travelled = 0f
                        },
                        onDragCancel = { travelled = 0f }
                    ) { _, dragAmount -> travelled += dragAmount }
                }
            )
    ) {
        // Darken the wheel side so the text reads at a glance — over the whole screen, behind the status bar too,
        // like the portrait player's backdrop (no band under the status bar)
        Box(
            Modifier
                .fillMaxSize()
                .drawWithCache {
                    val shade = Brush.horizontalGradient(
                        0f to HiFi.Ground.copy(alpha = 0.88f), 0.42f to HiFi.Ground.copy(alpha = 0.64f), 0.78f to Color.Transparent,
                        startX = if (controlsOnLeft) 0f else size.width,
                        endX = if (controlsOnLeft) size.width else 0f
                    )
                    onDrawBehind { drawRect(shade) }
                }
        )
    BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding()) {
        val s = spec.scaleFor(maxWidth.value, maxHeight.value)
        fun u(v: Float): Dp = (v * s).dp
        fun t(v: Float): TextUnit = (v * s).sp

        // Where everything goes (canvas `plWideLayout()`), in layout units from the controls' side; mirrored below
        val g = wideGeometryOf(spec, maxWidth.value / s, maxHeight.value / s, nextThree, isRadio)
        val widthU = maxWidth.value / s
        fun xOf(start: Float, width: Float) = if (controlsOnLeft) start else widthU - start - width

        // The top row (user, 2026-11-18): only ⋮ and the queue dropdown; ⋮ at the outer edge — `⋮ · queue ▾` on the
        // left, `queue ▾ · ⋮` on the right. Everything else is in ⋮.
        // The row scales with the layout, like the transport below it, but never below a 48 dp touch target: the car
        // spec on a phone in landscape ("Large controls" on a 400 dp tall screen) would otherwise shrink it to ~28 dp
        // while the labels, set in sp, stay large (user, 2026-11-18). Text and icons follow the row's real height.
        // The queue name never outgrows the artist line under the title (user, 2026-11-18): it takes the artist's
        // size, which follows the layout's scale (the smaller of the width and height factors), not the 48 dp floor.
        // The pill and ⋮ hug that label (portrait proportions); their touch targets stay ≥ 48 dp invisibly.
        val barFontSp = t(spec.artist)
        val barH = maxOf(minOf(u(spec.bar), (barFontSp.value * TOP_ROW_PER_SP).dp), MIN_TOP_ROW_VISUAL)
        val topRow: @Composable () -> Unit = {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(u(spec.barGap), if (controlsOnLeft) Alignment.Start else Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val more: @Composable () -> Unit = {
                    PlayerMoreButton(
                        items = playerMenuItems(
                            likeGestures = !likeButtons, supportsLikes = track.supportsLikes, isRadio = isRadio,
                            waveActive = waveActive, canStartRadio = onTrackWave != null && track.supportsLikes, trackWave = trackWaveSeed != null,
                            music = spec.car
                        ),
                        size = barH,
                        itemHeight = if (spec.car) maxOf(u(64f), MIN_TOP_ROW) else MIN_TOP_ROW,
                        fontSize = if (spec.car) maxOf(barFontSp.value, 16f).sp else 16.sp,
                        isLiked = isLiked,
                        waveTuning = waveTuning,
                        sleepTimer = sleepTimer,
                        onTune = { tuneOpen = true },
                        onStartRadio = { onTrackWave?.invoke() },
                        onBackToMyWave = onBackToMyWave,
                        onSearch = onSearch,
                        onSleepTimer = onSleepTimer,
                        onSleepAtEndOfTrack = onSleepAtEndOfTrack,
                        onCancelSleepTimer = onCancelSleepTimer,
                        onAddToPlaylist = onAddToPlaylist,
                        onQuality = onQuality,
                        onLike = onLike,
                        onDislike = onDislikeWithUndo,
                        initialOpen = initialMoreOpen,
                        onMusic = onMusic
                    )
                }
                val dropdown: @Composable () -> Unit = {
                    QueueDropdown(queueName, queueAccount, height = barH, fontSize = barFontSp, onClick = onQueues, modifier = Modifier.weight(1f, fill = false))
                }
                if (controlsOnLeft) { more(); dropdown() } else { dropdown(); more() }
            }
        }

        val driver: @Composable () -> Unit = {
            Column(Modifier.padding(start = u(xOf(g.controlsX, g.controlsW)), top = u(spec.top)).width(u(g.controlsW)).fillMaxHeight()) {
                topRow()
                // The block (labels, progress, transport) is centred vertically between the top row and the bottom
                // padding — on near-square screens (the Fold inner screen) it used to sink to the bottom (user, 2026-11-18)
                Spacer(Modifier.weight(1f))
                // The labels centred between dislike and like (the buttons keep their place when hidden)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val likes = likeButtons && track.supportsLikes
                    // Never below a 48 dp touch target (large controls on a phone in landscape scale them down)
                    val likeSize = maxOf(u(spec.like), MIN_TOP_ROW)
                    if (likes) {
                        GlassRound(likeSize, stringResource(R.string.cd_ne_nravitsya), onDislike) {
                            Icon(Icons.Rounded.HeartBroken, contentDescription = null, tint = Color.White.copy(alpha = 0.88f), modifier = Modifier.size(u(spec.likeIcon)))
                        }
                    } else Spacer(Modifier.size(likeSize))
                    WideLabels(track, caption, s, spec, Modifier.weight(1f).padding(horizontal = u(16f)))
                    if (likes) {
                        GlassRound(likeSize, stringResource(R.string.cd_nravitsya), onLike) {
                            Icon(
                                if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                contentDescription = null,
                                tint = if (isLiked) HiFi.Love else Color.White.copy(alpha = 0.88f),
                                modifier = Modifier.size(u(spec.likeIcon))
                            )
                        }
                    } else Spacer(Modifier.size(likeSize))
                }
                Spacer(Modifier.height(u(14f)))
                if (isRadio) HiFiLiveLine(Modifier.fillMaxWidth(), unit = u(spec.timelineUnit))
                else PlayerTimeline(progress, durationMs, isPlaying, onSeek, unit = u(spec.timelineUnit), timeSize = t(spec.timeSize))
                Spacer(Modifier.height(u(8f)))
                // Prev · Play (the car: in the ring) · Next — scaled together to fit a narrow column, so Next is never
                // squeezed smaller than Prev (the Fold inner screen in landscape, user 2026-11-18)
                val ringU = if (spec.ring > 0f) spec.ring else spec.play
                val tk = (g.controlsW / (2 * spec.skip + ringU + 2 * spec.transportGap)).coerceAtMost(1f)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(u(spec.transportGap * tk), Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HiFiKnob(onClick = onPrev, contentDescription = stringResource(R.string.cd_predyduschiy_trek), size = u(spec.skip * tk)) {
                        Icon(Icons.Rounded.SkipPrevious, contentDescription = null, tint = HiFi.OnMetal, modifier = Modifier.size(u(spec.skipIcon * tk)))
                    }
                    val ringSize = ringU * tk
                    Box(Modifier.size(u(ringSize)), contentAlignment = Alignment.Center) {
                        if (spec.ring > 0f) {
                            SpectrumRing(
                                mode = ringMode,
                                progress = fraction,
                                bars = if (LocalHiFiLite.current) 60 else 90,
                                innerRadius = u((spec.play / 2f + spec.play * 0.04f) * tk),
                                maxLength = u(((spec.ring - spec.play) / 2f - spec.play * 0.06f) * tk),
                                barWidth = u(3f),
                                modifier = Modifier.size(u(ringSize))
                            )
                        }
                        HiFiKnob(onClick = onPlayPause, contentDescription = stringResource(R.string.cd_vosproizvedenie_ili_pauza), size = u(spec.play * tk)) {
                            PlayPauseGlyph(isPlaying = isPlaying, size = u(spec.play * 0.28f * tk))
                        }
                    }
                    val nextLook = LocalPlaybackAux.current.next()
                    NextStateDecor(nextLook, size = u(spec.skip * tk)) {
                        HiFiKnob(
                            onClick = onNext,
                            contentDescription = if (nextLook == NextLook.READY) stringResource(R.string.cd_sleduyuschiy_trek) else nextLookLabel(nextLook),
                            size = u(spec.skip * tk)
                        ) {
                            Icon(Icons.Rounded.SkipNext, contentDescription = null, tint = HiFi.OnMetal, modifier = Modifier.size(u(spec.skipIcon * tk)))
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.height(u(spec.top)))
            }
        }

        // The tune panel is an overlay: the cover block stays in place underneath
        val farAlpha = 1f
        // The cover + next block: the full height, at the place the geometry gives
        Column(
            Modifier
                .padding(start = u(xOf(g.coverX, g.cover)), top = u(g.coverTop))
                .width(u(g.cover))
                .graphicsLayer { alpha = farAlpha },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(Modifier.size(u(g.cover)).then(coverGestures)) {
                WideCover(track, s, Modifier.fillMaxSize())
                coverOverlay()
            }
            if (!isRadio) {
                Spacer(Modifier.height(u(NEXT_BLOCK_GAP)))
                Box(Modifier.onGloballyPositioned { onNextBounds(it.boundsInRoot()) }) {
                    if (nextThree) {
                        NextCards(upcoming, upcomingLoading, s, spec, u(g.card), onClick = onUpcomingClick)
                    } else {
                        upcoming.firstOrNull()?.let { next ->
                            NextOne(next, s, spec, Modifier.width(u(g.cover)), onClick = onOpenQueue)
                        } ?: Spacer(Modifier.height(u(spec.oneHeight)))
                    }
                }
            }
        }
        // driver() draws itself at its own place
        driver()
        // The clock: one fixed place on every screen (see wideGeometryOf), over the cover or beside it
        if (spec.car && showClock) {
            Box(
                Modifier
                    .align(if (controlsOnLeft) Alignment.TopEnd else Alignment.TopStart)
                    .padding(top = u(g.clockTop), start = u(g.clockEnd), end = u(g.clockEnd))
                    .graphicsLayer { alpha = farAlpha }
            ) { CarClock(s) }
        }

        // "Tune the wave": the same panel as the main screen, slid in on the controls' (wheel) side, so the
        // cover block on the far side stays in view
        if (tuneOpen) {
            WaveTuneSheet(waveTuning, onChange = onWaveTuningChange, onDismiss = { tuneOpen = false }, onLeft = controlsOnLeft)
        }
    }
    }
    }
}

/** "ОЧЕРЕДЬ · 12 ИЗ 48", the title and the artist, centred. */
@Composable
internal fun WideLabels(track: Track, caption: String, s: Float, spec: WideSpec, modifier: Modifier) {
    AnimatedContent(
        targetState = track,
        contentKey = { it.id },
        transitionSpec = { (fadeIn(HiFiMotion.large()) + slideInVertically(HiFiMotion.large()) { it / 10 }) togetherWith fadeOut(HiFiMotion.quick()) },
        label = "wide_title",
        modifier = modifier
    ) { shown ->
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (caption.isNotEmpty()) {
                Text(
                    caption.uppercase(), color = Color.White.copy(alpha = 0.66f), fontSize = (spec.caps * s).sp,
                    fontWeight = FontWeight.ExtraBold, letterSpacing = 0.12.em, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                shown.title.ifEmpty { stringResource(R.string.bez_nazvaniya) },
                color = Color.White,
                fontSize = (spec.title * s).sp,
                lineHeight = (spec.titleLine * s).sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.02).em,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            Text(
                if (shown.isRadio) stringResource(R.string.mini_player_live) else shown.artistNames.ifEmpty { stringResource(R.string.player_unknown_artist) },
                color = Color.White.copy(alpha = 0.70f),
                fontSize = (spec.artist * s).sp,
                lineHeight = (spec.artist * 1.25f * s).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * The queue dropdown (canvas `PlDropdown`): glass, the active queue's name and a chevron — opens the queue
 * switcher. Used on the main screen and in the wide player.
 */
@Composable
internal fun QueueDropdown(name: String, account: AccountTag?, height: Dp, fontSize: TextUnit, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(height / 2)
    // Icons follow the label, not the row: a 48 dp floor around a small label must not make them loom
    val iconSize = minOf(height * 0.46f, (fontSize.value * 1.5f).dp)
    val label = stringResource(R.string.cd_queues)
    Row(
        modifier
            // A ≥ 48 dp touch target around a pill that hugs its label
            .minimumInteractiveComponentSize()
            .heightIn(min = height)
            .widthIn(max = height * 6.8f)
            // The player's glass, like the ⋮ beside it
            .hifiGlass(shape, fill = Color.White.copy(alpha = 0.06f), border = Color.White.copy(alpha = 0.14f))
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .padding(start = height * 0.3f, end = height * 0.25f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.Layers, contentDescription = null, tint = Color.White, modifier = Modifier.size(iconSize))
        Spacer(Modifier.width(height * 0.2f))
        if (account != null) {
            AccountMark(account, iconSize)
            Spacer(Modifier.width(height * 0.17f))
        }
        Text(name, color = Color.White, fontSize = fontSize, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(height * 0.12f))
        Icon(Icons.Rounded.ExpandMore, contentDescription = null, tint = HiFi.TextSecondary, modifier = Modifier.size(iconSize))
    }
}

@Composable
internal fun GlassRound(size: Dp, description: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(size)
            .hifiGlass(CircleShape, fill = Color.White.copy(alpha = 0.06f), border = Color.White.copy(alpha = 0.14f))
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) { content() }
}

/** Large bold time over a small-caps date, like a dashboard; refreshes once a minute. */
@Composable
private fun CarClock(s: Float) {
    val clock = LocalCarClock.current
    var time by remember { mutableStateOf("") }
    var date by remember { mutableStateOf("") }
    LaunchedEffect(clock) {
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val dateFormat = SimpleDateFormat("EEEE · d MMMM", Locale.forLanguageTag("ru"))
        while (true) {
            val now = clock()
            val calendar = Calendar.getInstance().apply { timeInMillis = now }
            time = timeFormat.format(calendar.time)
            date = dateFormat.format(calendar.time).uppercase(Locale.forLanguageTag("ru"))
            delay((60_000L - now % 60_000L).coerceIn(1_000L, 60_000L))
        }
    }
    // White with a soft dark shadow and a faint scrim behind: readable over any cover (canvas `plc-clock-light/-dark`)
    val shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.55f), Offset(0f, (2 * s)), blurRadius = 14 * s)
    Column(
        Modifier.drawWithCache {
            val scrim = Brush.radialGradient(
                0f to Color.Black.copy(alpha = 0.32f), 1f to Color.Transparent,
                center = Offset(size.width * 0.5f, size.height * 0.5f), radius = maxOf(size.width, size.height) * 0.5f
            )
            onDrawBehind { drawRect(scrim) }
        },
        horizontalAlignment = Alignment.End
    ) {
        Text(time, color = Color.White, fontSize = (46 * s).sp, lineHeight = (50 * s).sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em,
            style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum", shadow = shadow))
        Text(date, color = Color.White.copy(alpha = 0.78f), fontSize = (15 * s).sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.16.em, maxLines = 1,
            style = androidx.compose.ui.text.TextStyle(shadow = shadow))
    }
}

/** The cover with a gloss and a coloured shadow; a station's logo sits on white. */
@Composable
private fun WideCover(track: Track, s: Float, modifier: Modifier) {
    val context = LocalContext.current
    val tint = LocalArtworkTint.current
    val shape = RoundedCornerShape((36 * s).dp)
    Box(
        modifier
            .hifiSoftShadow(shape, (44 * s).dp, tint.primary.copy(alpha = 0.45f))
            .clip(shape)
            .background(Brush.linearGradient(listOf(tint.primary, tint.secondary)))
    ) {
        AnimatedContent(
            targetState = track.coverUri,
            transitionSpec = { fadeIn(HiFiMotion.large()) togetherWith fadeOut(HiFiMotion.large()) },
            label = "wide_cover"
        ) { uri ->
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .cover(uri, CoverSize.Large)
                    .crossfade(true)
                    .apply { if (track.isRadio) browserUserAgent() }
                    .build(),
                contentDescription = track.title.ifEmpty { null },
                contentScale = if (track.isRadio) ContentScale.Fit else ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (track.isRadio) Modifier.background(Color.White).padding((60 * s).dp) else Modifier)
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .drawWithCache {
                    val gloss = Brush.linearGradient(listOf(Color.White.copy(alpha = 0.18f), Color.Transparent), start = Offset.Zero, end = Offset(size.width * 0.55f, size.height * 0.55f))
                    val foot = Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.35f))
                    onDrawBehind {
                        drawRect(gloss)
                        drawRect(foot)
                    }
                }
        )
    }
}

/**
 * "Далее" and three square cards with title and artist under each (today's car cards, smaller). The first card
 * shows the next track's state (D81) and stays the whole tap target.
 */
@Composable
internal fun NextCards(tracks: List<Track>, loading: Boolean, s: Float, spec: WideSpec, card: Dp, onClick: (Track) -> Unit) {
    if (tracks.isEmpty() && !loading) {
        Spacer(Modifier.height(card))
        return
    }
    val nextLook = LocalPlaybackAux.current.next()
    val gap = (NEXT_CARD_GAP * s).dp
    Column(Modifier.width(card * 3 + gap * 2)) {
        Text(
            stringResource(R.string.car_next).uppercase(), color = Color.White.copy(alpha = 0.66f), fontSize = (spec.nextCaps * s).sp,
            fontWeight = FontWeight.ExtraBold, letterSpacing = 0.16.em, maxLines = 1
        )
        Spacer(Modifier.height((10 * s).dp))
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            // Reading order on both wheel sides (canvas: the right-hand side keeps the next cards in order)
            val slots = listOf(0, 1, 2)
            slots.forEach { slot ->
                val track = tracks.getOrNull(slot)
                when {
                    track != null -> NextCard(track, card, s, spec, look = if (slot == 0) nextLook else NextLook.READY) { onClick(track) }
                    loading -> Box(Modifier.size(card).clip(RoundedCornerShape((18 * s).dp)).background(Color.White.copy(alpha = 0.06f)))
                    else -> Spacer(Modifier.width(card))
                }
            }
        }
    }
}

@Composable
private fun NextCard(track: Track, size: Dp, s: Float, spec: WideSpec, look: NextLook, onClick: () -> Unit) {
    val context = LocalContext.current
    val shape = RoundedCornerShape((18 * s).dp)
    val title = track.title.ifEmpty { stringResource(R.string.bez_nazvaniya) }
    val play = stringResource(R.string.car_next_play_cd, title)
    val description = if (look == NextLook.READY) play else "$play. ${nextLookLabel(look)}"
    Column(
        // Not clipped to the cover's rounded corner: that would cut the first letter of the lines under it
        Modifier
            .width(size)
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
    ) {
        Box(contentAlignment = Alignment.Center) {
            AsyncImage(
                model = ImageRequest.Builder(context).cover(track.coverUri, CoverSize.Medium).crossfade(true).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(size)
                    .clip(shape)
                    .background(HiFi.Placeholder)
                    .alpha(if (look == NextLook.FAILED) 0.4f else 1f)
            )
            NextCoverOverlay(look, size)
        }
        Spacer(Modifier.height((6 * s).dp))
        Text(title, color = Color.White, fontSize = (spec.cardTitle * s).sp, lineHeight = (spec.cardTitle * 1.3f * s).sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(track.artistNames, color = Color.White.copy(alpha = 0.66f), fontSize = (spec.cardArtist * s).sp, lineHeight = (spec.cardArtist * 1.3f * s).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** One next track in a glass row (Next tracks = 1): a tap opens the queue. */
@Composable
private fun NextOne(next: Track, s: Float, spec: WideSpec, modifier: Modifier, onClick: () -> Unit) {
    val context = LocalContext.current
    val h = (spec.oneHeight * s).dp
    val shape = RoundedCornerShape(h * 0.29f)
    // At least [h] tall, growing with its text (a large system font) instead of clipping the title
    Row(
        modifier
            .heightIn(min = h)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.07f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = h * 0.16f, end = h * 0.22f, top = h * 0.10f, bottom = h * 0.10f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context).cover(next.coverUri, CoverSize.Small).crossfade(true).build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(h * 0.68f).clip(RoundedCornerShape(h * 0.16f)).background(HiFi.Placeholder)
        )
        Spacer(Modifier.width(h * 0.19f))
        // «ДАЛЕЕ» and the title as one block, centred beside the thumbnail, lines kept tight
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(h * 0.04f, Alignment.CenterVertically)) {
            val caps = (spec.nextCaps * s).sp
            val title = (spec.cardTitle * 1.3f * s).sp
            Text(
                stringResource(R.string.car_next).uppercase(), color = Color.White.copy(alpha = 0.62f), fontSize = caps,
                lineHeight = caps * 1.15f, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.14.em, maxLines = 1
            )
            Text(
                next.title.ifEmpty { stringResource(R.string.bez_nazvaniya) }, color = Color.White, fontSize = title,
                lineHeight = title * 1.15f, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null, tint = Color.White.copy(alpha = 0.62f), modifier = Modifier.size(h * 0.34f))
    }
}

/** What the player's ⋮ menu offers (user, 2026-11-18), in this order. */
internal enum class PlayerMenuItem { TUNE, BACK_TO_WAVE, START_RADIO, SEARCH, SLEEP, ADD_TO_PLAYLIST, QUALITY, LIKE, DISLIKE, MUSIC }

/**
 * The ⋮ menu of every player layout (canvas `pl-player-more`, `pl-land-more`, `plc-more`): "Tune the wave" on My Wave
 * (with "Back to My Wave" on a wave by a track), "Start radio" by the current track on another Yandex queue (none on
 * Jellyfin, D54), Search, the sleep timer; for a track "Add to playlist…" and its quality; and only with likes by
 * gestures Like / Dislike as a fallback, for a service that has likes. In the car layout ([music]) «Музыка» comes last:
 * the car Library of the active queue's account (gap (f), canvas `ca-entry-*`). Pure; unit-tested.
 */
@Suppress("LongParameterList")
internal fun playerMenuItems(
    likeGestures: Boolean,
    supportsLikes: Boolean,
    isRadio: Boolean,
    waveActive: Boolean,
    canStartRadio: Boolean,
    trackWave: Boolean,
    music: Boolean = false
): List<PlayerMenuItem> = buildList {
    when {
        waveActive -> {
            add(PlayerMenuItem.TUNE)
            if (trackWave) add(PlayerMenuItem.BACK_TO_WAVE)
        }
        canStartRadio && !isRadio -> add(PlayerMenuItem.START_RADIO)
    }
    add(PlayerMenuItem.SEARCH)
    add(PlayerMenuItem.SLEEP)
    if (!isRadio) {
        add(PlayerMenuItem.ADD_TO_PLAYLIST)
        add(PlayerMenuItem.QUALITY)
        if (likeGestures && supportsLikes) {
            add(PlayerMenuItem.LIKE)
            add(PlayerMenuItem.DISLIKE)
        }
    }
    if (music) add(PlayerMenuItem.MUSIC)
}

/** The ⋮ of the top row and its menu; the sleep timer opens a second level in the same menu. */
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
@Composable
internal fun PlayerMoreButton(
    items: List<PlayerMenuItem>,
    size: Dp,
    itemHeight: Dp,
    fontSize: TextUnit,
    isLiked: Boolean,
    waveTuning: WaveTuning,
    sleepTimer: org.tegro.yamp.data.player.SleepTimerState?,
    onTune: () -> Unit,
    onStartRadio: () -> Unit,
    onBackToMyWave: () -> Unit,
    onSearch: () -> Unit,
    onSleepTimer: (Int) -> Unit,
    onSleepAtEndOfTrack: () -> Unit,
    onCancelSleepTimer: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onQuality: () -> Unit,
    onLike: () -> Unit,
    onDislike: () -> Unit,
    initialOpen: Boolean,
    onMusic: () -> Unit = {}
) {
    var expanded by remember { mutableStateOf(initialOpen) }
    var sleepLevel by remember { mutableStateOf(false) }
    val close = { expanded = false; sleepLevel = false }
    Box {
        Box(
            Modifier
                // A ≥ 48 dp touch target around a circle as tall as the queue pill beside it
                .minimumInteractiveComponentSize()
                .size(size)
                .then(if (sleepTimer != null) Modifier.hifiAnodized(CircleShape) else Modifier.hifiGlass(CircleShape))
                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.wide_menu_more)) { expanded = true },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.wide_menu_more), tint = Color.White, modifier = Modifier.size(size * 0.45f))
        }
        val appDensity = LocalDensity.current
        androidx.compose.material3.DropdownMenu(
            expanded = expanded,
            onDismissRequest = close,
            shape = RoundedCornerShape(18.dp),
            containerColor = org.tegro.yamp.presentation.hifi.HiFiDialogDefaults.Container,
            border = androidx.compose.foundation.BorderStroke(1.dp, org.tegro.yamp.presentation.hifi.HiFiDialogDefaults.Rim)
        ) {
            org.tegro.yamp.presentation.hifi.AppScale(appDensity) {
                val colors = androidx.compose.material3.MenuDefaults.itemColors(textColor = Color.White, leadingIconColor = Color.White)
                @Composable
                fun row(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector?, color: Color = Color.White, onClick: () -> Unit) =
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(text, fontSize = fontSize, color = color, maxLines = 2, lineHeight = fontSize * 1.15f) },
                        leadingIcon = icon?.let { { Icon(it, contentDescription = null, modifier = Modifier.size(itemHeight * 0.4f)) } },
                        onClick = onClick,
                        colors = colors,
                        modifier = Modifier.heightIn(min = itemHeight)
                    )
                if (sleepLevel) {
                    for (minutes in listOf(15, 30, 45, 60)) row(stringResource(R.string.sleep_minutes, minutes), null) { close(); onSleepTimer(minutes) }
                    row(stringResource(R.string.sleep_end_of_track), null) { close(); onSleepAtEndOfTrack() }
                    if (sleepTimer != null) row(stringResource(R.string.sleep_off), null, HiFi.Danger) { close(); onCancelSleepTimer() }
                } else {
                    val quality = org.tegro.yamp.utils.LocalStreamQuality.current
                    for (item in items) when (item) {
                        PlayerMenuItem.TUNE -> {
                            val summary = waveTuningSummary(waveTuning)
                            row(summary?.let { stringResource(R.string.wave_tune_cd, it) } ?: stringResource(R.string.wave_tune_title), Icons.Rounded.Tune) { close(); onTune() }
                        }
                        PlayerMenuItem.BACK_TO_WAVE -> row(stringResource(R.string.wave_back_to_mine), Icons.Rounded.Waves) { close(); onBackToMyWave() }
                        PlayerMenuItem.START_RADIO -> row(stringResource(R.string.radio_start), Icons.Rounded.Sensors) { close(); onStartRadio() }
                        PlayerMenuItem.SEARCH -> row(stringResource(R.string.tab_search), Icons.Rounded.Search) { close(); onSearch() }
                        PlayerMenuItem.SLEEP -> row(stringResource(R.string.cd_sleep_timer), Icons.Rounded.Bedtime) { sleepLevel = true }
                        PlayerMenuItem.ADD_TO_PLAYLIST ->
                            row(stringResource(R.string.track_context_menu_dobavit_v_pleylist), Icons.AutoMirrored.Rounded.PlaylistAdd) { close(); onAddToPlaylist() }
                        PlayerMenuItem.QUALITY -> row(
                            quality?.let { stringResource(R.string.wide_menu_quality, it) } ?: stringResource(R.string.wide_menu_quality_unknown),
                            Icons.Rounded.GraphicEq
                        ) { close(); onQuality() }
                        PlayerMenuItem.LIKE -> row(
                            stringResource(if (isLiked) R.string.track_menu_unlike else R.string.track_menu_like),
                            if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder
                        ) { close(); onLike() }
                        PlayerMenuItem.DISLIKE -> row(stringResource(R.string.cd_ne_nravitsya), Icons.Rounded.HeartBroken) { close(); onDislike() }
                        PlayerMenuItem.MUSIC -> row(stringResource(R.string.player_menu_music), Icons.Rounded.LibraryMusic) { close(); onMusic() }
                    }
                }
            }
        }
    }
}
