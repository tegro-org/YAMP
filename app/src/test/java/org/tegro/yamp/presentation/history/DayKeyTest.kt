package org.tegro.yamp.presentation.history

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** The day groups of the history follow the local calendar, not UTC. */
class DayKeyTest {
    private val moscow = ZoneId.of("Europe/Moscow")

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, moscow).toInstant().toEpochMilli()

    private val now = at(2026, 11, 12, 12)

    @Test
    fun todayYesterdayAndOtherDays() {
        assertEquals(DayKey.Today, DayKey.of(at(2026, 11, 12, 0, 1), now, moscow))
        assertEquals(DayKey.Yesterday, DayKey.of(at(2026, 11, 11, 23, 59), now, moscow))
        assertEquals(DayKey.Date(LocalDate.of(2026, 11, 3), thisYear = true), DayKey.of(at(2026, 11, 3, 18), now, moscow))
        assertEquals(DayKey.Date(LocalDate.of(2025, 12, 31), thisYear = false), DayKey.of(at(2025, 12, 31, 23), now, moscow))
    }

    @Test
    fun theDayIsTheLocalOneNotTheUtcOne() {
        // 23:30 and 01:00 Moscow are both 11 November in UTC; the user went to bed in between
        assertEquals(DayKey.Yesterday, DayKey.of(at(2026, 11, 11, 23, 30), at(2026, 11, 12, 1), moscow))
    }
}
