package com.example.mygarmincaloriestracker

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectMealTest {
    @Test fun directCaloriesAreIncludedInTheSelectedDayBalanceWithoutGrams() {
        val date = LocalDate.of(2026, 1, 2)
        val direct = Meal(1, "Kalorienaufnahme", 0.0, 1500.0, 720)
        val food = Meal(2, "Apfel", 100.0, 52.0, 721)
        assertTrue(direct.isDirect)
        assertFalse(food.isDirect)
        val energy = TrackerMath.energy(DayData(date, listOf(direct, food), emptyList(), null, null,
            Profile(manual = 2000.0), null), date.plusDays(1).atStartOfDay())
        assertEquals(1552.0, energy.intake, 0.001)
        assertEquals(-448.0, energy.balance!!, 0.001)
    }
}
