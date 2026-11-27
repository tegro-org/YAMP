package org.tegro.yamp.presentation.hifi

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import org.tegro.yamp.R
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Timeline arithmetic shared by the player and the car screen. Pure; unit-tested. */
object TimelineMath {
    /** Played part 0..1; 0 for an unknown duration. */
    fun fraction(positionMs: Long, durationMs: Long): Float =
        if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

    /** The finger at [x] on a track [width] wide → fraction 0..1. */
    fun fractionAt(x: Float, width: Float): Float = if (width <= 0f) 0f else (x / width).coerceIn(0f, 1f)

    /** Where the track is sought to when the finger is lifted at [fraction]. */
    fun seekPosition(fraction: Float, durationMs: Long): Long =
        if (durationMs <= 0L) 0L else (fraction.coerceIn(0f, 1f) * durationMs).toLong()

    /** A change of the position larger than this is a jump (new track, seek), not playback. */
    const val JUMP = 0.03f

    /** "1:22" — minutes without a leading zero, as on the player. */
    fun clock(ms: Long): String {
        val total = (ms / 1000L).coerceAtLeast(0L)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        return if (hours > 0) String.format(java.util.Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        else String.format(java.util.Locale.ROOT, "%d:%02d", minutes, seconds)
    }

    /** The two labels under the player timeline: elapsed and "−remaining". */
    fun readout(positionMs: Long, durationMs: Long): Pair<String, String> {
        val pos = positionMs.coerceIn(0L, durationMs.coerceAtLeast(0L))
        return clock(pos) to "−" + clock((durationMs - pos).coerceAtLeast(0L))
    }
}

/**
 * The Hi-Fi timeline. At rest a thin recessed groove with a white played part (a highlight runs along
 * it while music plays) and a small metal bead; under the finger the groove thickens and the bead grows
 * on a spring, the track is sought where the finger is lifted. A new track or a seek glides instead of
 * jumping. Everything is read and drawn in the draw phase: nothing recomposes while it moves.
 * Sizes are in "units" ([unit] each): 48 tall, groove 6→12, bead 18→32 — the car scales them up.
 * [onScrub] gets the finger position while it is down and null when it is lifted. [buffered] (ms loaded, or
 * [Long.MAX_VALUE] when the whole file is on disk) is drawn as a lighter part ahead of the played one (D81).
 * [seekLock] (canvas 5e): non-null while seeking waits for the track's whole download (D91) — the bead is a hollow
 * ring, the lighter part is the share downloaded (0: not known yet, no percent), a touch does not move it (it flashes
 * and asks [onSeek], which refuses with a message); when the download is complete the ring fills into the bead.
 */
@Composable
fun HiFiTimeline(
    progress: () -> Long,
    durationMs: Long,
    isPlaying: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    unit: Dp = 1.dp,
    height: Float = 48f,
    onScrub: (Float?) -> Unit = {},
    buffered: () -> Long = { 0L },
    seekLock: () -> Float? = { null }
) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val lite = LocalHiFiLite.current
    val duration by rememberUpdatedState(durationMs)
    val seek by rememberUpdatedState(onSeek)
    val scrub by rememberUpdatedState(onScrub)
    val lockOf by rememberUpdatedState(seekLock)
    val lockPercent by remember { derivedStateOf { lockOf()?.let { (it * 100).toInt() } } }
    // 0 = the hollow ring (seeking waits for the copy), 1 = the metal bead; the ring fills in ≈150 ms
    val unlock = animateFloatAsState(if (lockPercent != null) 0f else 1f, tween(LOCK_FILL_MS, easing = FastOutSlowInEasing), label = "tl_unlock")
    // A touch while locked: the ring flashes the accent once
    val flash = remember { Animatable(0f) }
    fun u(v: Float): Dp = unit * v

    var touching by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    // The finger's own seek must not glide: the next progress seen after it is the target, not a jump
    val seekByFinger = remember { mutableStateOf(false) }
    // A jump (new track, a seek from elsewhere) glides: the offset from the old position springs back to 0
    val glide = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        var last = TimelineMath.fraction(progress(), duration)
        snapshotFlow { TimelineMath.fraction(progress(), duration) }.collect { now ->
            val jump = last - now
            last = now
            if (seekByFinger.value) {
                seekByFinger.value = false
                return@collect
            }
            if (!touching && abs(jump) > TimelineMath.JUMP) {
                launch {
                    glide.snapTo((glide.value + jump).coerceIn(-1f, 1f))
                    glide.animateTo(0f, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow))
                }
            }
        }
    }

    val groove by animateFloatAsState(if (touching) 12f else 6f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow), label = "tl_groove")
    val bead by animateFloatAsState(if (touching) 32f else 18f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow), label = "tl_bead")
    // The running highlight: only while music plays and effects are on
    val sheen: State<Float>? = if (isPlaying && !lite) {
        rememberInfiniteTransition(label = "tl_sheen").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(2800, easing = LinearEasing), RepeatMode.Restart),
            label = "tl_sheen_phase"
        )
    } else null

    val shown = {
        if (touching) dragFraction else (TimelineMath.fraction(progress(), duration) + glide.value).coerceIn(0f, 1f)
    }
    val percent by remember { derivedStateOf { (shown() * 100).toInt() } }
    val description = lockPercent?.let {
        if (it > 0) stringResource(R.string.seek_locked_a11y, it) else stringResource(R.string.seek_locked_a11y_no_percent)
    } ?: stringResource(R.string.car_timeline)

    Box(
        modifier
            .fillMaxWidth()
            .height(u(height))
            .semantics {
                contentDescription = description
                progressBarRangeInfo = ProgressBarRangeInfo(percent / 100f, 0f..1f)
                setProgress { target ->
                    seek(TimelineMath.seekPosition(target, duration))
                    true
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    if (duration <= 0L) return@awaitEachGesture
                    if (lockOf() != null) {
                        // Seeking waits for the copy: the bead stays, one refusal (with its message) per touch
                        scope.launch {
                            flash.snapTo(1f)
                            flash.animateTo(0f, tween(LOCK_FLASH_MS))
                        }
                        view.performHapticFeedback(if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.CONTEXT_CLICK)
                        seek(TimelineMath.seekPosition(TimelineMath.fractionAt(down.position.x, size.width.toFloat()), duration))
                        do {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.id == down.id && it.pressed })
                        return@awaitEachGesture
                    }
                    touching = true
                    dragFraction = TimelineMath.fractionAt(down.position.x, size.width.toFloat())
                    scrub(dragFraction)
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    var lastTick = (dragFraction * 20).toInt()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        if (!change.pressed) break
                        dragFraction = TimelineMath.fractionAt(change.position.x, size.width.toFloat())
                        scrub(dragFraction)
                        val tick = (dragFraction * 20).toInt()
                        if (tick != lastTick) {
                            lastTick = tick
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }
                    }
                    val target = dragFraction
                    scope.launch { glide.snapTo(0f) }
                    seekByFinger.value = true
                    seek(TimelineMath.seekPosition(target, duration))
                    view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    touching = false
                    scrub(null)
                }
            }
            .drawWithCache {
                val rest = Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.White.copy(alpha = 0.10f)))
                val playedFill = Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.55f), Color.White))
                val sheenWidth = u(120f).toPx()
                onDrawBehind {
                    val h = u(groove).toPx()
                    val top = (size.height - h) / 2f
                    val r = CornerRadius(h / 2f)
                    val f = shown()
                    val playedW = (size.width * f).coerceAtLeast(h)
                    val lock = lockOf()
                    // Groove
                    drawRoundRect(rest, Offset(0f, top), Size(size.width, h), r)
                    drawRoundRect(Color.Black.copy(alpha = 0.55f), Offset(0f, top), Size(size.width, h), r, style = Stroke(1f))
                    // Loaded part ahead of the played one (only while nobody drags)
                    if (lock != null) {
                        // The copy's share, with a soft leading edge while it grows
                        val loadedW = size.width * lock
                        if (loadedW > 0f) {
                            val growing = Brush.horizontalGradient(0f to Color.White.copy(alpha = 0.30f), 0.85f to Color.White.copy(alpha = 0.30f), 1f to Color.White.copy(alpha = 0.55f), startX = 0f, endX = loadedW)
                            drawRoundRect(growing, Offset(0f, top), Size(loadedW, h), r)
                        }
                    } else if (!touching) {
                        val loaded = org.tegro.yamp.presentation.player.bufferedFraction(buffered(), progress(), duration)
                        if (loaded > f) drawRoundRect(Color.White.copy(alpha = 0.30f), Offset(0f, top), Size(size.width * loaded, h), r)
                    }
                    // Played part with the running highlight (dimmed a little while seeking waits)
                    drawRoundRect(playedFill, Offset(0f, top), Size(playedW, h), r, alpha = if (lock != null) LOCKED_PLAYED_ALPHA else 1f)
                    if (lock == null) sheen?.value?.let { p ->
                        val x = -sheenWidth + p * (playedW + sheenWidth)
                        clipRect(0f, top, playedW, top + h) {
                            drawRect(
                                Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0f), Color.White.copy(alpha = 0.85f), Color.White.copy(alpha = 0f)), startX = x, endX = x + sheenWidth),
                                Offset(x, top),
                                Size(sheenWidth, h)
                            )
                        }
                    }
                    // Metal bead — or, while seeking waits, the hollow ring it fills from
                    val beadR = u(bead).toPx() / 2f
                    val c = Offset((size.width * f).coerceIn(beadR, size.width - beadR), size.height / 2f)
                    val metal = unlock.value
                    val lit = flash.value
                    drawCircle(Color.Black.copy(alpha = 0.45f), beadR + u(3f).toPx(), c + Offset(0f, u(3f).toPx()))
                    if (lit > 0f) drawCircle(HiFi.Accent.copy(alpha = 0.28f * lit), beadR + u(6f).toPx(), c)
                    if (metal < 1f) drawCircle(HiFi.Ground, beadR, c, alpha = 1f - metal)
                    if (metal > 0f) {
                        drawCircle(Brush.sweepGradient(HiFi.MetalSweep, center = c), beadR, c, alpha = metal)
                        drawCircle(
                            Brush.radialGradient(listOf(Color(0xFF3A3F4B), HiFi.AnodizedDark), center = c + Offset(0f, -beadR * 0.4f), radius = beadR * 1.3f),
                            beadR - u(1.5f).toPx(),
                            c,
                            alpha = metal
                        )
                    }
                    if (metal < 1f) {
                        val ring = androidx.compose.ui.graphics.lerp(Color.White.copy(alpha = 0.62f), HiFi.Accent, lit)
                        val stroke = u(2f).toPx()
                        drawCircle(ring, beadR - stroke / 2f, c, alpha = 1f - metal, style = Stroke(stroke))
                    }
                }
            }
    )
}

private const val LOCK_FILL_MS = 150
private const val LOCK_FLASH_MS = 600
private const val LOCKED_PLAYED_ALPHA = 0.75f

/** A live stream has no timeline: a quiet line with the blinking on-air mark in the middle. */
@Composable
fun HiFiLiveLine(modifier: Modifier = Modifier, unit: Dp = 1.dp, height: Float = 48f) {
    val lite = LocalHiFiLite.current
    val blink: State<Float>? = if (!lite) {
        rememberInfiniteTransition(label = "live").animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
            label = "live_alpha"
        )
    } else null
    Row(
        modifier.fillMaxWidth().height(unit * height),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(unit * 14f)
    ) {
        LiveGroove(Modifier.weight(1f).height(unit * 6f), fadeToRight = false)
        Box(
            Modifier.size(unit * 9f).drawWithCache {
                onDrawBehind { drawCircle(HiFi.Live.copy(alpha = blink?.value ?: 1f)) }
            }
        )
        Text(
            stringResource(R.string.radio_on_air),
            color = Color.White,
            fontSize = (13f * unit.value).sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.14.em
        )
        LiveGroove(Modifier.weight(1f).height(unit * 6f), fadeToRight = true)
    }
}

@Composable
private fun LiveGroove(modifier: Modifier, fadeToRight: Boolean) {
    Spacer(
        modifier.drawWithCache {
            val colors = listOf(Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.30f))
            val brush = Brush.horizontalGradient(if (fadeToRight) colors.reversed() else colors)
            onDrawBehind { drawRoundRect(brush, cornerRadius = CornerRadius(size.height / 2f)) }
        }
    )
}
