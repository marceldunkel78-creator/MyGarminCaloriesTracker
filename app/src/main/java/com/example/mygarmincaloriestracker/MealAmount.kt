package com.example.mygarmincaloriestracker

/** Uses the original logged energy density, independently of later catalog edits. */
internal fun rescaledMealCalories(meal: Meal, grams: Double): Double? {
    if (!grams.isFinite() || grams <= 0 || !meal.grams.isFinite() || meal.grams <= 0 ||
        !meal.kcal.isFinite() || meal.kcal < 0) return null
    return (meal.kcal * (grams / meal.grams)).takeIf { it.isFinite() && it >= 0 }
}
