package com.example.mygarmincaloriestracker

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsPeriodTest {
    private fun date(value: String) = LocalDate.parse(value)

    @Test fun weekIsTrailingInclusiveAcrossYears() {
        val anchor = date("2025-01-03")
        assertEquals(date("2024-12-28") to anchor, StatsPeriod.WEEK.range(anchor))
    }

    @Test fun savedDayAndUnknownPeriodsFallBackToWeek() {
        assertEquals(StatsPeriod.WEEK, StatsPeriod.fromSavedName("DAY"))
        assertEquals(StatsPeriod.WEEK, StatsPeriod.fromSavedName(null))
        assertEquals(StatsPeriod.WEEK, StatsPeriod.fromSavedName("UNKNOWN"))
        assertEquals(StatsPeriod.MONTH, StatsPeriod.fromSavedName("MONTH"))
    }

    @Test fun monthUsesClampedPreviousMonthIncludingBothEndpoints() {
        assertEquals(date("2025-02-28") to date("2025-03-31"), StatsPeriod.MONTH.range(date("2025-03-31")))
        assertEquals(date("2024-02-29") to date("2024-03-31"), StatsPeriod.MONTH.range(date("2024-03-31")))
        assertEquals(date("2024-12-01") to date("2025-01-01"), StatsPeriod.MONTH.range(date("2025-01-01")))
    }

    @Test fun yearUsesInclusiveTwelveMonthWindowAcrossLeapDays() {
        assertEquals(date("2023-03-01") to date("2024-02-29"), StatsPeriod.YEAR.range(date("2024-02-29")))
        assertEquals(date("2024-02-29") to date("2025-02-28"), StatsPeriod.YEAR.range(date("2025-02-28")))
        assertEquals(date("2024-07-16") to date("2025-07-15"), StatsPeriod.YEAR.range(date("2025-07-15")))
    }

    @Test fun navigationMovesAnchorsAndKeepsRangesHistorical() {
        val anchor = date("2025-07-15")
        StatsPeriod.entries.forEach { period ->
            val previous = period.move(anchor, -1)
            assertTrue(previous < anchor)
            assertEquals(previous, period.range(previous).second)
            assertEquals(anchor, period.move(previous, 1))
        }
        assertEquals(date("2025-02-28"), StatsPeriod.MONTH.move(date("2025-03-31"), -1))
        assertEquals(date("2023-02-28"), StatsPeriod.YEAR.move(date("2024-02-29"), -1))
    }

    @Test fun yearBucketsIncludeBothPartialMonthsWithDistinctYears() {
        val (from, to) = StatsPeriod.YEAR.range(date("2025-07-15"))
        val slots = statsMonthSlots(from, to)
        assertEquals(13, slots.size)
        assertEquals(date("2024-07-16") to date("2024-07-31"), slots.first())
        assertEquals(date("2025-07-01") to date("2025-07-15"), slots.last())
        assertEquals(0, statsMonthIndex(from, from))
        assertEquals(1, statsMonthIndex(from, date("2024-08-01")))
        assertEquals(12, statsMonthIndex(from, to))
        slots.forEachIndexed { index, (start, end) ->
            assertEquals(index, statsMonthIndex(from, start))
            assertEquals(index, statsMonthIndex(from, end))
        }
    }

    @Test fun fullCalendarYearHasTwelveSlotsAndLeapFebruary() {
        val (from, to) = StatsPeriod.YEAR.range(date("2024-12-31"))
        val slots = statsMonthSlots(from, to)
        assertEquals(12, slots.size)
        assertEquals(date("2024-02-01") to date("2024-02-29"), slots[1])
        assertEquals(11, statsMonthIndex(from, to))
    }
}
