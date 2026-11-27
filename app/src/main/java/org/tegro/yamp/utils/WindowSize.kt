package org.tegro.yamp.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntSize

/** Size of the app window in dp (multi-window and foldable aware, unlike Configuration.screenWidthDp). */
data class WindowSizeDp(val widthDp: Int, val heightDp: Int)

/**
 * The size the app really draws in, in pixels, measured at its root (MainActivity); [IntSize.Zero] where there is
 * no such root (a test of one screen). What the system says of the window can be stale: a VW Golf unit (Q8MAX,
 * Android 15) showing the app in its launcher's small standing card reported that card again a second after the
 * app was opened full screen, and every page took the standing layout on the lying screen.
 */
val LocalAppWindowSize = staticCompositionLocalOf { IntSize.Zero }

/** The app's window in pixels: as drawn where known, else as the system says. */
@Composable
fun currentWindowSizePx(): IntSize {
    val drawn = LocalAppWindowSize.current
    if (drawn.width > 0 && drawn.height > 0) return drawn
    return LocalWindowInfo.current.containerSize
}

@Composable
fun currentWindowSizeDp(): WindowSizeDp {
    val size = currentWindowSizePx()
    val density = LocalDensity.current.density
    return WindowSizeDp((size.width / density).toInt(), (size.height / density).toInt())
}
