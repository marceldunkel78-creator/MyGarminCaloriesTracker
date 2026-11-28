package com.example.mygarmincaloriestracker

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** SQLite integration tests. All fixture data and database files belong to the test APK. */
@RunWith(AndroidJUnit4::class)
class TrackerBackupInstrumentedTest {
    private lateinit var databaseFile: File
    private lateinit var isolatedContext: IsolatedDatabaseContext
    private var store: TrackerStore? = null
    private val day = LocalDate.of(2024, 2, 3)

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val testCache = instrumentation.context.cacheDir
        databaseFile = File.createTempFile("tracker-backup-test-", ".db", testCache)
        isolatedContext = IsolatedDatabaseContext(instrumentation.targetContext, databaseFile)
        assertSame(isolatedContext, isolatedContext.applicationContext)
        assertEquals(databaseFile.canonicalFile, isolatedContext.getDatabasePath("tracker.db").canonicalFile)
        assertNotEquals(
            instrumentation.targetContext.getDatabasePath("tracker.db").canonicalFile,
            databaseFile.canonicalFile,
        )
        store = TrackerStore(isolatedContext)
    }

    @After
    fun tearDown() {
        try {
            store?.close()
        } finally {
            // deleteDatabase also removes SQLite journal/WAL/SHM sidecars for this exact file.
            if (::databaseFile.isInitialized) SQLiteDatabase.deleteDatabase(databaseFile)
        }
    }

    @Test
    fun upgradesVersionTwoWithoutLosingMeasurements() {
        requireNotNull(store).close()
        SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { db ->
            db.execSQL("CREATE TABLE body(day INTEGER PRIMARY KEY, weight REAL, waist REAL, hip REAL)")
            db.execSQL("INSERT INTO body VALUES (${day.toEpochDay()}, 70, 80, 90)")
            db.version = 2
        }
        store = TrackerStore(isolatedContext)
        assertEquals(Body(day, 70.0, 80.0, 90.0), requireNotNull(store).manualBody(day))
        requireNotNull(store).saveImportedBody(Body(day, null, null, null, 20.0))
        openTestDatabase().use { db ->
            db.rawQuery("SELECT body_fat FROM effective_body WHERE day = ?", arrayOf(day.toEpochDay().toString())).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(20.0, cursor.getDouble(0), 0.0)
            }
        }
    }

    @Test
    fun bodyImportPreservesManualValuesAndBackupAndCanBeCleared() {
        val tracker = requireNotNull(store)
        tracker.saveBody(Body(day, 70.0, 80.0, null, 20.0))
        tracker.saveImportedBody(Body(day, 72.0, null, null, 24.0))
        assertEquals(Body(day, 70.0, 80.0, null, 20.0), tracker.day(day).body)
        tracker.saveBody(Body(day, null, 80.0, null, null))
        assertEquals(Body(day, 72.0, 80.0, null, 24.0), tracker.day(day).body)
        tracker.saveImportedBody(Body(day, null, null, null, 25.0))
        assertEquals(72.0, requireNotNull(tracker.day(day).body?.weight), 0.0)
        val backup = BackupJson.read(BackupJson.encode(tracker.backupSnapshot()).inputStream())
        tracker.clearImportedBody()
        assertEquals(null, tracker.day(day).body?.weight)
        tracker.restoreBackup(backup)
        assertEquals(25.0, requireNotNull(tracker.day(day).body?.bodyFat), 0.0)
        assertEquals(80.0, requireNotNull(tracker.manualBody(day)?.waist), 0.0)
        tracker.saveBody(Body(day.plusDays(1), null, null, null, 19.0))
        assertEquals(day.plusDays(1) to 19.0, tracker.latestMeasurements(day.plusDays(1)).bodyFat)
        assertEquals(day to 25.0, tracker.latestMeasurements(day).bodyFat)
    }

    @Test
    fun isolatedContextRoutesTrackerStoreToTemporaryDatabase() {
        val tracker = requireNotNull(store)
        val callsBefore = isolatedContext.pathRequests + isolatedContext.openRequests
        tracker.addDirectMeal(day, "Isolation marker", 12.5, 42)
        assertTrue(databaseFile.isFile)
        assertTrue((isolatedContext.pathRequests + isolatedContext.openRequests) > callsBefore)
        openTestDatabase().use { db ->
            db.rawQuery("SELECT name FROM meal", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Isolation marker", cursor.getString(0))
                assertFalse(cursor.moveToNext())
            }
        }
    }

    @Test
    fun jsonRoundTripRestoresAllTablesAndResetsAutoincrementToRestoredMaxima() {
        val tracker = requireNotNull(store)
        populate(tracker)
        val original = tracker.backupSnapshot()
        assertTrue(original.tables.values.all { it.isNotEmpty() })
        assertTrue(tracker.day(day).meals.any { it.isDirect })
        val decoded = BackupJson.read(BackupJson.encode(original).inputStream())
        assertEquals(original, decoded)

        mutate(tracker)
        assertNotEquals(original, tracker.backupSnapshot())
        tracker.restoreBackup(decoded)
        assertEquals(original, tracker.backupSnapshot())

        // Restore sets sqlite_sequence to MAX(id), not to an older historical high-water mark.
        val maxMeal = original.tables.getValue("meal").maxOf { it.getValue("id") as Long }
        val maxActivity = original.tables.getValue("activity").maxOf { it.getValue("id") as Long }
        assertEquals(mapOf("meal" to maxMeal, "activity" to maxActivity), sequences())
        tracker.addDirectMeal(day, "After restore", 1.0, 1)
        tracker.addActivity(day, "After restore", 1, 1.0, 2)
        assertEquals(maxMeal + 1, tracker.day(day).meals.single { it.name == "After restore" }.id)
        assertEquals(maxActivity + 1, tracker.day(day).activities.single { it.name == "After restore" }.id)
    }

    @Test
    fun legacyRestingFlagInBackupIsPreservedButIgnoredByCalculation() {
        val tracker = requireNotNull(store)
        val date = LocalDate.now().minusDays(1)
        tracker.saveProfile(Profile(useGarmin = true))
        tracker.addActivity(date, "Manual", 30, 100.0, 600)
        tracker.saveGarmin(date, GarminDay(1000.0, 200.0, emptyList()))
        val backup = tracker.backupSnapshot()
        val profiles = backup.tables.getValue("profile").map { it + ("garmin_total_is_resting" to 1L) }
        val legacy = backup.copy(tables = backup.tables + ("profile" to profiles))
        tracker.restoreBackup(BackupJson.read(BackupJson.encode(legacy).inputStream()))
        assertEquals(legacy, tracker.backupSnapshot())
        val energy = TrackerMath.energy(tracker.day(date), LocalDate.now().atTime(12, 0))
        assertEquals(1100.0, requireNotNull(energy.burned), 0.001)
        assertEquals(300.0, requireNotNull(energy.active), 0.001)
        assertEquals(800.0, requireNotNull(energy.resting), 0.001)
    }

    @Test
    fun failedInsertRollsBackAllTablesAndSequences() {
        val tracker = requireNotNull(store)
        populate(tracker)
        val backup = BackupJson.read(BackupJson.encode(tracker.backupSnapshot()).inputStream())
        mutate(tracker)
        val before = tracker.backupSnapshot()
        val sequencesBefore = sequences()
        assertNotEquals(backup, before)

        // The backup is valid. Fail after deletes and earlier table inserts have begun.
        openTestDatabase().use { db ->
            db.execSQL("CREATE TRIGGER fail_restore BEFORE INSERT ON meal BEGIN SELECT RAISE(ABORT, 'simulated'); END")
        }
        try {
            try {
                tracker.restoreBackup(backup)
                fail("Expected the SQLite trigger to abort restore")
            } catch (expected: SQLiteConstraintException) {
                assertTrue(expected.message.orEmpty().contains("simulated"))
            }
            assertEquals(before, tracker.backupSnapshot())
            assertEquals(sequencesBefore, sequences())
        } finally {
            openTestDatabase().use { it.execSQL("DROP TRIGGER IF EXISTS fail_restore") }
        }
    }

    @Test
    fun malformedOrInvalidBackupsAreRejectedWithoutChangingDatabase() {
        val tracker = requireNotNull(store)
        populate(tracker)
        val before = tracker.backupSnapshot()
        val sequencesBefore = sequences()
        val encoded = BackupJson.encode(before).toString(Charsets.UTF_8)
        val invalidDocuments = listOf(
            encoded.dropLast(1),
            encoded + "{}",
            encoded.replace("\"version\":2", "\"version\":2,\"version\":2"),
            encoded.replace("\"version\":2", "\"version\":3"),
            encoded.replace("\"minute\":480", "\"minute\":1440"),
        )
        assertTrue(encoded.contains("\"minute\":480"))
        for (json in invalidDocuments) {
            try {
                val decoded = BackupJson.read(json.byteInputStream())
                tracker.restoreBackup(decoded)
                fail("Invalid JSON or backup was accepted")
            } catch (_: Exception) {
                // Parser and validator can throw different exception subclasses.
            }
            assertEquals(before, tracker.backupSnapshot())
            assertEquals(sequencesBefore, sequences())
        }
        val meal = before.tables.getValue("meal").first()
        val invalid = before.copy(tables = before.tables + ("meal" to listOf(meal + ("name" to null))))
        try {
            tracker.restoreBackup(invalid)
            fail("Invalid in-memory backup was accepted")
        } catch (_: IllegalArgumentException) {
            // restoreBackup validates even when a caller bypasses JSON decoding.
        }
        assertEquals(before, tracker.backupSnapshot())
        assertEquals(sequencesBefore, sequences())
    }

    private fun populate(tracker: TrackerStore) {
        tracker.addFood("Test food \"食\"", 123.5, "portion", 75.0)
        tracker.addFood("Test food without unit", 30.0, null, null)
        tracker.addSport("Test sport", 2.5, 4.0, 6.5)
        tracker.addMeal(day, "Measured meal", 75.0, 92.625, 480)
        tracker.addDirectMeal(day, "Direct meal", 250.0, 720)
        tracker.addActivity(day, "Manual activity", 30, 180.0, 900)
        tracker.saveBody(Body(day, 71.5, null, 98.5, 22.0))
        tracker.saveImportedBody(Body(day, 72.0, null, null, 23.0))
        // Profile revisions are keyed by the actual local save date.
        tracker.saveProfile(Profile(35, 175.5, -50, 2100.0, true))
        tracker.saveGarmin(day, GarminDay(2300.0, null, listOf(
            ActivityEntry(901, "Garmin run", 40, 320.0, 600, "Garmin"),
            ActivityEntry(-4, "Garmin walk", 20, 70.0, 800, "Watch"),
        )))
    }

    private fun mutate(tracker: TrackerStore) {
        tracker.addFood("Changed food", 88.0, null, null)
        tracker.addSport("Changed sport", 1.0, 2.0, 3.0)
        tracker.addDirectMeal(day, "Changed meal", 33.0, 50)
        tracker.addActivity(day, "Changed activity", 5, 20.0, 60)
        tracker.saveBody(Body(day, 72.0, 80.0, null))
        tracker.saveProfile(Profile(40, 180.0, 0, 2000.0, false))
        tracker.clearGarmin()
        tracker.saveGarmin(day, GarminDay(null, 99.0, listOf(
            ActivityEntry(42, "Changed import", 10, 10.0, 100, "Other"),
        )))
    }

    private fun openTestDatabase(): SQLiteDatabase = SQLiteDatabase.openDatabase(
        databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE,
    )

    private fun sequences(): Map<String, Long> = openTestDatabase().use { db ->
        db.rawQuery("SELECT name, seq FROM sqlite_sequence WHERE name IN ('meal', 'activity')", null).use { cursor ->
            buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getLong(1)) }
        }
    }

    /** Never delegate a database operation to the app's real context. */
    private class IsolatedDatabaseContext(base: Context, private val file: File) : ContextWrapper(base) {
        var pathRequests = 0
            private set
        var openRequests = 0
            private set

        override fun getApplicationContext(): Context = this

        override fun getDatabasePath(name: String): File {
            require(name == "tracker.db") { "Unexpected database name" }
            pathRequests++
            return file
        }

        override fun openOrCreateDatabase(
            name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?,
        ): SQLiteDatabase {
            openRequests++
            return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).absolutePath, factory)
        }

        override fun openOrCreateDatabase(
            name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?,
        ): SQLiteDatabase {
            openRequests++
            return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).absolutePath, factory, errorHandler)
        }

        override fun deleteDatabase(name: String): Boolean = SQLiteDatabase.deleteDatabase(getDatabasePath(name))
    }
}
