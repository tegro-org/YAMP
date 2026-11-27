package org.tegro.yamp.presentation.main.components

/** What one tap on the version row asks for (D101). */
data class DeveloperTap(
    /** The 4th to 6th tap: taps still to go, for the toast ("3 more taps…"). */
    val remaining: Int? = null,
    /** The 7th tap: the Developer section is now on. */
    val unlocked: Boolean = false,
    /** The section is shown already: the tap only checks for updates, at once. */
    val checkNow: Boolean = false,
    /** Check for updates after [DeveloperTaps.CHECK_DELAY_MS] unless another tap comes first. */
    val checkLater: Boolean = false
)

/**
 * The seven taps on the version row that open Settings → Developer. A tap still checks for updates, but only once no
 * further tap has followed within [CHECK_DELAY_MS]; the count starts over after [RESET_MS] without a tap.
 */
class DeveloperTaps {
    private var count = 0
    private var lastAt = 0L

    fun onTap(nowMs: Long, sectionShown: Boolean): DeveloperTap {
        if (sectionShown) return DeveloperTap(checkNow = true)
        count = if (count == 0 || nowMs - lastAt > RESET_MS) 1 else count + 1
        lastAt = nowMs
        if (count >= TAPS) {
            count = 0
            return DeveloperTap(unlocked = true)
        }
        return DeveloperTap(remaining = (TAPS - count).takeIf { count >= TOAST_FROM }, checkLater = true)
    }

    companion object {
        const val TAPS = 7
        const val TOAST_FROM = 4
        const val CHECK_DELAY_MS = 500L
        const val RESET_MS = 3_000L
    }
}
