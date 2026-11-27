package org.tegro.yamp.utils

import android.util.DisplayMetrics
import org.tegro.yamp.domain.repository.SettingsRepository
import kotlin.math.abs
import kotlin.math.roundToInt

/** What the app read from the screen and the UI scale it chose for an over/under-reported density. */
data class DisplayScaleInfo(
    val widthPx: Int,
    val heightPx: Int,
    val reportedDpi: Int,
    val physicalDpi: Int,
    val autoScale: Float
)

/**
 * Works out a UI-scale multiplier for screens whose reported density does not match their real pixel
 * density — common on car head units ("dense screen, low dpi"), where everything renders too small.
 * The app cannot change the system density, so it scales its own UI instead (see MainActivity).
 */
object DisplayScale {

    /** Below this relative mismatch the reported density is trusted and the scale stays at 1.0. */
    private const val MISMATCH_THRESHOLD = 0.15f

    fun analyze(metrics: DisplayMetrics): DisplayScaleInfo {
        val reportedDpi = metrics.densityDpi
        val physicalDpi = physicalDpi(metrics)
        return DisplayScaleInfo(
            widthPx = metrics.widthPixels,
            heightPx = metrics.heightPixels,
            reportedDpi = reportedDpi,
            physicalDpi = physicalDpi,
            autoScale = autoScale(reportedDpi, physicalDpi, shortSidePx = minOf(metrics.widthPixels, metrics.heightPixels))
        )
    }

    /** Average of the panel's physical x/y dpi, or 0 when the device does not report a usable value. */
    private fun physicalDpi(metrics: DisplayMetrics): Int {
        val valid = listOf(metrics.xdpi, metrics.ydpi).filter { it.isFinite() && it in 60f..1200f }
        if (valid.isEmpty()) return 0
        return (valid.sum() / valid.size).roundToInt()
    }

    /**
     * Scale that makes the UI render at the screen's true density: physical / reported, but only when
     * both are plausible and differ by more than [MISMATCH_THRESHOLD]. Returns 1.0 otherwise, so screens
     * that report their density honestly (virtually every phone) are left untouched. Rounded to 5%.
     */
    fun autoScale(reportedDpi: Int, physicalDpi: Int, shortSidePx: Int = 0): Float {
        if (reportedDpi !in 80..700 || physicalDpi !in 80..700) return SettingsRepository.DEFAULT_UI_SCALE
        val ratio = physicalDpi.toFloat() / reportedDpi.toFloat()
        if (abs(ratio - 1f) < MISMATCH_THRESHOLD) return SettingsRepository.DEFAULT_UI_SCALE
        // A panel's physical dpi can be nonsense (a 1920×720 head unit claims 321 dpi → 200 %): whatever the
        // ratio says, the screen must keep at least MIN_SHORT_SIDE_DP on its short side, or every dialog and
        // settings page outgrows it. 720 px at 160 dpi → at most 150 %.
        val room = if (shortSidePx > 0) shortSidePx / (reportedDpi / 160f) / MIN_SHORT_SIDE_DP else Float.MAX_VALUE
        val rounded = (minOf(ratio, room) * 20f).toInt() / 20f // nearest 0.05, never above the room
        return rounded.coerceIn(SettingsRepository.MIN_UI_SCALE, SettingsRepository.MAX_UI_SCALE)
    }

    /** The short side of the screen must stay at least this many dp after scaling (a small phone's width). */
    const val MIN_SHORT_SIDE_DP = 480f

    /** The scale to apply now: the chosen automatic value, or the manual one. */
    fun effectiveScale(auto: Boolean, manualScale: Float, info: DisplayScaleInfo): Float =
        if (auto) info.autoScale
        else manualScale.coerceIn(SettingsRepository.MIN_UI_SCALE, SettingsRepository.MAX_UI_SCALE)
}
