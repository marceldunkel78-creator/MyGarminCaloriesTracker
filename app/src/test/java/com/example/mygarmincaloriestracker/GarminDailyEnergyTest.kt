package com.example.mygarmincaloriestracker

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GarminDailyEnergyTest {
    private val start = Instant.parse("2026-04-12T22:00:00Z")
    private val end = start.plusSeconds(86_400)

    @Test fun dailySnapshotUsesRawValueAndIgnoresWorkoutRecords() {
        assertEquals(1_000.0, garminDailyValue(start, end, listOf(
            Triple(start, end.minusSeconds(1), 1_000.0),
            Triple(start.plusSeconds(3600), start.plusSeconds(7200), 300.0),
        ))!!, 0.0)
    }

    @Test fun sumsBeforeDuringAndAfterWorkoutWithoutProrating() {
        val workoutStart = start.plusSeconds(36000)
        val workoutEnd = workoutStart.plusSeconds(3600)
        val before = Triple(start, workoutStart.minusSeconds(1), 650.0)
        assertEquals(1_400.0, garminSegmentedDailyValue(start, end, listOf(
            Triple(workoutEnd, end.minusSeconds(1), 432.0),
            before, before, Triple(workoutStart, workoutEnd, 318.0),
        ))!!, 0.0)
    }

    @Test fun rejectsOverlapAndMissingCoverage() {
        val split = start.plusSeconds(36000)
        val before = Triple(start, split, 650.0)
        assertNull(garminSegmentedDailyValue(start, end, listOf(before, Triple(split.minusSeconds(2), end, 750.0))))
        assertNull(garminSegmentedDailyValue(start, end, listOf(before, Triple(split.plusSeconds(2), end, 750.0))))
        assertNull(garminSegmentedDailyValue(start, end, listOf(before, Triple(split, split.plusSeconds(3600), 318.0))))
    }

    @Test fun workoutOrConflictingSnapshotsDoNotBecomeDailyTotal() {
        assertNull(garminDailyValue(start, end, listOf(Triple(start, start.plusSeconds(3600), 300.0))))
        assertNull(garminDailyValue(start, end, listOf(Triple(start, end, 1000.0), Triple(start, end, 1200.0))))
    }
}
