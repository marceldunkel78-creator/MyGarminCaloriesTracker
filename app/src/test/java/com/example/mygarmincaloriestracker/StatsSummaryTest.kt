package com.example.mygarmincaloriestracker

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatsSummaryTest {
    private fun d(value: String) = LocalDate.parse(value)
    private fun sample(date: String, value: Double) = d(date) to value
    private val from = d("2024-02-28")
    private val to = d("2024-03-03")
    private val today = d("2024-03-01")

    @Test fun sumIncludesAvailableDaysAndLeapDayButNotFutureOrOutside() {
        val values = listOf(sample("2024-02-27", 100.0), sample("2024-02-28", 0.2),
            sample("2024-02-29", -0.1), sample("2024-03-01", 0.05),
            sample("2024-03-02", 100.0), sample("2024-03-04", 100.0), sample("2024-02-29", Double.NaN))
        assertEquals(0.15, StatsSummary.sum(values, from, to, today)!!, 1e-10)
        assertNull(StatsSummary.sum(emptyList(), from, to, today))
        assertNull(StatsSummary.sum(listOf(sample("2024-02-29", Double.POSITIVE_INFINITY)), from, to, today))
        assertEquals(0.0, StatsSummary.sum(listOf(sample("2024-02-29", 0.0)), from, to, today)!!, 0.0)
    }

    @Test fun differenceUsesActualFirstAndLastRegardlessOfOrderingAndMonthAggregation() {
        val values = listOf(sample("2024-03-01", 72.0), sample("2024-02-29", 74.0),
            sample("2024-02-28", 75.0), sample("2024-03-02", 90.0), sample("2024-02-27", 90.0))
        assertEquals(-3.0, StatsSummary.difference(values, from, to, today)!!, 0.0)
        assertEquals(-1.0, StatsSummary.difference(values, d("2024-02-28"), d("2024-02-29"), today)!!, 0.0)
    }

    @Test fun differenceRequiresTwoDistinctDatesNotDifferentValues() {
        assertNull(StatsSummary.difference(emptyList(), from, to, today))
        assertNull(StatsSummary.difference(listOf(sample("2024-02-29", 70.0)), from, to, today))
        assertNull(StatsSummary.difference(listOf(sample("2024-02-29", 70.0), sample("2024-02-29", 71.0)), from, to, today))
        assertNull(StatsSummary.difference(listOf(sample("2024-02-28", Double.NaN), sample("2024-02-29", 70.0)), from, to, today))
        assertEquals(0.0, StatsSummary.difference(listOf(sample("2024-02-28", 70.0), sample("2024-02-29", 70.0)), from, to, today)!!, 0.0)
        assertEquals(2.0, StatsSummary.difference(listOf(sample("2024-02-28", 70.0), sample("2024-03-01", 72.0)), from, to, today)!!, 0.0)
    }

    @Test fun nonFiniteResultsAreUnavailable() {
        val values = listOf(sample("2024-02-28", Double.MAX_VALUE), sample("2024-02-29", Double.MAX_VALUE))
        assertNull(StatsSummary.sum(values, from, to, today))
        assertNull(StatsSummary.difference(listOf(sample("2024-02-28", -Double.MAX_VALUE), sample("2024-02-29", Double.MAX_VALUE)), from, to, today))
    }
}
