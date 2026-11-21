package com.example.mygarmincaloriestracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MealAmountTest {
    private val meal = Meal(1, "Joghurt", 150.0, 91.5, 600)

    @Test fun recalculatesCaloriesUsingRecordedEnergyDensity() {
        assertEquals(122.0, rescaledMealCalories(meal, 200.0)!!, 0.0001)
        assertEquals(45.75, rescaledMealCalories(meal, 75.0)!!, 0.0001)
        assertEquals(0.0, rescaledMealCalories(meal.copy(kcal = 0.0), 50.0)!!, 0.0)
    }

    @Test fun rejectsInvalidAndOverflowingAmounts() {
        assertNull(rescaledMealCalories(meal, 0.0))
        assertNull(rescaledMealCalories(meal, Double.NaN))
        assertNull(rescaledMealCalories(meal.copy(grams = 0.0), 20.0))
        assertNull(rescaledMealCalories(meal.copy(kcal = Double.MAX_VALUE), 300.0))
    }
}
