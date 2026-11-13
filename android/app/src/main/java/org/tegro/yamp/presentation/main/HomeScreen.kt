package org.tegro.yamp.presentation.main

import org.tegro.yamp.utils.currentWindowSizeDp
import org.tegro.yamp.presentation.main.components.WaveTuneChips
import org.tegro.yamp.domain.model.WaveTuning
import androidx.compose.foundation.layout.heightIn
import org.tegro.yamp.presentation.hifi.HiFiLinkBadge
import org.tegro.yamp.utils.cover
import org.tegro.yamp.utils.CoverSize
import org.tegro.yamp.presentation.hifi.hifiPress
import org.tegro.yamp.presentation.hifi.hifiSoftShadow
import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.HeartBroken
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.tegro.yamp.R
import org.tegro.yamp.presentation.queues.QueueButton
import org.tegro.yamp.data.dto.TrackDetailsDto
import org.tegro.yamp.data.queue.ActiveQueue
import org.tegro.yamp.domain.queue.QueueEntry
import org.tegro.yamp.domain.queue.QueueKind
import org.tegro.yamp.presentation.hifi.GlassCircleButton
import org.tegro.yamp.presentation.hifi.HiFi
import org.tegro.yamp.presentation.hifi.HiFiKnob
import org.tegro.yamp.presentation.hifi.HiFiLargeTitle
import org.tegro.yamp.presentation.hifi.HiFiMotion
import org.tegro.yamp.presentation.hifi.HiFiSectionHeader
import org.tegro.yamp.presentation.hifi.HiFiTextAction
import org.tegro.yamp.presentation.hifi.HiFiType
import org.tegro.yamp.presentation.hifi.LocalHiFiLite
import org.tegro.yamp.presentation.hifi.PlayPauseGlyph
import org.tegro.yamp.presentation.hifi.RingMode
import org.tegro.yamp.presentation.hifi.SpectrumRing

/**
 * What the wave page shows under the knob. Pure; unit-tested.
 *
 * - The wave plays: the tracks after the current one; while the queue refills, placeholders.
 * - The wave is not playing (not started, or another source plays): a preview of the saved wave —
 *   [startsWith] is the track the knob starts with, [shelf] what follows it.
 *
 * The shelf is never simply missing: when there is nothing to show yet, [loading] keeps its place.
 */
data class WaveShelfState(
    val startsWith: TrackDetailsDto?,
    val shelf: List<TrackDetailsDto>,
    val loading: Boolean
)

fun waveShelfOf(waveActive: Boolean, upcoming: List<TrackDetailsDto>, preview: List<TrackDetailsDto>): WaveShelfState =
    if (waveActive) {
        WaveShelfState(startsWith = null, shelf = upcoming, loading = upcoming.isEmpty())
    } else {
        val shelf = preview.drop(1)
        WaveShelfState(startsWith = preview.firstOrNull(), shelf = shelf, loading = shelf.isEmpty())
    }

/**
 * Whose page the first tab is (D49): the wave's — the WAVE queue is active, or none is — or a queue of the
 * user's own (STATIC; later RADIO/MIX), named after it and without the wave-only controls.
 */
sealed interface HomeMode {
    data object Wave : HomeMode
    data class Queue(val name: String) : HomeMode
}

fun homeModeOf(active: ActiveQueue?): HomeMode =
    if (active == null || active.queue.kind == QueueKind.WAVE) HomeMode.Wave else HomeMode.Queue(active.queue.name)

/**
 * A queue's page under the knob: the tracks after the cursor (a queue without a cursor counts from its first
 * entry). While the queue is not what plays, the cursor track is what the knob starts with. Nothing is ever
 * being fetched for a static queue, so the shelf is never "loading".
 */
fun queueShelfOf(active: ActiveQueue, playing: Boolean, limit: Int = 10): WaveShelfState {
    val start = active.cursorIndex.coerceAtLeast(0)
    return WaveShelfState(
        startsWith = if (playing) null else active.tracks.getOrNull(start),
        shelf = active.tracks.drop(start + 1).take(limit),
        loading = false
    )
}

/** The entry a shelf card stands for: the first occurrence of the track after the cursor. */
fun shelfEntryOf(active: ActiveQueue, trackId: String): QueueEntry? {
    val from = active.cursorIndex.coerceAtLeast(0) + 1
    val index = (from until active.tracks.size).firstOrNull { active.tracks[it].idString == trackId } ?: return null
    return active.entries.getOrNull(index)
}

/**
 * The first tab — the face of the app: a metal knob inside a spectrum ring (equalizer and track progress
 * in one) in the upper part, the track and the "next up" shelf at the bottom, right above the tab bar.
 * The page never scrolls: the knob shrinks on short screens so everything always fits.
 *
 * It is the active queue's page (D49): "Моя волна" with the wave's pills, or a queue of the user's own
 * under its name, with the same knob, track and shelf and no wave controls — see [mode].
 *
 * [waveTrack] is the current track only while what the page shows is the playing source; otherwise [shelf]
 * tells which track the knob starts with.
 */
@Composable
fun HomeScreen(
    waveTrack: TrackDetailsDto?,
    isPlaying: Boolean,
    isLoading: Boolean,
    isLiked: Boolean,
    progress: () -> Long,
    shelf: WaveShelfState,
    shimmer: Boolean,
    onPlayPause: () -> Unit,
    onLike: () -> Unit,
    onDislike: () -> Unit,
    onOpenPlayer: (() -> Unit)?,
    onShelfClick: (TrackDetailsDto) -> Unit,
    contentBottom: Dp = 100.dp,
    tuning: WaveTuning = WaveTuning(),
    trackWaveSeed: TrackDetailsDto? = null,
    onTune: () -> Unit = {},
    onBackToMyWave: () -> Unit = {},
    /** How many queues the user has: the badge on the queue button. */
    queueCount: Int = 0,
    mode: HomeMode = HomeMode.Wave
) {
    val wave = mode is HomeMode.Wave
    val title = when (mode) {
        is HomeMode.Queue -> mode.name
        HomeMode.Wave -> trackWaveSeed?.let { stringResource(R.string.wave_by_track_title, it.title.orEmpty()) } ?: stringResource(R.string.wave_title)
    }
    // A queue's page has no pills; the row keeps its place so the knob stays where it is across queues
    val chips: @Composable (Modifier) -> Unit = { modifier ->
        if (wave) WaveTuneChips(tuning, trackWave = trackWaveSeed != null, onTune = onTune, onBackToMyWave = onBackToMyWave, modifier = modifier)
        else Spacer(modifier)
    }
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val windowSize = org.tegro.yamp.utils.currentWindowSizeDp()
    val minDim = minOf(windowSize.widthDp, windowSize.heightDp).toFloat()
    val preferredKnob = (minDim * if (isLandscape) 0.34f else 0.41f).dp.coerceIn(120.dp, 210.dp)

    val duration = (waveTrack?.durationMs ?: 0L).coerceAtLeast(0L)
    val progressFraction = { if (duration > 0) progress().toFloat() / duration else 0f }
    val ringMode = when {
        isLoading -> RingMode.Loading
        waveTrack != null && isPlaying -> RingMode.Playing
        else -> RingMode.Paused
    }
    val playing = waveTrack != null && isPlaying

    // The knob fits the space it is given: ring = 1.72 × knob
    val instrument: @Composable (Dp) -> Unit = { maxRing ->
        val knob = waveKnobSize(preferredKnob, maxRing)
        WaveInstrument(knobSize = knob, mode = ringMode, isPlaying = playing, wave = wave, progress = progressFraction, onPlayPause = onPlayPause, onOpenPlayer = onOpenPlayer)
    }

    if (isLandscape) {
        // A phone on its side (under 480 dp tall): the chips go above the knob, where there is room, so the
        // "next up" cards keep clear of the tab dock
        val chipsOverKnob = windowSize.heightDp < 480
        Row(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 24.dp, top = 12.dp, bottom = contentBottom),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (chipsOverKnob) chips(Modifier.padding(top = 4.dp, bottom = 4.dp))
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    instrument(minOf(maxWidth, maxHeight))
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HiFiLargeTitle(title, modifier = Modifier.weight(1f))
                    HiFiLinkBadge()
                    QueueButton(queueCount, modifier = Modifier.padding(start = 8.dp))
                }
                if (!chipsOverKnob) chips(Modifier.padding(top = 10.dp))
                Spacer(Modifier.weight(1f))
                WaveTrackInfo(waveTrack, shelf.startsWith, wave, isLiked, onLike, onDislike)
                WaveShelf(shelf, wave, shimmer, onOpenPlayer, onShelfClick, horizontalPadding = 0.dp, cardSize = if (windowSize.heightDp < 480) 84.dp else 112.dp)
            }
        }
    } else {
        // The knob stays centred in the space between the title and the bottom block, as if that block sat
        // at the very bottom; the block itself is lifted up to just under the ring, so the track and the
        // "next up" cards keep away from the tab bar. On short screens it stays at the bottom.
        Layout(
            contents = listOf(
                {
                    // The knob's place is counted from this height: it must not change with the title
                    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp)) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                            HiFiLargeTitle(title, modifier = Modifier.weight(1f))
                            HiFiLinkBadge()
                            QueueButton(queueCount, modifier = Modifier.padding(start = 8.dp))
                        }
                        chips(Modifier.padding(top = 10.dp).height(36.dp))
                    }
                },
                {
                    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        instrument(minOf(maxWidth, maxHeight))
                    }
                },
                {
                    Column {
                        Box(Modifier.padding(horizontal = 20.dp)) { WaveTrackInfo(waveTrack, shelf.startsWith, wave, isLiked, onLike, onDislike) }
                        WaveShelf(shelf, wave, shimmer, onOpenPlayer, onShelfClick, horizontalPadding = 20.dp, cardSize = portraitShelfCard(windowSize.widthDp.dp))
                    }
                }
            ),
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(bottom = contentBottom)
        ) { (titleSlot, instrumentSlot, bottomSlot), constraints ->
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            val loose = Constraints(maxWidth = width)
            val title = titleSlot.first().measure(loose)
            val bottom = bottomSlot.first().measure(loose)
            val pad = WaveInstrumentPadding.roundToPx()
            val innerHeight = (height - title.height - bottom.height - 2 * pad).coerceAtLeast(0)
            val instrumentPlaceable = instrumentSlot.first().measure(Constraints.fixed(width, innerHeight))
            val ring = waveRingSize(preferredKnob, minOf(width, innerHeight).toDp()).roundToPx()
            val centerY = title.height + pad + innerHeight / 2
            val bottomY = minOf(centerY + ring / 2 + WaveRingToTrackGap.roundToPx(), height - bottom.height)
            layout(width, height) {
                title.place(0, 0)
                instrumentPlaceable.place(0, title.height + pad)
                bottom.place(0, bottomY)
            }
        }
    }
}

private val WaveInstrumentPadding = 8.dp

/**
 * The "next up" cards on a standing screen: 132 dp on a phone, growing with the width so a tablet or a
 * tall head unit (the Geely's 960 dp) shows about as many cards across as a phone, not a short strip.
 */
internal fun portraitShelfCard(width: Dp): Dp = ((width - 40.dp - 36.dp) / 3.4f).coerceIn(132.dp, 200.dp)
/** From the bottom of the spectrum ring to the track title. */
private val WaveRingToTrackGap = 18.dp

/** The knob for the space it is given; the ring around it is 1.72 × the knob. */
private fun waveKnobSize(preferred: Dp, maxRing: Dp): Dp = minOf(preferred, maxRing / 1.72f).coerceAtLeast(84.dp)

private fun waveRingSize(preferredKnob: Dp, maxRing: Dp): Dp = waveKnobSize(preferredKnob, maxRing) * 1.72f

/** Knob + ring. The ring is ~1.7× the knob: room for the longest bars. A tap on the ring opens the player. */
@Composable
private fun WaveInstrument(
    knobSize: Dp,
    mode: RingMode,
    isPlaying: Boolean,
    wave: Boolean,
    progress: () -> Float,
    onPlayPause: () -> Unit,
    onOpenPlayer: (() -> Unit)? = null
) {
    val ringSize = knobSize * 1.72f
    val openPlayer = stringResource(R.string.wave_player)
    Box(
        Modifier
            .size(ringSize)
            .then(
                if (onOpenPlayer != null) {
                    Modifier
                        .clip(CircleShape)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClickLabel = openPlayer, onClick = onOpenPlayer)
                } else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        SpectrumRing(
            mode = mode,
            progress = progress,
            bars = 72,
            innerRadius = knobSize / 2 + knobSize * 0.08f,
            maxLength = knobSize * 0.24f,
            modifier = Modifier.size(ringSize)
        )
        HiFiKnob(
            onClick = onPlayPause,
            contentDescription = stringResource(
                when {
                    isPlaying -> R.string.wave_pause
                    wave -> R.string.wave_play
                    else -> R.string.cd_slushat
                }
            ),
            size = knobSize
        ) {
            PlayPauseGlyph(isPlaying = isPlaying, size = knobSize * 0.3f)
        }
    }
}

private val TrackInfoHeight = 56.dp

/**
 * The playing track with dislike/like; or, before the page's music plays, the track it starts with. Fixed
 * height. With nothing at all: the wave is being fetched, a queue of the user's own is empty.
 */
@Composable
private fun WaveTrackInfo(track: TrackDetailsDto?, startsWith: TrackDetailsDto?, wave: Boolean, isLiked: Boolean, onLike: () -> Unit, onDislike: () -> Unit) {
    AnimatedContent(
        targetState = track to startsWith,
        contentKey = { (playing, next) -> playing?.idString ?: ("idle:" + next?.idString) },
        transitionSpec = {
            (fadeIn(HiFiMotion.large()) + slideInVertically(HiFiMotion.large()) { it / 8 }) togetherWith fadeOut(HiFiMotion.quick())
        },
        modifier = Modifier.fillMaxWidth().height(TrackInfoHeight),
        label = "wave_track"
    ) { (shown, next) ->
        if (shown == null) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                if (next == null) {
                    Text(stringResource(if (wave) R.string.wave_loading else R.string.home_queue_empty).uppercase(), style = HiFiType.SectionCaps)
                    return@Column
                }
                Text(stringResource(R.string.wave_starts_with).uppercase(), style = HiFiType.SectionCaps)
                Text(
                    text = listOfNotNull(next.title, next.artists.firstOrNull()?.name).joinToString(" · "),
                    color = Color.White,
                    fontSize = 22.sp,
                    lineHeight = 28.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.02).em,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                GlassCircleButton(onClick = onDislike, contentDescription = stringResource(R.string.cd_ne_nravitsya), size = 48.dp) {
                    Icon(Icons.Rounded.HeartBroken, contentDescription = null, tint = Color.White.copy(alpha = 0.86f), modifier = Modifier.size(21.dp))
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = shown.title ?: stringResource(R.string.bez_nazvaniya),
                        color = Color.White,
                        fontSize = 26.sp,
                        lineHeight = 32.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-0.02).em,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = shown.artists.joinToString { it.name ?: "" },
                        color = Color.White.copy(alpha = 0.70f),
                        fontSize = 17.sp,
                        lineHeight = 22.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
                GlassCircleButton(onClick = onLike, contentDescription = stringResource(R.string.cd_nravitsya), size = 48.dp) {
                    Icon(
                        if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        contentDescription = null,
                        tint = if (isLiked) HiFi.Love else Color.White.copy(alpha = 0.86f),
                        modifier = Modifier.size(21.dp)
                    )
                }
            }
        }
    }
}

/**
 * "Дальше в волне" / a queue's "Дальше": always in place, with the same height whether it shows tracks,
 * placeholders or nothing (a queue at its last track).
 */
@Composable
private fun WaveShelf(
    state: WaveShelfState,
    wave: Boolean,
    shimmer: Boolean,
    onOpenPlayer: (() -> Unit)?,
    onClick: (TrackDetailsDto) -> Unit,
    horizontalPadding: Dp,
    cardSize: Dp
) {
    Column {
        Spacer(Modifier.height(14.dp))
        HiFiSectionHeader(
            text = stringResource(if (wave) R.string.wave_next_up else R.string.home_next_up),
            modifier = Modifier.padding(start = horizontalPadding, end = (horizontalPadding - 8.dp).coerceAtLeast(0.dp))
        ) {
            // Opens the full-screen player; hidden while there is nothing to open
            if (onOpenPlayer != null) {
                HiFiTextAction(text = stringResource(R.string.wave_player), onClick = onOpenPlayer)
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
            } else {
                // Keeps the header as tall as with the action, so the shelf does not shift
                Spacer(Modifier.height(44.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
        when {
            state.loading -> SkeletonRow(cardSize, horizontalPadding, animated = shimmer && !LocalHiFiLite.current)
            // Exactly a card's height (cover + 8 + 18 + 17): the knob does not move when the shelf runs out
            state.shelf.isEmpty() -> Spacer(Modifier.height(cardSize + 43.dp))
            else -> LazyRow(
                contentPadding = PaddingValues(horizontal = horizontalPadding),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Keyed by place, not by track: a queue of the user's own may hold the same track twice
                itemsIndexed(state.shelf, key = { index, track -> "$index:${track.idString}" }, contentType = { _, _ -> "up_next" }) { _, track ->
                    UpNextCard(track, cardSize) { onClick(track) }
                }
            }
        }
    }
}

@Composable
private fun UpNextCard(track: TrackDetailsDto, size: Dp, onClick: () -> Unit) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .width(size)
            .hifiPress(onClick)
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context).cover(track.coverUri, CoverSize.Medium).crossfade(true).size(320).build(),
            contentDescription = track.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size)
                .hifiSoftShadow(RoundedCornerShape(14.dp), 16.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(HiFi.Placeholder)
        )
        Spacer(Modifier.height(8.dp))
        Text(track.title ?: stringResource(R.string.bez_nazvaniya), color = Color.White, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(track.artists.joinToString { it.name ?: "" }, color = HiFi.TextSecondary, fontSize = 13.sp, lineHeight = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Placeholders in the exact size of the cards. The shimmer is read only while drawing (no
 * recomposition per frame) and runs only while the wave is really being fetched.
 */
@Composable
private fun SkeletonRow(cardSize: Dp, horizontalPadding: Dp, animated: Boolean) {
    val loadingText = stringResource(R.string.wave_loading)
    val phase: State<Float>? = if (animated) {
        rememberInfiniteTransition(label = "wave_skeleton").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
            label = "wave_skeleton_phase"
        )
    } else null
    val base = remember { Color.White.copy(alpha = 0.06f) }
    val bright = remember { Color.White.copy(alpha = 0.14f) }
    // As many placeholders as cards would fit, so a wide screen is not left with a gap at the end
    val count = (((currentWindowSizeDp().widthDp.dp - horizontalPadding) / (cardSize + 12.dp)).toInt() + 1).coerceIn(3, 8)
    Row(
        modifier = Modifier
            .padding(start = horizontalPadding)
            .semantics { contentDescription = loadingText },
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        repeat(count) {
            Column(Modifier.width(cardSize)) {
                // Exactly a card's height (cover + 8 + 18 + 17): the shelf doesn't jump when tracks arrive
                SkeletonBlock(Modifier.size(cardSize), 14.dp, phase, base, bright)
                Spacer(Modifier.height(11.dp))
                SkeletonBlock(Modifier.width(cardSize * 0.72f).height(12.dp), 6.dp, phase, base, bright)
                Spacer(Modifier.height(7.dp))
                SkeletonBlock(Modifier.width(cardSize * 0.48f).height(10.dp), 5.dp, phase, base, bright)
                Spacer(Modifier.height(3.dp))
            }
        }
    }
}

@Composable
private fun SkeletonBlock(modifier: Modifier, radius: Dp, phase: State<Float>?, base: Color, bright: Color) {
    Box(
        modifier.drawWithCache {
            val r = CornerRadius(radius.toPx())
            val w = size.width
            onDrawBehind {
                val p = phase?.value
                if (p == null) {
                    drawRoundRect(base, cornerRadius = r)
                } else {
                    val x = -w + p * 3f * w
                    drawRoundRect(
                        Brush.linearGradient(listOf(base, bright, base), start = Offset(x, 0f), end = Offset(x + w, 0f)),
                        cornerRadius = r
                    )
                }
            }
        }
    )
}
