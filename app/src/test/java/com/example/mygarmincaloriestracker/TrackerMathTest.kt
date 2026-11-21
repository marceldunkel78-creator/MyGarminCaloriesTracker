package com.example.mygarmincaloriestracker

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackerMathTest {
    private val today = LocalDate.of(2026, 4, 12)
    private val noon = today.atTime(12, 0)
    private val morningMeal = Meal(1, "Breakfast", 200.0, 500.0, 480)
    private val morningWorkout = ActivityEntry(1, "Run", 30, 300.0, 600)

    @Test
    fun todayUsesElapsedBaselineAndExcludesFutureEntries() {
        val data = DayData(today, listOf(morningMeal, morningMeal.copy(id = 2, minute = 721)),
            listOf(morningWorkout, morningWorkout.copy(id = 2, minute = 721)), null, null,
            Profile(manual = 2_000.0), null)
        val energy = TrackerMath.energy(data, noon)
        assertEquals(500.0, energy.intake, 0.001)
        assertKcal(1_000.0, energy.resting)
        assertKcal(300.0, energy.active)
        assertKcal(1_300.0, energy.burned)
        assertKcal(2_300.0, energy.forecast)
        assertKcal(-1_800.0, energy.projectedBalance)
        assertKcal(-800.0, energy.balance)
        assertKcal(-800.0 / 7_700.0, energy.changeKg)
        assertEquals("Manuell", energy.source)
    }

    @Test
    fun historyUsesFullMifflinBaselineAndLatestDatedWeight() {
        val yesterday = today.minusDays(1)
        val data = DayData(yesterday, listOf(morningMeal.copy(minute = 1439)),
            listOf(morningWorkout.copy(minute = 1439)), Body(yesterday, null, 82.0, null),
            Body(yesterday.minusDays(1), 70.0, null, null), Profile(30, 170.0, -161), null)
        val energy = TrackerMath.energy(data, noon)
        assertEquals(500.0, energy.intake, 0.001)
        assertKcal(1_451.5, energy.resting)
        assertKcal(2_041.8, energy.burned)
        assertKcal(2_041.8, energy.forecast)
        assertEquals("Mifflin-St Jeor", energy.source)
        assertNull(TrackerMath.energy(data.copy(latestBody = data.latestBody?.copy(day = today)), noon).burned)
    }

    @Test
    fun garminUsesImportedTotalsWithoutCountingWorkoutsTwice() {
        val garmin = GarminDay(1_700.0, 450.0, listOf(morningWorkout.copy(source = "Garmin")))
        val data = DayData(today, listOf(morningMeal), listOf(morningWorkout), null, null,
            Profile(manual = 2_000.0, useGarmin = true), garmin)
        val energy = TrackerMath.energy(data, noon)
        assertEquals("Garmin", energy.source)
        assertKcal(1_700.0, energy.burned)
        assertKcal(1_250.0, energy.resting)
        assertKcal(450.0, energy.active)
        assertKcal(2_950.0, energy.forecast)
        assertKcal(-2_450.0, energy.projectedBalance)
        assertKcal(1_300.0, TrackerMath.energy(data.copy(garmin = garmin.copy(total = null)), noon).burned)
        assertKcal(1_300.0, TrackerMath.energy(data.copy(profile = data.profile.copy(useGarmin = false)), noon).burned)
        assertKcal(1_700.0, TrackerMath.energy(data.copy(day = today.minusDays(1)), noon).forecast)
        assertNull(TrackerMath.energy(data.copy(garmin = garmin.copy(active = null)), noon).resting)
    }

    @Test
    fun garminRestOnlyExportAddsActiveExactlyOnce() {
        val data = DayData(today, listOf(morningMeal), listOf(morningWorkout), null, null,
            Profile(useGarmin = true, garminTotalIsResting = true),
            GarminDay(1_000.0, 300.0, listOf(morningWorkout.copy(source = "Garmin"))))
        val energy = TrackerMath.energy(data, noon)
        assertKcal(1_000.0, energy.resting)
        assertKcal(300.0, energy.active)
        assertKcal(1_300.0, energy.burned)
        assertKcal(2_300.0, energy.forecast)
        assertKcal(-1_800.0, energy.projectedBalance)
        assertKcal(-800.0, energy.balance)
        val incomplete = TrackerMath.energy(data.copy(garmin = data.garmin?.copy(active = null)), noon)
        assertKcal(1_000.0, incomplete.resting)
        assertNull(incomplete.burned)
        assertNull(incomplete.balance)
    }

    @Test
    fun noElapsedMealSuppressesBalanceButKeepsBurn() {
        val data = DayData(today, listOf(morningMeal.copy(minute = 800)), emptyList(), null, null,
            Profile(manual = 2_000.0), null)
        val energy = TrackerMath.energy(data, noon)
        assertKcal(1_000.0, energy.burned)
        assertNull(energy.balance)
        assertNull(energy.projectedBalance)
        assertNull(energy.changeKg)
        assertKcal(-1_000.0, TrackerMath.energy(data.copy(meals = listOf(morningMeal.copy(kcal = 0.0))), noon).balance)
    }

    @Test
    fun futureDaysAndUnknownBaselineDoNotReportTotalBurn() {
        val data = DayData(today, listOf(morningMeal), listOf(morningWorkout), null, null, Profile(), null)
        val unknown = TrackerMath.energy(data, noon)
        assertNull(unknown.burned)
        assertNull(unknown.balance)
        assertKcal(300.0, unknown.active)
        val future = TrackerMath.energy(data.copy(day = today.plusDays(1), profile = Profile(manual = 2_000.0)), noon)
        assertEquals(0.0, future.intake, 0.001)
        assertNull(future.burned)
        assertNull(future.forecast)
        assertNull(future.active)
    }

    @Test
    fun grossMetConvertsToNetCalories() {
        assertEquals(183.75, TrackerMath.activityKcal(6.0, 70.0, 30), 0.001)
        assertEquals(0.0, TrackerMath.activityKcal(1.0, 70.0, 30), 0.001)
        assertEquals(0.0, TrackerMath.activityKcal(0.8, 70.0, 30), 0.001)
    }

    @Test
    fun garminForecastUsesLocalElapsedTimeAndNeverExtrapolatesActive() {
        val data = DayData(today, listOf(morningMeal), listOf(morningWorkout), null, null,
            Profile(useGarmin = true), GarminDay(600.0, 100.0, emptyList()))
        val morning = TrackerMath.energy(data, today.atTime(6, 0))
        assertKcal(2_100.0, morning.forecast)
        // Breakfast occurs after 6:00 and is not yet included.
        assertNull(morning.projectedBalance)
        val noonEnergy = TrackerMath.energy(data, noon)
        assertKcal(1_100.0, noonEnergy.forecast)
        assertKcal(-600.0, noonEnergy.projectedBalance)
        assertKcal(400.0, TrackerMath.energy(data.copy(meals = listOf(morningMeal.copy(kcal = 1_500.0))), noon).projectedBalance)
    }

    @Test
    fun unknownAndInvalidGarminComponentsDoNotProduceForecast() {
        val data = DayData(today, listOf(morningMeal), emptyList(), null, null,
            Profile(useGarmin = true), GarminDay(1_000.0, null, emptyList()))
        assertNull(TrackerMath.energy(data, noon).forecast)
        assertNull(TrackerMath.energy(data, noon).projectedBalance)
        assertNull(TrackerMath.energy(data.copy(garmin = data.garmin?.copy(active = 1_001.0)), noon).forecast)
        assertNull(TrackerMath.energy(data.copy(garmin = data.garmin?.copy(active = Double.NaN)), noon).forecast)
        assertNull(TrackerMath.energy(data.copy(profile = data.profile.copy(garminTotalIsResting = true)), noon).forecast)
        assertKcal(1_000.0, TrackerMath.energy(data.copy(day = today.minusDays(1)), noon).forecast)
    }

    @Test
    fun midnightFutureAndZeroInputsAreSafe() {
        val data = DayData(today, listOf(morningMeal.copy(minute = 0, kcal = 0.0)), emptyList(), null, null,
            Profile(useGarmin = true), GarminDay(0.0, 0.0, emptyList()))
        assertNull(TrackerMath.energy(data, today.atStartOfDay()).forecast)
        assertNull(TrackerMath.energy(data.copy(profile = Profile(manual = 2_000.0)), today.atStartOfDay()).projectedBalance)
        assertKcal(0.0, TrackerMath.energy(data, noon).forecast)
        assertKcal(0.0, TrackerMath.energy(data, noon).projectedBalance)
        assertNull(TrackerMath.energy(data.copy(day = today.plusDays(1)), noon).projectedBalance)
        assertNull(TrackerMath.energy(data.copy(garmin = data.garmin?.copy(total = Double.MAX_VALUE)), today.atTime(0, 0, 1)).forecast)
    }

    @Test
    fun deficitStreakEndsYesterdayAndRequiresConsecutiveKnownDeficits() {
        fun day(date: LocalDate, intake: Double = 500.0) = DayData(date,
            listOf(morningMeal.copy(kcal = intake)), emptyList(), null, null, Profile(manual = 2_000.0), null)
        val yesterday = day(today.minusDays(1))
        val twoDaysAgo = day(today.minusDays(2))
        val threeDaysAgo = day(today.minusDays(3), 2_000.0)
        assertEquals(2, TrackerMath.deficitStreak(listOf(day(today), threeDaysAgo, twoDaysAgo, yesterday), noon))
        assertEquals(1, TrackerMath.deficitStreak(listOf(yesterday, threeDaysAgo), noon))
        assertEquals(0, TrackerMath.deficitStreak(listOf(twoDaysAgo, day(today)), noon))
        assertEquals(1, TrackerMath.deficitStreak(listOf(yesterday, twoDaysAgo.copy(meals = emptyList())), noon))
        assertEquals(1, TrackerMath.deficitStreak(listOf(yesterday, twoDaysAgo.copy(profile = Profile())), noon))
        assertEquals(0, TrackerMath.deficitStreak(listOf(yesterday.copy(meals = emptyList()), twoDaysAgo), noon))
        assertEquals(0, TrackerMath.deficitStreak(listOf(yesterday.copy(meals = listOf(morningMeal.copy(kcal = Double.NaN)))), noon))
        assertEquals(0, TrackerMath.deficitStreak(listOf(yesterday, yesterday), noon))
        assertEquals(1, TrackerMath.deficitStreak(listOf(yesterday, day(today.minusDays(2), 2_500.0)), noon))
    }

    private fun assertKcal(expected: Double, actual: Double?) {
        assertEquals(expected, requireNotNull(actual), 0.001)
    }
}
