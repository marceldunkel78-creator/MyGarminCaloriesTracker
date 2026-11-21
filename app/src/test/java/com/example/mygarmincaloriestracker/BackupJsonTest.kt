package com.example.mygarmincaloriestracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupJsonTest {
    private fun valid() = """{"version":1,"tables":{"food":[],"sport":[],"meal":[],"activity":[],"body":[],"profile":[],"garmin_day":[],"garmin_activity":[]}}"""

    @Test fun parsesEmptyBackup() {
        val result = BackupJson.read(valid().byteInputStream())
        assertEquals(1L, result.version)
        assertEquals(8, result.tables.size)
        assertEquals(result, BackupJson.read(BackupJson.encode(result).inputStream()))
    }

    @Test fun nonemptyRoundTripPreservesLongTextAndFiniteNumbers() {
        val empty = BackupJson.read(valid().byteInputStream())
        val meal = mapOf<String, Any?>("id" to Long.MAX_VALUE - 1, "day" to 0L,
            "name" to ("Long\n食😀".repeat(3000)), "grams" to Double.MIN_VALUE,
            "kcal" to Double.MAX_VALUE, "minute" to 1439L)
        val data = empty.copy(tables = empty.tables + ("meal" to listOf(meal)))
        assertEquals(data, BackupJson.read(BackupJson.encode(data).inputStream()))
    }

    @Test fun rejectsDuplicateKeysAndTrailingData() {
        assertThrows(Exception::class.java) {
            BackupJson.read(valid().replace("\"version\":1", "\"version\":1,\"version\":1").byteInputStream())
        }
        assertThrows(Exception::class.java) { BackupJson.read((valid() + "{}").byteInputStream()) }
        assertThrows(Exception::class.java) { BackupJson.read(valid().dropLast(1).byteInputStream()) }
        assertThrows(Exception::class.java) { BackupJson.read(valid().replace("\"version\":1", "\"version\":01").byteInputStream()) }
        assertThrows(Exception::class.java) { BackupJson.read(valid().replace("\"food\":[]", "\"food\":[null,]").byteInputStream()) }
    }

    @Test fun rejectsMalformedUtf8AndOversizedInput() {
        assertThrows(Exception::class.java) { BackupJson.read(byteArrayOf(0xc3.toByte(), 0x28).inputStream()) }
        assertThrows(Exception::class.java) { BackupJson.read(ByteArray(BackupJson.MAX_BYTES + 1).inputStream()) }
    }
}
