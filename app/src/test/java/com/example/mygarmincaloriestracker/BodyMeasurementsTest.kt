package com.example.mygarmincaloriestracker

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BodyMeasurementsTest {
    private val day = LocalDate.of(2024, 1, 2)

    @Test fun percentagePointDifferenceUsesPeriodEndpoints() {
        assertEquals(-2.5, requireNotNull(StatsSummary.difference(listOf(day to 25.0, day.plusDays(2) to 22.5), day, day.plusDays(3), day.plusDays(3))), 0.0)
    }

    @Test fun legacyBackupAddsNullFatAndNewBackupValidatesRange() {
        val json = """{"version":1,"tables":{"food":[],"sport":[],"meal":[],"activity":[],"body":[{"day":0,"weight":70.0,"waist":null,"hip":null}],"profile":[],"garmin_day":[],"garmin_activity":[]}}"""
        val legacy = BackupJson.read(json.byteInputStream())
        assertEquals(null, legacy.tables.getValue("body").single()["body_fat"])
        val updated = legacy.copy(tables = legacy.tables + ("body" to listOf(legacy.tables.getValue("body").single() + ("body_fat" to 22.5))))
        assertEquals(updated, BackupJson.read(BackupJson.encode(updated).inputStream()))
        val invalid = updated.copy(tables = updated.tables + ("body" to listOf(updated.tables.getValue("body").single() + ("body_fat" to 101.0))))
        assertThrows(IllegalArgumentException::class.java) { BackupValidation.validate(invalid) }
    }
}
