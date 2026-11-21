package com.example.mygarmincaloriestracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupValidationTest {
    private fun valid() = BackupData(1L, linkedMapOf(
        "food" to listOf(mapOf("id" to 1L, "name" to "Apple", "kcal100" to 52.0, "unit_name" to null, "unit_grams" to null)),
        "sport" to emptyList(),
        "meal" to listOf(mapOf("id" to 3L, "day" to 0L, "name" to "Meal", "grams" to 0.0, "kcal" to 20.0, "minute" to 42L)),
        "activity" to emptyList(),
        "body" to emptyList(),
        "profile" to emptyList(),
        "garmin_day" to listOf(mapOf("day" to 0L, "total" to null, "active" to 20.0)),
        "garmin_activity" to listOf(mapOf("day" to 0L, "position" to 0L, "entry_id" to Long.MIN_VALUE, "name" to "Run", "minutes" to 1L, "kcal" to 0.0, "minute" to 0L, "source" to "Garmin")),
    ))

    private fun withRow(data: BackupData, table: String, row: Map<String, Any?>) =
        data.copy(tables = data.tables + (table to listOf(row)))

    private fun rejected(data: BackupData) {
        assertThrows(IllegalArgumentException::class.java) { BackupValidation.validate(data) }
    }

    @Test fun validBackupPreservesRowsAndIds() {
        assertEquals(valid(), BackupValidation.validate(valid()))
    }

    @Test fun rejectsUnknownVersionAndSchema() {
        rejected(valid().copy(version = 2))
        rejected(valid().copy(tables = valid().tables - "profile"))
        rejected(valid().copy(tables = valid().tables + ("unexpected" to emptyList())))
    }

    @Test fun rejectsBadTypesNonFiniteAndBounds() {
        val data = valid()
        val meal = data.tables.getValue("meal").single()
        rejected(withRow(data, "meal", meal + ("minute" to 1440L)))
        rejected(withRow(data, "meal", meal + ("kcal" to Double.NaN)))
        rejected(withRow(data, "meal", meal + ("kcal" to Double.POSITIVE_INFINITY)))
        rejected(withRow(data, "meal", meal + ("kcal" to -1.0)))
        rejected(withRow(data, "meal", meal + ("id" to "3")))
        rejected(withRow(data, "meal", meal + ("day" to Long.MAX_VALUE)))
        rejected(withRow(data, "meal", meal + ("name" to " ")))
        rejected(withRow(data, "meal", meal + ("extra" to 1L)))
    }

    @Test fun acceptsLargeFiniteValuesLongTextAndSafeIds() {
        val data = valid()
        val meal = data.tables.getValue("meal").single() + mapOf(
            "id" to Long.MAX_VALUE - 1, "name" to "x".repeat(20_000), "kcal" to Double.MAX_VALUE)
        assertEquals(meal, BackupValidation.validate(withRow(data, "meal", meal)).tables.getValue("meal").single())
        rejected(withRow(data, "meal", meal + ("id" to Long.MAX_VALUE)))
        rejected(withRow(data, "meal", meal + ("id" to 0L)))
    }

    @Test fun rejectsDuplicateKeysAndOrphanActivities() {
        val data = valid()
        val meal = data.tables.getValue("meal").single()
        rejected(data.copy(tables = data.tables + ("meal" to listOf(meal, meal))))
        rejected(data.copy(tables = data.tables + ("garmin_day" to emptyList())))
    }

    @Test fun rejectsInvalidFlagAndFoodUnit() {
        val data = valid()
        val food = data.tables.getValue("food").single()
        rejected(withRow(data, "food", food + ("unit_name" to "piece")))
        rejected(withRow(data, "profile", mapOf("day" to 0L, "age" to null, "height" to null,
            "formula_offset" to null, "manual" to null, "use_garmin" to 2L, "garmin_total_is_resting" to 0L)))
    }
}
