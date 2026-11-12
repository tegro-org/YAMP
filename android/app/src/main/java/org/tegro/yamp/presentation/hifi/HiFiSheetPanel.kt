package org.tegro.yamp.presentation.hifi

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.animation.core.Animatable
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Which edge a landscape panel is attached to: the driver's side in the car, the right on a phone on its side. */
enum class PanelSide { LEFT, RIGHT }

/**
 * The app's sheet-or-panel container (DESIGN_GUIDE §3 "Sheet / panel"): a bottom sheet in portrait — handle,
 * pull-down by the handle to close, scrim, predictive back — and a side panel on [side] in landscape. A
 * window of its own ([AppDialog]), so it keeps the app's scale and sits over everything, the player included.
 *
 * [content] fills a column; a list inside takes `Modifier.weight(1f)`. The sheet is [sheetHeight] of the
 * screen (null: as tall as its content), the panel [panelWidth] wide. Nothing scrolls here: the content decides.
 */
@Composable
fun HiFiSheetPanel(
    onDismiss: () -> Unit,
    side: PanelSide = PanelSide.RIGHT,
    sheetHeight: Float? = 0.78f,
    panelWidth: Dp = 460.dp,
    content: @Composable ColumnScope.(landscape: Boolean) -> Unit
) {
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    LaunchedEffect(visible.currentState, visible.targetState) {
        if (!visible.targetState && visible.isIdle) onDismiss()
    }
    val close = { visible.targetState = false }
    val back = rememberPredictiveBack(enabled = visible.targetState, onBack = close)
    val fromRight = side == PanelSide.RIGHT
    AppDialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize()) {
            AnimatedVisibility(visibleState = visible, enter = fadeIn(HiFiMotion.standard()), exit = fadeOut(HiFiMotion.standard())) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(if (isLandscape) HiFi.PanelScrim else HiFi.SheetScrim)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = close)
                )
            }
            AnimatedVisibility(
                visibleState = visible,
                enter = when {
                    !isLandscape -> slideInVertically(HiFiMotion.large()) { it }
                    fromRight -> slideInHorizontally(HiFiMotion.large()) { it }
                    else -> slideInHorizontally(HiFiMotion.large()) { -it }
                },
                exit = when {
                    !isLandscape -> slideOutVertically(HiFiMotion.standard()) { it }
                    fromRight -> slideOutHorizontally(HiFiMotion.standard()) { it }
                    else -> slideOutHorizontally(HiFiMotion.standard()) { -it }
                },
                modifier = Modifier.align(
                    when {
                        !isLandscape -> Alignment.BottomCenter
                        fromRight -> Alignment.CenterEnd
                        else -> Alignment.CenterStart
                    }
                )
            ) {
                if (isLandscape) {
                    SidePanel(panelWidth, back, content)
                } else {
                    BottomSheet(sheetHeight, back, close, content)
                }
            }
        }
    }
}

@Composable
private fun SidePanel(width: Dp, back: Animatable<Float, *>, content: @Composable ColumnScope.(Boolean) -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Column(
        Modifier
            .graphicsLayer { alpha = 1f - 0.4f * back.value }
            .width(width)
            .fillMaxHeight()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(12.dp)
            .clip(shape)
            .background(HiFi.SheetFill)
            .border(1.dp, HiFi.Hairline, shape)
            .pointerInput(Unit) { detectTapGestures { } }
    ) {
        content(true)
    }
}

@Composable
private fun BottomSheet(height: Float?, back: Animatable<Float, *>, close: () -> Unit, content: @Composable ColumnScope.(Boolean) -> Unit) {
    // Pull the sheet down by its handle to close it; the predictive gesture lowers it under the finger
    val density = LocalDensity.current
    val drag = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val closeAt = with(density) { 120.dp.toPx() }
    val dismiss by rememberUpdatedState(close)
    Column(
        Modifier
            .graphicsLayer { translationY = back.value * 160.dp.toPx() }
            .offset { IntOffset(0, drag.value.roundToInt()) }
            .widthIn(max = 640.dp)
            .fillMaxWidth()
            .then(if (height != null) Modifier.fillMaxHeight(height) else Modifier)
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(HiFi.SheetFill)
            .pointerInput(Unit) { detectTapGestures { } }
            .navigationBarsPadding()
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { delta -> scope.launch { drag.snapTo((drag.value + delta).coerceAtLeast(0f)) } },
                    onDragStopped = {
                        if (drag.value > closeAt) dismiss()
                        else drag.animateTo(0f, spring(stiffness = Spring.StiffnessMedium))
                    }
                )
                .padding(top = 10.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            Box(
                Modifier
                    .size(width = 40.dp, height = 5.dp)
                    .clip(CircleShape)
                    .background(HiFi.SheetHandle)
            )
            Spacer(Modifier.height(5.dp))
        }
        content(false)
    }
}
