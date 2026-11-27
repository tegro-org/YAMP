package org.tegro.yamp.presentation.car

import androidx.compose.ui.unit.dp

/** Time source of the car layout's clock; screenshot tests pin it. */
val LocalCarClock = androidx.compose.runtime.staticCompositionLocalOf<() -> Long> { System::currentTimeMillis }

/** How far Android's "font size" may grow the car layout's own, already large, type: not at all. */
internal const val CAR_MAX_FONT_SCALE = 1f

/** How far a finger travels across the car layout to switch a track. */
internal val CarSwipeThreshold = 72.dp

/** A swipe's result: 1 — back a track (to the right), -1 — on to the next (to the left), 0 — too short. */
internal fun carSwipeStep(travelledPx: Float, thresholdPx: Float): Int = when {
    travelledPx >= thresholdPx -> 1
    travelledPx <= -thresholdPx -> -1
    else -> 0
}

/** The car pickers' scale for a screen: the 1280×720 design lying, 600×1200 standing (by the height the bars leave). */
internal fun carScaleOf(maxWidth: androidx.compose.ui.unit.Dp, maxHeight: androidx.compose.ui.unit.Dp, usableHeight: androidx.compose.ui.unit.Dp, isLandscape: Boolean): Float {
    val s = if (isLandscape) minOf(maxWidth / 1280.dp, maxHeight / 720.dp) else minOf(maxWidth / 600.dp, usableHeight / 1200.dp)
    return s.coerceIn(0.55f, 2f)
}
