package com.example.mygarmincaloriestracker

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.annotation.RequiresApi
import java.io.Closeable
import java.time.LocalDate

data class Food(val id: Long, val name: String, val kcal100: Double, val unitName: String?, val unitGrams: Double?)

/** Intensity values are gross MET. */
data class Sport(val id: Long, val name: String, val light: Double, val medium: Double, val hard: Double)

data class Meal(val id: Long, val name: String, val grams: Double, val kcal: Double, val minute: Int)

data class ActivityEntry(
    val id: Long,
    val name: String,
    val minutes: Int,
    val kcal: Double,
    val minute: Int,
    val source: String = "Manuell",
)

/** Weight in kg; waist and hip in cm. */
data class Body(val day: LocalDate, val weight: Double?, val waist: Double?, val hip: Double?)

/** Height in cm. Manual is daily baseline kcal, including any untracked everyday burn but no tracked workouts. */
data class Profile(
    val age: Int? = null,
    val height: Double? = null,
    val formulaOffset: Int? = null,
    val manual: Double? = null,
    val useGarmin: Boolean = false,
    val garminTotalIsResting: Boolean = false,
)

/** Imported activities are display-only; they are not added to the imported total. */
data class GarminDay(val total: Double?, val active: Double?, val activities: List<ActivityEntry>)

/** [activities] contains manual entries only. [latestBody] is the latest weigh-in on or before [day]. */
data class DayData(
    val day: LocalDate,
    val meals: List<Meal>,
    val activities: List<ActivityEntry>,
    val body: Body?,
    val latestBody: Body?,
    val profile: Profile,
    val garmin: GarminDay?,
)

/** Private local SQLite store. Call sequentially on an IO thread; close after the last use. */
@RequiresApi(26)
class TrackerStore(context: Context) : Closeable {
    private val helper = TrackerOpenHelper(context.applicationContext)

    override fun close() = helper.close()

    fun foods(): List<Food> = helper.readableDatabase.rows(
        "SELECT id, name, kcal100, unit_name, unit_grams FROM food ORDER BY name COLLATE NOCASE, id",
    ) { Food(getLong(0), getString(1), getDouble(2), getString(3), doubleOrNull(4)) }

    fun sports(): List<Sport> = helper.readableDatabase.rows(
        "SELECT id, name, light, medium, hard FROM sport ORDER BY name COLLATE NOCASE, id",
    ) { Sport(getLong(0), getString(1), getDouble(2), getDouble(3), getDouble(4)) }

    fun addFood(name: String, kcal100: Double, unitName: String?, unitGrams: Double?) {
        require(name.isNotBlank() && kcal100.isNonNegative() && (unitGrams == null || unitGrams.isPositive()))
        helper.writableDatabase.execSQL(INSERT_FOOD, arrayOf<Any?>(name.trim(), kcal100, unitName, unitGrams))
    }

    fun addSport(name: String, light: Double, medium: Double, hard: Double) {
        require(name.isNotBlank() && listOf(light, medium, hard).all { it.isPositive() })
        helper.writableDatabase.execSQL(INSERT_SPORT, arrayOf<Any?>(name.trim(), light, medium, hard))
    }

    fun addMeal(day: LocalDate, name: String, grams: Double, kcal: Double, minute: Int) {
        require(name.isNotBlank() && grams.isPositive() && kcal.isNonNegative() && minute in 0..1439)
        helper.writableDatabase.execSQL(
            "INSERT INTO meal(day, name, grams, kcal, minute) VALUES (?, ?, ?, ?, ?)",
            arrayOf<Any?>(day.toEpochDay(), name.trim(), grams, kcal, minute),
        )
    }

    fun addActivity(day: LocalDate, name: String, minutes: Int, kcal: Double, minute: Int) {
        require(name.isNotBlank() && minutes > 0 && kcal.isNonNegative() && minute in 0..1439)
        helper.writableDatabase.execSQL(
            "INSERT INTO activity(day, name, minutes, kcal, minute) VALUES (?, ?, ?, ?, ?)",
            arrayOf<Any?>(day.toEpochDay(), name.trim(), minutes, kcal, minute),
        )
    }

    fun saveBody(body: Body) {
        require(listOfNotNull(body.weight, body.waist, body.hip).all { it.isPositive() })
        helper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO body(day, weight, waist, hip) VALUES (?, ?, ?, ?)",
            arrayOf<Any?>(body.day.toEpochDay(), body.weight, body.waist, body.hip),
        )
    }

    fun profile(): Profile = helper.readableDatabase.profileAsOf(LocalDate.now().toEpochDay())

    /** Baseline settings take effect on the local save date; same-day saves replace that revision. */
    fun saveProfile(profile: Profile) {
        require(profile.age == null || profile.age > 0)
        require(listOfNotNull(profile.height, profile.manual).all { it.isPositive() })
        helper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO profile(day, $PROFILE_COLUMNS) VALUES (?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(LocalDate.now().toEpochDay(), profile.age, profile.height, profile.formulaOffset,
                profile.manual, if (profile.useGarmin) 1 else 0, if (profile.garminTotalIsResting) 1 else 0),
        )
    }

    fun day(day: LocalDate): DayData = days(day, day).single()

    /** One row per calendar date, including unlogged dates, in ascending order; both endpoints inclusive. */
    fun days(from: LocalDate, to: LocalDate): List<DayData> {
        if (from > to) return emptyList()
        val db = helper.readableDatabase
        val args = arrayOf(from.toEpochDay().toString(), to.toEpochDay().toString())
        val meals = db.rows(
            "SELECT day, id, name, grams, kcal, minute FROM meal WHERE $DAY_RANGE ORDER BY day, minute, id", args,
        ) { getLong(0) to Meal(getLong(1), getString(2), getDouble(3), getDouble(4), getInt(5)) }
            .groupBy({ it.first }, { it.second })
        val activities = db.rows(
            "SELECT day, id, name, minutes, kcal, minute FROM activity WHERE $DAY_RANGE ORDER BY day, minute, id", args,
        ) { getLong(0) to ActivityEntry(getLong(1), getString(2), getInt(3), getDouble(4), getInt(5)) }
            .groupBy({ it.first }, { it.second })
        val bodies = db.rows("SELECT $BODY_COLUMNS FROM body WHERE $DAY_RANGE", args) { bodyRow() }
            .associateBy { it.day.toEpochDay() }
        val importedActivities = db.rows(
            "SELECT day, entry_id, name, minutes, kcal, minute, source FROM garmin_activity " +
                "WHERE $DAY_RANGE ORDER BY day, position", args,
        ) { getLong(0) to ActivityEntry(getLong(1), getString(2), getInt(3), getDouble(4), getInt(5), getString(6)) }
            .groupBy({ it.first }, { it.second })
        val garmin = db.rows("SELECT day, total, active FROM garmin_day WHERE $DAY_RANGE", args) {
            val key = getLong(0)
            key to GarminDay(doubleOrNull(1), doubleOrNull(2), importedActivities[key].orEmpty())
        }.toMap()
        val profileUpdates = db.rows("SELECT day, $PROFILE_COLUMNS FROM profile WHERE $DAY_RANGE", args) {
            getLong(0) to profileRow(1)
        }.toMap()

        var latestBody = db.rows(
            "SELECT $BODY_COLUMNS FROM body WHERE day < ? AND weight IS NOT NULL ORDER BY day DESC LIMIT 1",
            arrayOf(args[0]),
        ) { bodyRow() }.firstOrNull()
        var baselineProfile = db.profileAsOf(from.toEpochDay())
        // Garmin preference is global: switching it off also switches historical views to local estimates.
        val garminSettings = profile()
        return (from.toEpochDay()..to.toEpochDay()).map { key ->
            val body = bodies[key]
            if (body?.weight != null) latestBody = body
            profileUpdates[key]?.let { baselineProfile = it }
            DayData(LocalDate.ofEpochDay(key), meals[key].orEmpty(), activities[key].orEmpty(),
                body, latestBody, baselineProfile.copy(useGarmin = garminSettings.useGarmin,
                    garminTotalIsResting = garminSettings.garminTotalIsResting), garmin[key])
        }
    }

    /** Atomically replaces a day's import, retaining the order, IDs, and source labels supplied by the importer. */
    fun saveGarmin(day: LocalDate, data: GarminDay) {
        val key = day.toEpochDay()
        helper.writableDatabase.transaction {
            delete("garmin_day", "day = ?", arrayOf(key.toString())) // Cascades to imported activities.
            execSQL("INSERT INTO garmin_day(day, total, active) VALUES (?, ?, ?)", arrayOf<Any?>(key, data.total, data.active))
            data.activities.forEachIndexed { position, entry ->
                execSQL(
                    "INSERT INTO garmin_activity(day, position, entry_id, name, minutes, kcal, minute, source) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf<Any?>(key, position, entry.id, entry.name, entry.minutes, entry.kcal, entry.minute, entry.source),
                )
            }
        }
    }

    fun clearGarmin() {
        helper.writableDatabase.delete("garmin_day", null, null)
    }

    fun deleteMeal(id: Long) {
        helper.writableDatabase.delete("meal", "id = ?", arrayOf(id.toString()))
    }

    fun deleteActivity(id: Long) {
        helper.writableDatabase.delete("activity", "id = ?", arrayOf(id.toString()))
    }
}

private const val DAY_RANGE = "day BETWEEN ? AND ?"
private const val BODY_COLUMNS = "day, weight, waist, hip"
private const val PROFILE_COLUMNS = "age, height, formula_offset, manual, use_garmin, garmin_total_is_resting"
private const val INSERT_FOOD = "INSERT INTO food(name, kcal100, unit_name, unit_grams) VALUES (?, ?, ?, ?)"
private const val INSERT_SPORT = "INSERT INTO sport(name, light, medium, hard) VALUES (?, ?, ?, ?)"

private class TrackerOpenHelper(context: Context) : SQLiteOpenHelper(context, "tracker.db", null, 2) {
    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE food (id INTEGER PRIMARY KEY, name TEXT NOT NULL, kcal100 REAL NOT NULL, unit_name TEXT, unit_grams REAL)")
        db.execSQL("CREATE TABLE sport (id INTEGER PRIMARY KEY, name TEXT NOT NULL, light REAL NOT NULL, medium REAL NOT NULL, hard REAL NOT NULL)")
        db.execSQL("CREATE TABLE meal (id INTEGER PRIMARY KEY AUTOINCREMENT, day INTEGER NOT NULL, name TEXT NOT NULL, grams REAL NOT NULL, kcal REAL NOT NULL, minute INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE activity (id INTEGER PRIMARY KEY AUTOINCREMENT, day INTEGER NOT NULL, name TEXT NOT NULL, minutes INTEGER NOT NULL, kcal REAL NOT NULL, minute INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX meal_day_minute ON meal(day, minute)")
        db.execSQL("CREATE INDEX activity_day_minute ON activity(day, minute)")
        db.execSQL("CREATE TABLE body (day INTEGER PRIMARY KEY, weight REAL, waist REAL, hip REAL)")
        db.execSQL("CREATE TABLE profile (day INTEGER PRIMARY KEY, age INTEGER, height REAL, formula_offset INTEGER, manual REAL, use_garmin INTEGER NOT NULL, garmin_total_is_resting INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE garmin_day (day INTEGER PRIMARY KEY, total REAL, active REAL)")
        db.execSQL("""
            CREATE TABLE garmin_activity (
                day INTEGER NOT NULL REFERENCES garmin_day(day) ON DELETE CASCADE,
                position INTEGER NOT NULL,
                entry_id INTEGER NOT NULL,
                name TEXT NOT NULL,
                minutes INTEGER NOT NULL,
                kcal REAL NOT NULL,
                minute INTEGER NOT NULL,
                source TEXT NOT NULL,
                PRIMARY KEY (day, position)
            )
        """.trimIndent())

        // Generic defaults; portions and intensity estimates can vary by product or pace.
        listOf<Array<Any?>>(
            arrayOf("Apfel", 52.0, "Stück", 150.0),
            arrayOf("Banane", 89.0, "Stück", 120.0),
            arrayOf("Haferflocken", 370.0, null, null),
            arrayOf("Naturjoghurt", 61.0, "Becher", 150.0),
            arrayOf("Vollkornbrot", 230.0, "Scheibe", 50.0),
        ).forEach { db.execSQL(INSERT_FOOD, it) }
        listOf<Array<Any?>>(
            arrayOf("Gehen", 2.8, 3.8, 4.8),
            arrayOf("Laufen", 6.5, 8.5, 11.0),
            arrayOf("Radfahren", 4.0, 6.8, 10.0),
            arrayOf("Schwimmen", 4.8, 6.0, 9.8),
            arrayOf("Krafttraining", 3.0, 3.5, 6.0),
        ).forEach { db.execSQL(INSERT_SPORT, it) }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion == 1 && newVersion == 2) {
            db.execSQL("ALTER TABLE profile ADD COLUMN garmin_total_is_resting INTEGER NOT NULL DEFAULT 0")
        } else {
            error("Missing tracker database migration: $oldVersion -> $newVersion")
        }
    }
}

private fun Double.isPositive() = isFinite() && this > 0.0
private fun Double.isNonNegative() = isFinite() && this >= 0.0
private fun Cursor.doubleOrNull(index: Int) = if (isNull(index)) null else getDouble(index)
private fun Cursor.intOrNull(index: Int) = if (isNull(index)) null else getInt(index)
@RequiresApi(26)
private fun Cursor.bodyRow() = Body(LocalDate.ofEpochDay(getLong(0)), doubleOrNull(1), doubleOrNull(2), doubleOrNull(3))
private fun Cursor.profileRow(offset: Int) = Profile(intOrNull(offset), doubleOrNull(offset + 1),
    intOrNull(offset + 2), doubleOrNull(offset + 3), getInt(offset + 4) != 0, getInt(offset + 5) != 0)

private fun SQLiteDatabase.profileAsOf(day: Long): Profile = rows(
    "SELECT $PROFILE_COLUMNS FROM profile WHERE day <= ? ORDER BY day DESC LIMIT 1", arrayOf(day.toString()),
) { profileRow(0) }.firstOrNull() ?: Profile()

private inline fun <T> SQLiteDatabase.rows(sql: String, args: Array<String>? = null, read: Cursor.() -> T): List<T> =
    rawQuery(sql, args).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.read()) }
    }

private inline fun SQLiteDatabase.transaction(block: SQLiteDatabase.() -> Unit) {
    beginTransaction()
    try {
        block()
        setTransactionSuccessful()
    } finally {
        endTransaction()
    }
}
