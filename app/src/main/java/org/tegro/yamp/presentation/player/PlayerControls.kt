package org.tegro.yamp.presentation.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.outlined.HeartBroken
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.semantics
import org.tegro.yamp.presentation.hifi.hifiGlass
import kotlinx.coroutines.launch
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.tegro.yamp.R
import org.tegro.yamp.presentation.hifi.HiFi
import org.tegro.yamp.presentation.hifi.HiFiKnob
import org.tegro.yamp.presentation.hifi.HiFiMotion
import org.tegro.yamp.presentation.hifi.PlayPauseGlyph

// --- Controls ---------------------------------------------------------------------------------------

/** Prev · Play · Next in portrait, in dp: the knobs and the gap between them. */
internal data class PortraitTransport(val skip: Float, val play: Float, val gap: Float) {
    val total: Float get() = skip * 2 + play + gap * 2
}

/**
 * Sizes of the portrait transport row for a row [widthDp] wide. Pure; unit-tested.
 *
 * The gap grows with the width (about 19 dp on a 320 dp row, at most 40 dp), so the three knobs never sit glued
 * together now that like/dislike live beside the title. A row too narrow for them scales the whole row down, never
 * below 60 %; wide rows (the Fold's inner screen) keep the base sizes — nothing grows past them.
 */
internal fun portraitTransport(widthDp: Float): PortraitTransport {
    val gap = (widthDp * GAP_SHARE).coerceIn(GAP_MIN, GAP_MAX)
    val base = PortraitTransport(skip = 68f, play = 96f, gap = gap)
    val k = (widthDp / base.total).coerceIn(MIN_SCALE, 1f)
    return if (k >= 1f) base else PortraitTransport(base.skip * k, base.play * k, base.gap * k)
}

private const val GAP_SHARE = 0.06f
private const val GAP_MIN = 16f
private const val GAP_MAX = 40f
private const val MIN_SCALE = 0.6f

/**
 * Metal previous · large metal play · metal next, with a gap that grows with the width (see [portraitTransport]).
 * Like/dislike are beside the title, not here.
 */
@Composable
internal fun PlayerControls(
    isPlaying: Boolean,
    isLoading: Boolean,
    isRadio: Boolean,
    onPrev: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val sz = portraitTransport(maxWidth.value)
        val skip = sz.skip.dp
        val play = sz.play.dp
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(sz.gap.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HiFiKnob(
                onClick = onPrev,
                contentDescription = stringResource(if (isRadio) R.string.cd_prev_station else R.string.cd_predyduschiy_trek),
                size = skip
            ) {
                Icon(Icons.Rounded.SkipPrevious, contentDescription = null, tint = HiFi.OnMetal, modifier = Modifier.size(skip * 0.44f))
            }
            HiFiKnob(onClick = onPlayPause, contentDescription = stringResource(R.string.cd_vosproizvedenie_ili_pauza), size = play) {
                PlayPauseGlyph(isPlaying = isPlaying, isLoading = isLoading, size = play * 0.42f)
            }
            // Never disabled (D81): loading / failed only decorate it; the whole knob stays the tap target
            val look = if (isRadio) NextLook.READY else LocalPlaybackAux.current.next()
            NextStateDecor(look, size = skip) {
                HiFiKnob(
                    onClick = onNext,
                    contentDescription = if (look == NextLook.READY) {
                        stringResource(if (isRadio) R.string.cd_next_station else R.string.cd_sleduyuschiy_trek)
                    } else {
                        nextLookLabel(look)
                    },
                    size = skip
                ) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = null, tint = HiFi.OnMetal, modifier = Modifier.size(skip * 0.44f))
                }
            }
        }
    }
}

/** A round glass button that sinks a little under the finger (no glow, no shadow band). */
@Composable
private fun GlassPressButton(
    onClick: () -> Unit,
    contentDescription: String,
    size: Dp,
    content: @Composable () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, HiFiMotion.spring(), label = "glass_press")
    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .hifiGlass(CircleShape, fill = Color.White.copy(alpha = 0.06f), border = Color.White.copy(alpha = 0.14f))
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
fun LikeHeartButton(isLiked: Boolean, onToggleLike: () -> Unit, size: Dp = 52.dp) {
    val pop = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    GlassPressButton(
        onClick = {
            if (!isLiked) scope.launch {
                pop.snapTo(1.3f)
                pop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
            }
            onToggleLike()
        },
        contentDescription = stringResource(R.string.cd_nravitsya),
        size = size
    ) {
        Icon(
            imageVector = if (isLiked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            contentDescription = null,
            tint = if (isLiked) HiFi.Love else Color.White.copy(alpha = 0.86f),
            modifier = Modifier
                .size(size * 0.43f)
                .graphicsLayer {
                    scaleX = pop.value
                    scaleY = pop.value
                }
        )
    }
}

@Composable
fun DislikeButton(onDislikeClick: () -> Unit, size: Dp = 52.dp) {
    val flash = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    GlassPressButton(
        onClick = {
            scope.launch {
                flash.snapTo(1f)
                flash.animateTo(0f, tween(450))
            }
            onDislikeClick()
        },
        contentDescription = stringResource(R.string.cd_ne_nravitsya),
        size = size
    ) {
        val base = Color.White.copy(alpha = 0.86f)
        Icon(
            imageVector = Icons.Outlined.HeartBroken,
            contentDescription = null,
            tint = androidx.compose.ui.graphics.lerp(base, HiFi.Danger, flash.value),
            modifier = Modifier
                .size(size * 0.43f)
                .graphicsLayer {
                    val s = 1f + 0.25f * flash.value
                    scaleX = s
                    scaleY = s
                }
        )
    }
}
