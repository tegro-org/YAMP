package org.tegro.yamp.presentation.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.tegro.yamp.R
import org.tegro.yamp.domain.player.NextTrack
import org.tegro.yamp.presentation.hifi.HiFi

/** What the Next button shows (D81): it is never disabled, a tap on any state skips (load-first, D67). */
enum class NextLook { READY, LOADING, FAILED }

fun NextTrack.look(): NextLook = when (this) {
    is NextTrack.Loading -> NextLook.LOADING
    is NextTrack.Failed -> NextLook.FAILED
    is NextTrack.Ready, NextTrack.None -> NextLook.READY
}

/**
 * The next track's state and the current track's loaded part, for every Next button and progress bar below
 * the screen that provides them (MainScreen). Defaults draw today's look: ready, nothing extra buffered.
 */
class PlaybackAux(
    val next: () -> NextLook,
    val buffered: () -> Long,
    /** Seeking waits for the disk copy: null when it seeks, else the copy's share on disk 0..1 (canvas 5e). */
    val seekLock: () -> Float? = { null }
)

val LocalPlaybackAux = compositionLocalOf { PlaybackAux(next = { NextLook.READY }, buffered = { 0L }) }

/** How much of the bar is loaded, never less than what has played ([buffered] = [Long.MAX_VALUE]: all of it). */
fun bufferedFraction(bufferedMs: Long, positionMs: Long, durationMs: Long): Float {
    if (durationMs <= 0L) return 0f
    val loaded = bufferedMs.coerceAtMost(durationMs).coerceAtLeast(positionMs)
    return (loaded.toFloat() / durationMs).coerceIn(0f, 1f)
}

@Composable
fun nextLookLabel(look: NextLook): String = stringResource(
    when (look) {
        NextLook.READY -> R.string.next_state_ready
        NextLook.LOADING -> R.string.next_state_loading
        NextLook.FAILED -> R.string.next_state_failed
    }
)

private const val RING_ARC = 110f
private const val FAILED_ALPHA = 0.45f
private const val RING_TURN_MS = 1100
private const val FADE_MS = 220

/**
 * Wraps a Next control of [size]: loading draws a turning ring around it, failed dims it and adds a small
 * retry badge. Only decoration — the wrapped control stays the one tap target (the badge takes no touches).
 * The slot is the control's own size in every state: the ring and the badge are always composed, laid out
 * over the control (they draw past its bounds, never resize it) and only fade in and out, so nothing around
 * the button moves when the state changes. [ring] = false draws no ring around (the car artwork puts its
 * ring inside, via [NextCoverOverlay]).
 */
@Composable
fun NextStateDecor(look: NextLook, size: Dp, ring: Boolean = true, content: @Composable () -> Unit) {
    val ringAlpha = animateFloatAsState(if (look == NextLook.LOADING && ring) 1f else 0f, tween(FADE_MS), label = "next_ring_alpha")
    val failed = animateFloatAsState(if (look == NextLook.FAILED) 1f else 0f, tween(FADE_MS), label = "next_failed")
    Box(contentAlignment = Alignment.Center) {
        Box(Modifier.graphicsLayer { alpha = 1f - (1f - FAILED_ALPHA) * failed.value }) { content() }
        // matchParentSize: these never take part in the Box's measuring, the content alone sizes the slot
        Box(Modifier.matchParentSize().wrapContentSize(unbounded = true), contentAlignment = Alignment.Center) {
            LoadingRing(Modifier.requiredSize(size + size * 0.2f), stroke = (size * 0.045f).coerceAtLeast(2.5.dp), alpha = { ringAlpha.value })
        }
        Box(Modifier.matchParentSize().wrapContentSize(Alignment.BottomEnd, unbounded = true)) {
            RetryBadge(size, Modifier.offset(x = size * 0.06f, y = size * 0.06f).graphicsLayer { alpha = failed.value })
        }
    }
}

/** The car's next-track artwork overlay: a ring inside the cover while loading, a retry mark when failed. */
@Composable
fun NextCoverOverlay(look: NextLook, size: Dp) {
    val ringAlpha = animateFloatAsState(if (look == NextLook.LOADING) 1f else 0f, tween(FADE_MS), label = "cover_ring_alpha")
    val failed = animateFloatAsState(if (look == NextLook.FAILED) 1f else 0f, tween(FADE_MS), label = "cover_failed")
    Box(contentAlignment = Alignment.Center) {
        LoadingRing(Modifier.size(size * 0.5f), stroke = 3.5.dp, alpha = { ringAlpha.value })
        Icon(
            Icons.Rounded.Refresh, contentDescription = null, tint = HiFi.Warning,
            modifier = Modifier.size(size * 0.46f).graphicsLayer { alpha = failed.value }
        )
    }
}

/**
 * A turning arc; always composed, [alpha] read at draw time only. The turn runs only while it shows, so a
 * hidden ring costs no frames.
 */
@Composable
private fun LoadingRing(modifier: Modifier, stroke: Dp, alpha: () -> Float, color: Color = HiFi.Accent) {
    val turn = remember { Animatable(0f) }
    val shown by remember { derivedStateOf { alpha() > 0f } }
    LaunchedEffect(shown) {
        if (shown) {
            while (true) {
                turn.animateTo(turn.value + 360f, tween(RING_TURN_MS, easing = LinearEasing))
                turn.snapTo(turn.value % 360f)
            }
        }
    }
    Box(
        modifier.drawBehind {
            val a = alpha()
            if (a <= 0f) return@drawBehind
            val w = stroke.toPx()
            val inset = w / 2f
            val arcSize = Size(size.width - w, size.height - w)
            drawCircle(Color.White.copy(alpha = 0.12f * a), radius = size.minDimension / 2f - inset, style = Stroke(w))
            rotate(turn.value) {
                drawArc(color, -90f, RING_ARC, false, Offset(inset, inset), arcSize, alpha = a, style = Stroke(w, cap = StrokeCap.Round))
            }
        }
    )
}

@Composable
private fun RetryBadge(size: Dp, modifier: Modifier) {
    val badge = (size * 0.34f).coerceAtLeast(18.dp)
    Box(
        modifier
            .size(badge)
            .background(HiFi.Warning, CircleShape)
            .border(2.dp, HiFi.Ground, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Rounded.Refresh, contentDescription = null, tint = Color(0xFF1A1200), modifier = Modifier.size(badge * 0.66f))
    }
}
