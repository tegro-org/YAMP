package org.tegro.yamp.presentation.history

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The day group of a history row: named in the UI (D2), computed here from the local calendar. */
sealed interface DayKey {
    data object Today : DayKey
    data object Yesterday : DayKey

    /** Any other day; [thisYear] lets the UI leave the year out. */
    data class Date(val date: LocalDate, val thisYear: Boolean) : DayKey

    companion object {
        fun of(startedAtMs: Long, nowMs: Long, zone: ZoneId): DayKey {
            val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
            val day = Instant.ofEpochMilli(startedAtMs).atZone(zone).toLocalDate()
            return when (day) {
                today -> Today
                today.minusDays(1) -> Yesterday
                else -> Date(day, day.year == today.year)
            }
        }
    }
}
