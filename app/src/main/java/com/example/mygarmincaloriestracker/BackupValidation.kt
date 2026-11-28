package com.example.mygarmincaloriestracker

import java.time.LocalDate

/** Untrusted backup data must be decoded with byte, nesting, and duplicate-key limits before this step. */
internal data class BackupData(
    val version: Long,
    val tables: Map<String, List<Map<String, Any?>>>,
)

/**
 * Schema-level validation, deliberately independent of Android APIs so it can be unit tested.
 * Integer values must be decoded as Long and real values as Double; strings are never coerced.
 * A caller must not modify the source while validation is in progress.
 */
internal object BackupValidation {
    const val VERSION = 2L
    const val MAX_ROWS = 100_000
    // The transport byte limit already bounds text allocation; app inputs have no smaller cap.
    const val MAX_TEXT_LENGTH = BackupJson.MAX_BYTES
    private val minDay = LocalDate.MIN.toEpochDay()
    private val maxDay = LocalDate.MAX.toEpochDay()

    private enum class Kind { ID, SIGNED, DAY, POSITION, MINUTE, POSITIVE_INT, FLAG, TEXT, NONNEGATIVE, POSITIVE, PERCENT }
    private data class Column(val name: String, val kind: Kind, val nullable: Boolean = false)
    private data class Table(val columns: List<Column>, val key: List<String>)
    private fun col(name: String, kind: Kind, nullable: Boolean = false) = Column(name, kind, nullable)
    private val schema = linkedMapOf(
        "food" to Table(listOf(col("id", Kind.ID), col("name", Kind.TEXT), col("kcal100", Kind.NONNEGATIVE), col("unit_name", Kind.TEXT, true), col("unit_grams", Kind.POSITIVE, true)), listOf("id")),
        "sport" to Table(listOf(col("id", Kind.ID), col("name", Kind.TEXT), col("light", Kind.POSITIVE), col("medium", Kind.POSITIVE), col("hard", Kind.POSITIVE)), listOf("id")),
        "meal" to Table(listOf(col("id", Kind.ID), col("day", Kind.DAY), col("name", Kind.TEXT), col("grams", Kind.NONNEGATIVE), col("kcal", Kind.NONNEGATIVE), col("minute", Kind.MINUTE)), listOf("id")),
        "activity" to Table(listOf(col("id", Kind.ID), col("day", Kind.DAY), col("name", Kind.TEXT), col("minutes", Kind.POSITIVE_INT), col("kcal", Kind.NONNEGATIVE), col("minute", Kind.MINUTE)), listOf("id")),
        "body" to Table(listOf(col("day", Kind.DAY), col("weight", Kind.POSITIVE, true), col("waist", Kind.POSITIVE, true), col("hip", Kind.POSITIVE, true), col("body_fat", Kind.PERCENT, true)), listOf("day")),
        "imported_body" to Table(listOf(col("day", Kind.DAY), col("weight", Kind.POSITIVE, true), col("body_fat", Kind.PERCENT, true)), listOf("day")),
        "profile" to Table(listOf(col("day", Kind.DAY), col("age", Kind.POSITIVE_INT, true), col("height", Kind.POSITIVE, true), col("formula_offset", Kind.SIGNED, true), col("manual", Kind.POSITIVE, true), col("use_garmin", Kind.FLAG), col("garmin_total_is_resting", Kind.FLAG)), listOf("day")),
        "garmin_day" to Table(listOf(col("day", Kind.DAY), col("total", Kind.NONNEGATIVE, true), col("active", Kind.NONNEGATIVE, true)), listOf("day")),
        "garmin_activity" to Table(listOf(col("day", Kind.DAY), col("position", Kind.POSITION), col("entry_id", Kind.SIGNED), col("name", Kind.TEXT), col("minutes", Kind.POSITIVE_INT), col("kcal", Kind.NONNEGATIVE), col("minute", Kind.MINUTE), col("source", Kind.TEXT)), listOf("day", "position")),
    )

    /** Returns a defensive copy. Throws without exposing personal data in error messages. */
    fun validate(input: BackupData): BackupData {
        require(input.version == 1L || input.version == VERSION) { "Unsupported backup version" }
        val tables = if (input.version == 1L) {
            require(input.tables.keys == schema.keys - "imported_body") { "Unexpected backup tables" }
            require(input.tables.getValue("body").all { it.keys == setOf("day", "weight", "waist", "hip") }) { "Unexpected backup columns" }
            input.tables + ("body" to input.tables.getValue("body").map { it + ("body_fat" to null) }) + ("imported_body" to emptyList())
        } else input.tables
        require(tables.keys == schema.keys) { "Unexpected backup tables" }
        var count = 0L
        val copy = linkedMapOf<String, List<Map<String, Any?>>>()
        for ((name, table) in schema) {
            val rows = tables.getValue(name)
            count += rows.size.toLong()
            require(count <= MAX_ROWS) { "Too many backup rows" }
            val keys = HashSet<List<Any?>>()
            val expected = table.columns.map { it.name }.toSet()
            copy[name] = rows.map { row ->
                require(row.keys == expected) { "Unexpected backup columns" }
                val result = linkedMapOf<String, Any?>()
                for (column in table.columns) {
                    val value = row[column.name]
                    require(valid(value, column)) { "Invalid backup value" }
                    result[column.name] = value
                }
                require(keys.add(table.key.map { result[it] })) { "Duplicate backup key" }
                if (name == "food") {
                    val unit = result["unit_name"] as String?
                    require(unit == null || result["unit_grams"] != null) { "Invalid backup food unit" }
                }
                result.toMap()
            }
        }
        val days = copy.getValue("garmin_day").map { it.getValue("day") }.toHashSet()
        require(copy.getValue("garmin_activity").all { it.getValue("day") in days }) { "Orphan Garmin activity" }
        return BackupData(VERSION, copy.toMap())
    }

    private fun wellFormedUtf16(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val c = text[i++]
            if (Character.isHighSurrogate(c)) {
                if (i == text.length || !Character.isLowSurrogate(text[i++])) return false
            } else if (Character.isLowSurrogate(c)) return false
        }
        return true
    }

    private fun valid(value: Any?, column: Column): Boolean {
        if (value == null) return column.nullable
        return when (column.kind) {
            Kind.TEXT -> value is String && value.isNotBlank() && value.length <= MAX_TEXT_LENGTH && wellFormedUtf16(value)
            Kind.ID -> value is Long && value in 1 until Long.MAX_VALUE
            Kind.SIGNED -> value is Long && (column.name == "entry_id" || value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
            Kind.DAY -> value is Long && value in minDay..maxDay
            Kind.POSITION -> value is Long && value in 0..Int.MAX_VALUE.toLong()
            Kind.MINUTE -> value is Long && value in 0..1439
            Kind.POSITIVE_INT -> value is Long && value in 1..Int.MAX_VALUE.toLong()
            Kind.FLAG -> value is Long && value in 0..1
            Kind.NONNEGATIVE -> value is Double && value.isFinite() && value >= 0.0
            Kind.POSITIVE -> value is Double && value.isFinite() && value > 0.0
            Kind.PERCENT -> value is Double && value.isFinite() && value in 0.0..100.0
        }
    }
}
