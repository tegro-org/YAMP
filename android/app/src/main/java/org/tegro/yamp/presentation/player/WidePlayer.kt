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
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.Icon
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
import org.tegro.yamp.presentation.main.components.WaveTuneChoices
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

/**
 * Where the cover goes in the far column of [widthDp] × [heightDp], with [nextDp] under it for the next tracks and,
 * in the car, the clock ([clockDp] wide) in the far corner: the largest square that fits (up to [maxDp]) and stays
 * clear of the clock (`plc-unit-1760`). The column spans the full usable height — from the top row's top to the
 * bottom margin — so the cover takes all of it the next block leaves. Too narrow beside the clock: the cover starts
 * under it ([belowClockDp] down). Pure; unit-tested.
 */
internal data class CoverFit(val size: Float, val top: Float)

/** The gap between the three next-track cards, in layout units. */
private const val NEXT_CARD_GAP = 20f

internal fun coverFitOf(widthDp: Float, heightDp: Float, nextDp: Float, gapDp: Float, maxDp: Float, clockDp: Float, belowClockDp: Float): CoverFit {
    val byHeight = heightDp - nextDp - gapDp
    if (clockDp <= 0f) return CoverFit(minOf(maxDp, widthDp, byHeight).coerceAtLeast(0f), 0f)
    val besideClock = minOf(maxDp, byHeight, widthDp - 2 * clockDp).coerceAtLeast(0f)
    val belowClock = minOf(maxDp, widthDp, byHeight - belowClockDp).coerceAtLeast(0f)
    // Whichever gives the larger cover: on a 1760-wide unit it goes under the clock at full size
    return if (besideClock >= belowClock) CoverFit(besideClock, 0f) else CoverFit(belowClock, belowClockDp)
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
    initialMoreOpen: Boolean = false
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

        val topRow: @Composable () -> Unit = {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(u(spec.barGap), if (controlsOnLeft) Alignment.Start else Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val items = buildList<@Composable () -> Unit> {
                    add { QueueDropdown(queueName, queueAccount, height = u(spec.bar), fontSize = t(spec.barFont + 2f), onClick = onQueues, modifier = Modifier.weight(1f, fill = false)) }
                    add {
                        WaveButtons(
                            track, waveActive, waveTuning, trackWaveSeed, onTrackWave, onBackToMyWave,
                            onTune = { tuneOpen = true }, height = u(spec.bar), fontSize = t(spec.barFont)
                        )
                    }
                    add {
                        Box(
                            Modifier.size(u(spec.bar)).hifiGlass(CircleShape).clickable(role = Role.Button, onClickLabel = stringResource(R.string.tab_search), onClick = onSearch),
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.tab_search), tint = Color.White, modifier = Modifier.size(u(spec.bar * 0.45f))) }
                    }
                    add {
                        WideMoreButton(
                            items = wideMenuItems(!likeButtons, track.supportsLikes, isRadio),
                            size = u(spec.bar),
                            itemHeight = if (spec.car) u(64f) else 48.dp,
                            fontSize = if (spec.car) t(spec.barFont + 2f) else 16.sp,
                            isLiked = isLiked,
                            sleepTimer = sleepTimer,
                            onSleepTimer = onSleepTimer,
                            onSleepAtEndOfTrack = onSleepAtEndOfTrack,
                            onCancelSleepTimer = onCancelSleepTimer,
                            onAddToPlaylist = onAddToPlaylist,
                            onQuality = onQuality,
                            onLike = onLike,
                            onDislike = onDislikeWithUndo,
                            initialOpen = initialMoreOpen
                        )
                    }
                }
                (if (controlsOnLeft) items else items.reversed()).forEach { it() }
            }
        }

        val driver: @Composable () -> Unit = {
            Column(Modifier.width(u(spec.driverW)).fillMaxHeight()) {
                topRow()
                Spacer(Modifier.weight(1f))
                // The labels centred between dislike and like (the buttons keep their place when hidden)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val likes = likeButtons && track.supportsLikes
                    if (likes) {
                        GlassRound(u(spec.like), stringResource(R.string.cd_ne_nravitsya), onDislike) {
                            Icon(Icons.Rounded.HeartBroken, contentDescription = null, tint = Color.White.copy(alpha = 0.88f), modifier = Modifier.size(u(spec.likeIcon)))
                        }
                    } else Spacer(Modifier.size(u(spec.like)))
                    WideLabels(track, caption, s, spec, Modifier.weight(1f).padding(horizontal = u(16f)))
                    if (likes) {
                        GlassRound(u(spec.like), stringResource(R.string.cd_nravitsya), onLike) {
                            Icon(
                                if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                contentDescription = null,
                                tint = if (isLiked) HiFi.Love else Color.White.copy(alpha = 0.88f),
                                modifier = Modifier.size(u(spec.likeIcon))
                            )
                        }
                    } else Spacer(Modifier.size(u(spec.like)))
                }
                Spacer(Modifier.height(u(14f)))
                if (isRadio) HiFiLiveLine(Modifier.fillMaxWidth(), unit = u(spec.timelineUnit))
                else PlayerTimeline(progress, durationMs, isPlaying, onSeek, unit = u(spec.timelineUnit), timeSize = t(spec.timeSize))
                Spacer(Modifier.height(u(8f)))
                // Prev · Play (the car: in the ring) · Next
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(u(spec.transportGap), Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HiFiKnob(onClick = onPrev, contentDescription = stringResource(R.string.cd_predyduschiy_trek), size = u(spec.skip)) {
                        Icon(Icons.Rounded.SkipPrevious, contentDescription = null, tint = HiFi.OnMetal, modifier = Modifier.size(u(spec.skipIcon)))
                    }
                    val ringSize = if (spec.ring > 0f) spec.ring else spec.play
                    Box(Modifier.size(u(ringSize)), contentAlignment = Alignment.Center) {
                        if (spec.ring > 0f) {
                            SpectrumRing(
                                mode = ringMode,
                                progress = fraction,
                                bars = if (LocalHiFiLite.current) 60 else 90,
                                innerRadius = u(spec.play / 2f + spec.play * 0.04f),
                                maxLength = u((spec.ring - spec.play) / 2f - spec.play * 0.06f),
                                barWidth = u(3f),
                                modifier = Modifier.size(u(ringSize))
                            )
                        }
                        HiFiKnob(onClick = onPlayPause, contentDescription = stringResource(R.string.cd_vosproizvedenie_ili_pauza), size = u(spec.play)) {
                            PlayPauseGlyph(isPlaying = isPlaying, size = u(spec.play * 0.28f))
                        }
                    }
                    val nextLook = LocalPlaybackAux.current.next()
                    NextStateDecor(nextLook, size = u(spec.skip)) {
                        HiFiKnob(
                            onClick = onNext,
                            contentDescription = if (nextLook == NextLook.READY) stringResource(R.string.cd_sleduyuschiy_trek) else nextLookLabel(nextLook),
                            size = u(spec.skip)
                        ) {
                            Icon(Icons.Rounded.SkipNext, contentDescription = null, tint = HiFi.OnMetal, modifier = Modifier.size(u(spec.skipIcon)))
                        }
                    }
                }
                Spacer(Modifier.height(u(spec.top)))
            }
        }

        val far: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
            val farAlpha by androidx.compose.animation.core.animateFloatAsState(if (tuneOpen) 0f else 1f, HiFiMotion.standard(), label = "wide_far")
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().graphicsLayer { alpha = farAlpha }) {
                val clock = spec.car && showClock
                // Three cards span the cover's width (as in the phone landscape player): sized from the largest cover
                // first, then from the cover that fits
                fun cardsFor(cover: Float) = ((cover - 2 * NEXT_CARD_GAP) / 3f).coerceAtLeast(0f)
                fun blockFor(card: Float) = when {
                    isRadio -> 0f
                    // What NextCards draws: the caption line, 10, the card, 6, the title and artist lines, a little air
                    nextThree -> spec.nextCaps * 1.4f + 10f + card + 6f + spec.cardTitle * 1.3f + spec.cardArtist * 1.3f + 8f
                    else -> spec.oneHeight
                }
                // The block spans the full usable height: from the top row's top (the row's own top padding) down to
                // the same bottom margin the controls keep. The cover takes whatever height the next block leaves —
                // no fixed cap — and stays square.
                val columnW = maxWidth.value / s
                val columnH = maxHeight.value / s - spec.top
                fun fitFor(nextDp: Float) = coverFitOf(
                    widthDp = columnW, heightDp = columnH, nextDp = nextDp,
                    gapDp = if (isRadio) 0f else 22f, maxDp = maxOf(columnW, columnH),
                    clockDp = if (clock) 230f else 0f, belowClockDp = 96f
                )
                // Three cards are as wide as the cover, so their height depends on it: a second pass settles both
                val firstFit = fitFor(blockFor(cardsFor(minOf(columnW, columnH))))
                val fit = if (nextThree) fitFor(blockFor(cardsFor(firstFit.size))) else firstFit
                val cardSize = if (nextThree) cardsFor(fit.size) else 0f
                Column(
                    Modifier.fillMaxSize().padding(top = u(fit.top), bottom = u(spec.top)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(u(22f), Alignment.CenterVertically)
                ) {
                    Box(Modifier.size(u(fit.size)).then(coverGestures)) {
                        WideCover(track, s, Modifier.fillMaxSize())
                        coverOverlay()
                    }
                    if (!isRadio) {
                        Box(Modifier.onGloballyPositioned { onNextBounds(it.boundsInRoot()) }) {
                            if (nextThree) {
                                NextCards(upcoming, upcomingLoading, s, spec, u(cardSize), mirrored = !controlsOnLeft, onClick = onUpcomingClick)
                            } else {
                                upcoming.firstOrNull()?.let { next ->
                                    NextOne(next, s, spec, Modifier.width(u(fit.size)), onClick = onOpenQueue)
                                } ?: Spacer(Modifier.height(u(spec.oneHeight)))
                            }
                        }
                    }
                }
                if (clock) {
                    Box(Modifier.align(if (controlsOnLeft) Alignment.TopEnd else Alignment.TopStart)) { CarClock(s) }
                }
            }
        }

        Row(Modifier.fillMaxSize().padding(start = u(spec.pad), end = u(spec.pad), top = u(spec.top))) {
            if (controlsOnLeft) {
                driver(); Spacer(Modifier.width(u(spec.gap))); far()
            } else {
                far(); Spacer(Modifier.width(u(spec.gap))); driver()
            }
        }

        // "Tune the wave" over the far column: the controls stay free
        if (tuneOpen) {
            Box(Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { tuneOpen = false })
            val panelWidth = min(u(560f), maxWidth - u(spec.pad * 2 + spec.driverW + spec.gap)).coerceAtLeast(u(380f))
            TunePanel(
                waveTuning, onWaveTuningChange, s,
                onClose = { tuneOpen = false },
                modifier = Modifier
                    .align(if (controlsOnLeft) Alignment.TopEnd else Alignment.TopStart)
                    .padding(top = u(spec.top + spec.bar + 16f), start = u(spec.pad), end = u(spec.pad), bottom = u(spec.top))
                    .width(panelWidth),
                segments = panelWidth >= u(460f)
            )
        }
    }
    }
    }
}

/** "ОЧЕРЕДЬ · 12 ИЗ 48", the title and the artist, centred. */
@Composable
private fun WideLabels(track: Track, caption: String, s: Float, spec: WideSpec, modifier: Modifier) {
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

/** The wave's buttons in the top row: "Tune the wave" on My Wave; off it "Start radio" (D54). */
@Composable
private fun WaveButtons(
    track: Track,
    waveActive: Boolean,
    tuning: WaveTuning,
    trackWaveSeed: Track?,
    onTrackWave: (() -> Unit)?,
    onBackToMyWave: () -> Unit,
    onTune: () -> Unit,
    height: Dp,
    fontSize: TextUnit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(height * 0.2f), verticalAlignment = Alignment.CenterVertically) {
        when {
            !waveActive -> if (onTrackWave != null && track.supportsLikes && !track.isRadio) {
                WavePill(Icons.Rounded.Sensors, stringResource(R.string.radio_start), active = false, height = height, fontSize = fontSize, onClick = onTrackWave)
            }
            trackWaveSeed != null -> {
                WavePill(Icons.Rounded.Waves, stringResource(R.string.wave_back_to_mine), active = false, height = height, fontSize = fontSize, onClick = onBackToMyWave)
                WavePill(Icons.Rounded.Tune, stringResource(R.string.wave_tune_short), active = !tuning.isDefault, height = height, fontSize = fontSize,
                    onClick = onTune, description = stringResource(R.string.wave_tune_title))
            }
            else -> {
                val summary = waveTuningSummary(tuning)
                WavePill(Icons.Rounded.Tune, summary ?: stringResource(R.string.wave_tune_title), active = summary != null, height = height, fontSize = fontSize,
                    onClick = onTune, description = summary?.let { stringResource(R.string.wave_tune_cd, it) } ?: stringResource(R.string.wave_tune_title))
            }
        }
    }
}

/** "Настроить волну": big enough to hit without aiming; scrolls inside its place on a cramped screen. */
@Composable
private fun TunePanel(tuning: WaveTuning, onChange: (WaveTuning) -> Unit, s: Float, onClose: () -> Unit, modifier: Modifier, segments: Boolean) {
    fun u(v: Float): Dp = (v * s).dp
    val shape = RoundedCornerShape(u(30f))
    Column(
        modifier
            .clip(shape)
            .background(HiFi.SheetFill.copy(alpha = 0.97f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), shape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
            .verticalScroll(rememberScrollState())
            .padding(start = u(24f), end = u(18f), top = u(16f), bottom = u(22f))
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.wave_tune_title), color = Color.White, fontSize = (28 * s).sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            Box(Modifier.size(u(56f)).hifiGlass(CircleShape).clickable(role = Role.Button, onClick = onClose), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cd_zakryt), tint = Color.White, modifier = Modifier.size(u(24f)))
            }
        }
        WaveTuneChoices(tuning, onChange, segments = segments, optionHeight = u(56f), fontSize = (15 * s).sp, captionSize = (14 * s).sp, gap = u(12f))
    }
}

/**
 * The queue dropdown (canvas `PlDropdown`): anodized, the active queue's name and a chevron — opens the queue
 * switcher. Used on the main screen and in the wide player.
 */
@Composable
internal fun QueueDropdown(name: String, account: AccountTag?, height: Dp, fontSize: TextUnit, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(height / 2)
    val label = stringResource(R.string.cd_queues)
    Row(
        modifier
            .heightIn(min = height)
            .widthIn(max = height * 6.8f)
            .hifiAnodized(shape)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .padding(start = height * 0.3f, end = height * 0.25f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.Layers, contentDescription = null, tint = Color.White, modifier = Modifier.size(height * 0.46f))
        Spacer(Modifier.width(height * 0.2f))
        if (account != null) {
            AccountMark(account, height * 0.46f)
            Spacer(Modifier.width(height * 0.17f))
        }
        Text(name, color = Color.White, fontSize = fontSize, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(height * 0.12f))
        Icon(Icons.Rounded.ExpandMore, contentDescription = null, tint = HiFi.TextSecondary, modifier = Modifier.size(height * 0.46f))
    }
}

@Composable
private fun GlassRound(size: Dp, description: String, onClick: () -> Unit, content: @Composable () -> Unit) {
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
    Column(horizontalAlignment = Alignment.End) {
        Text(time, color = Color.White, fontSize = (46 * s).sp, lineHeight = (50 * s).sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em,
            style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"))
        Text(date, color = Color.White.copy(alpha = 0.60f), fontSize = (15 * s).sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.16.em, maxLines = 1)
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
private fun NextCards(tracks: List<Track>, loading: Boolean, s: Float, spec: WideSpec, card: Dp, mirrored: Boolean, onClick: (Track) -> Unit) {
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
            val slots = if (mirrored) listOf(2, 1, 0) else listOf(0, 1, 2)
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
        Modifier
            .width(size)
            .clip(shape)
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
    Row(
        modifier
            .height(h)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.07f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = h * 0.16f, end = h * 0.22f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context).cover(next.coverUri, CoverSize.Small).crossfade(true).build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(h * 0.68f).clip(RoundedCornerShape(h * 0.16f)).background(HiFi.Placeholder)
        )
        Spacer(Modifier.width(h * 0.19f))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.car_next).uppercase(), color = Color.White.copy(alpha = 0.62f), fontSize = (spec.nextCaps * s).sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.14.em, maxLines = 1)
            Text(next.title.ifEmpty { stringResource(R.string.bez_nazvaniya) }, color = Color.White, fontSize = (spec.cardTitle * 1.3f * s).sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null, tint = Color.White.copy(alpha = 0.62f), modifier = Modifier.size(h * 0.34f))
    }
}

/** What the wide player's ⋮ menu offers (user, 2026-11-18). */
internal enum class WideMenuItem { SLEEP, ADD_TO_PLAYLIST, QUALITY, LIKE, DISLIKE }

/**
 * The ⋮ menu of the wide layouts: the sleep timer always; for a track, "Add to playlist…" and its quality; and only
 * with likes by gestures (no buttons on screen) Like / Dislike as a fallback, for a service that has likes.
 */
internal fun wideMenuItems(likeGestures: Boolean, supportsLikes: Boolean, isRadio: Boolean): List<WideMenuItem> = buildList {
    add(WideMenuItem.SLEEP)
    if (!isRadio) {
        add(WideMenuItem.ADD_TO_PLAYLIST)
        add(WideMenuItem.QUALITY)
        if (likeGestures && supportsLikes) {
            add(WideMenuItem.LIKE)
            add(WideMenuItem.DISLIKE)
        }
    }
}

/** The ⋮ at the end of the top row and its menu; the sleep timer opens a second level in the same menu. */
@Suppress("LongParameterList", "LongMethod")
@Composable
private fun WideMoreButton(
    items: List<WideMenuItem>,
    size: Dp,
    itemHeight: Dp,
    fontSize: TextUnit,
    isLiked: Boolean,
    sleepTimer: org.tegro.yamp.data.player.SleepTimerState?,
    onSleepTimer: (Int) -> Unit,
    onSleepAtEndOfTrack: () -> Unit,
    onCancelSleepTimer: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onQuality: () -> Unit,
    onLike: () -> Unit,
    onDislike: () -> Unit,
    initialOpen: Boolean
) {
    var expanded by remember { mutableStateOf(initialOpen) }
    var sleepLevel by remember { mutableStateOf(false) }
    val close = { expanded = false; sleepLevel = false }
    Box {
        Box(
            Modifier
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
                        WideMenuItem.SLEEP -> row(stringResource(R.string.cd_sleep_timer), Icons.Rounded.Bedtime) { sleepLevel = true }
                        WideMenuItem.ADD_TO_PLAYLIST ->
                            row(stringResource(R.string.track_context_menu_dobavit_v_pleylist), Icons.AutoMirrored.Rounded.PlaylistAdd) { close(); onAddToPlaylist() }
                        WideMenuItem.QUALITY -> row(
                            quality?.let { stringResource(R.string.wide_menu_quality, it) } ?: stringResource(R.string.wide_menu_quality_unknown),
                            Icons.Rounded.GraphicEq
                        ) { close(); onQuality() }
                        WideMenuItem.LIKE -> row(
                            stringResource(if (isLiked) R.string.track_menu_unlike else R.string.track_menu_like),
                            if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder
                        ) { close(); onLike() }
                        WideMenuItem.DISLIKE -> row(stringResource(R.string.cd_ne_nravitsya), Icons.Rounded.HeartBroken) { close(); onDislike() }
                    }
                }
            }
        }
    }
}
