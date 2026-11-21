package com.example.mygarmincaloriestracker

import android.content.Context
import android.os.Build
import androidx.activity.result.contract.ActivityResultContract
import androidx.annotation.RequiresApi
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.text.NumberFormat
import java.util.Locale
import java.util.UUID
import kotlin.reflect.KClass

@RequiresApi(Build.VERSION_CODES.O) // java.time; Garmin sync itself is gated to Android 14.
internal class GarminHealth(context: Context) {
    private val appContext = context.applicationContext

    /** Checks the Android/Health Connect gate, not whether Garmin has synced. */
    fun available(): Boolean = Build.VERSION.SDK_INT >= 34 &&
        HealthConnectClient.getSdkStatus(appContext) == HealthConnectClient.SDK_AVAILABLE

    suspend fun granted(): Set<String> = if (available()) {
        HealthConnectClient.getOrCreate(appContext).permissionController
            .getGrantedPermissions().intersect(permissions)
    } else {
        emptySet()
    }

    data class ReadResult(val data: GarminDay, val details: String = "")

    /** A null total means Garmin's stored total records don't substantiate a usable day value. */
    suspend fun read(day: LocalDate, includeDetails: Boolean = false): ReadResult {
        check(available()) { "Garmin's Health Connect bridge is unavailable on this device." }
        val client = HealthConnectClient.getOrCreate(appContext)
        val grants = client.permissionController.getGrantedPermissions()
        val canReadTotal = readTotal in grants
        val canReadActive = readActive in grants
        val canReadExercise = readExercise in grants
        if (!canReadTotal && !canReadActive && !canReadExercise) {
            throw SecurityException("Grant at least one Garmin Health Connect read permission.")
        }

        val zone = ZoneId.systemDefault()
        val start = day.atStartOfDay(zone).toInstant()
        // Health Connect time filters have millisecond precision.
        val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant()
        val end = minOf(dayEnd, Instant.ofEpochMilli(System.currentTimeMillis()))
        if (end <= start) return ReadResult(GarminDay(null, null, emptyList()))

        val lookback = day.minusDays(1).atStartOfDay(zone).toInstant()
        val sessions = if (canReadExercise) {
            client.readDayRecords(ExerciseSessionRecord::class, lookback, start, end)
                .filter { it.startTime < end && it.endTime > start }
                .distinctBy { it.metadata.id }
                .sortedBy { it.startTime }
        } else {
            emptyList()
        }
        val totalRecords = if (canReadTotal) {
            client.readDayRecords(TotalCaloriesBurnedRecord::class, lookback, start, dayEnd)
                .map { CalorieInterval(it.startTime, it.endTime, it.energy.inKilocalories) }
        } else {
            emptyList()
        }

        // Garmin may store a day's value in a midnight-to-midnight record, even for today.
        // Use that payload as supplied; the aggregate API prorates it if queried up to 'now'.
        val dailyTotal = totalRecords.dailyValue(start, dayEnd)
        val segmentedTotal = if (dailyTotal == null) totalRecords.segmentedDailyValue(start, dayEnd) else null
        val syncedEnd = totalRecords.asSequence().filter { it.start < end && it.end <= end }
            .maxOfOrNull { it.end }
        val contiguousTotal = if (dailyTotal == null && segmentedTotal == null && syncedEnd != null && syncedEnd > start &&
            (dayEnd > end || dayEnd == syncedEnd) && totalRecords.covers(start, syncedEnd)) {
            client.aggregateKcal(TotalCaloriesBurnedRecord.ENERGY_TOTAL, start, syncedEnd)
        } else null
        val total = dailyTotal ?: segmentedTotal ?: contiguousTotal
        val activeRecords = if (canReadActive) {
            client.readDayRecords(ActiveCaloriesBurnedRecord::class, lookback, start, dayEnd)
                .map { CalorieInterval(it.startTime, it.endTime, it.energy.inKilocalories) }
        } else {
            emptyList()
        }

        val active = if (canReadActive) {
            if (contiguousTotal != null) {
                client.aggregateKcal(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL, start, checkNotNull(syncedEnd))
            } else {
                activeRecords.dailyValue(start, dayEnd)
                    ?: client.aggregateKcal(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL, start, end)
            }
        } else null

        val activities = sessions.map { session ->
            val from = maxOf(start, session.startTime)
            val to = minOf(end, session.endTime)
            val localStart = from.atZone(zone)
            ActivityEntry(
                id = UUID.nameUUIDFromBytes("$garminPackage:${session.metadata.id}".toByteArray(Charsets.UTF_8))
                    .mostSignificantBits,
                name = session.title?.trim()?.takeIf { it.isNotEmpty() } ?: session.defaultName(),
                minutes = Duration.between(from, to).toMinutes().coerceAtLeast(1L).toInt(),
                // Total records for the workout also contain resting energy, not just active energy.
                kcal = activeRecords.workoutKcal(session, from, to) ?: 0.0,
                minute = localStart.hour * 60 + localStart.minute,
                source = "Garmin",
            )
        }
        val details = if (includeDetails) {
            val time = DateTimeFormatter.ofPattern("dd.MM. HH:mm:ss", Locale.GERMANY).withZone(zone)
            val number = NumberFormat.getNumberInstance(Locale.GERMANY).apply { maximumFractionDigits = 1 }
            val activeEnd = if (contiguousTotal != null) checkNotNull(syncedEnd) else end
            val separateActive = if (canReadActive && activeEnd < end) {
                activeRecords.dailyValue(start, dayEnd)
                    ?: client.aggregateKcal(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL, start, end)
            } else null
            buildString {
                append("Gesamtwert: ")
                append(when {
                    !canReadTotal -> "Lesezugriff nicht erteilt."
                    dailyTotal != null -> "Garmin-Tagesdatensatz, unverändert."
                    segmentedTotal != null -> "Summe der Garmin-Tagesabschnitte, ohne zeitanteilige Kürzung."
                    contiguousTotal != null -> "Zeitreihe von ${time.format(start)} bis ${time.format(checkNotNull(syncedEnd))}."
                    else -> "Kein vollständiger Tagesdatensatz und keine lückenlose Zeitreihe bis zum Tagesende (bei heute: bis zum letzten nutzbaren Messpunkt)."
                })
                if (canReadActive && activeEnd < end) {
                    val extended = separateActive?.let { "${number.format(it)} kcal" } ?: "nicht verfügbar"
                    append("\nAktivkalorien im Tracker beziehen sich auf denselben Zeitraum wie der Gesamtwert. Bis ${time.format(end)} separat verfügbar: $extended.")
                }
                fun records(label: String, allowed: Boolean, intervals: List<CalorieInterval>) {
                    append("\n\n$label: ")
                    if (!allowed) {
                        append("Lesezugriff nicht erteilt.")
                        return
                    }
                    val overlapping = intervals.filter { it.start < dayEnd && it.end > start }.sortedBy { it.start }
                    append("${overlapping.size} Garmin-Rohdatensätze")
                    if (overlapping.isNotEmpty()) {
                        append("\nZeitraum: ${time.format(overlapping.first().start)} – ${time.format(overlapping.maxOf { it.end })}")
                        val sample = if (overlapping.size <= 6) overlapping else overlapping.take(3) + overlapping.takeLast(3)
                        sample.forEachIndexed { index, record ->
                            if (overlapping.size > 6 && index == 3) append("\n…")
                            append("\n${time.format(record.start)} – ${time.format(record.end)}: ${number.format(record.kcal)} kcal")
                        }
                    }
                }
                records("Gesamtkalorien", canReadTotal, totalRecords)
                records("Aktivkalorien", canReadActive, activeRecords)
            }
        } else ""
        return ReadResult(GarminDay(total, active, activities), details)
    }

    private suspend fun HealthConnectClient.aggregateKcal(
        metric: AggregateMetric<Energy>,
        start: Instant,
        end: Instant,
    ): Double? {
        val result = aggregate(
            AggregateRequest(
                metrics = setOf(metric),
                timeRangeFilter = TimeRangeFilter.between(start, end),
                dataOriginFilter = garminOrigins,
            ),
        )
        return result[metric]?.inKilocalories?.takeIf { result.dataOrigins == garminOrigins }
    }

    private suspend fun <T : Record> HealthConnectClient.readDayRecords(
        type: KClass<T>,
        lookback: Instant,
        start: Instant,
        end: Instant,
    ): List<T> = try {
        // Raw reads can filter by start time; include the previous local date for overnight records.
        readAll(type, lookback, end)
    } catch (_: SecurityException) {
        // The extra date may cross Health Connect's history limit. Retry the requested day;
        // genuine permission errors still propagate if that read is denied.
        readAll(type, start, end)
    }

    private suspend fun <T : Record> HealthConnectClient.readAll(
        type: KClass<T>,
        start: Instant,
        end: Instant,
    ): List<T> {
        val records = mutableListOf<T>()
        var token: String? = null
        do {
            val page = readRecords(
                ReadRecordsRequest(
                    recordType = type,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    dataOriginFilter = garminOrigins,
                    pageToken = token,
                ),
            )
            records += page.records.filter { it.metadata.dataOrigin in garminOrigins }
            token = page.pageToken
        } while (token != null)
        return records
    }

    private data class CalorieInterval(val start: Instant, val end: Instant, val kcal: Double)

    private fun List<CalorieInterval>.dailyValue(start: Instant, end: Instant): Double? =
        garminDailyValue(start, end, map { Triple(it.start, it.end, it.kcal) })

    private fun List<CalorieInterval>.segmentedDailyValue(start: Instant, end: Instant): Double? =
        garminSegmentedDailyValue(start, end, map { Triple(it.start, it.end, it.kcal) })

    private fun List<CalorieInterval>.covers(start: Instant, end: Instant): Boolean {
        var coveredUntil = start
        for (interval in sortedBy { it.start }) {
            if (interval.start > coveredUntil) return false
            if (interval.end > coveredUntil) coveredUntil = interval.end
            if (coveredUntil >= end) return true
        }
        return false
    }

    private fun List<CalorieInterval>.workoutKcal(
        session: ExerciseSessionRecord,
        from: Instant,
        to: Instant,
    ): Double? {
        // A daily calorie record overlapping a session is not a workout calorie record.
        val candidates = filter {
            it.start >= session.startTime && it.end <= session.endTime &&
                it.start < to && it.end > from && it.kcal > 0.0
        }.distinct().sortedBy { it.start }
        val wholeSession = candidates.filter { it.start == session.startTime && it.end == session.endTime }
        if (wholeSession.isNotEmpty()) return wholeSession.singleOrNull()?.kcalWithin(from, to)
        if (candidates.zipWithNext().any { (first, second) -> first.end > second.start }) return null
        return candidates.sumOf { it.kcalWithin(from, to) }.takeIf { it > 0.0 }
    }

    private fun CalorieInterval.kcalWithin(from: Instant, to: Instant): Double {
        val overlap = Duration.between(maxOf(start, from), minOf(end, to)).toNanos()
        return kcal * (overlap.toDouble() / Duration.between(start, end).toNanos())
    }

    private fun ExerciseSessionRecord.defaultName(): String = when (exerciseType) {
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "Laufen"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "Radfahren"
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "Gehen"
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "Wandern"
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER,
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL -> "Schwimmen"
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING -> "Krafttraining"
        else -> "Garmin-Aktivität"
    }

    companion object {
        private const val garminPackage = "com.garmin.android.apps.connectmobile"
        private val garminOrigins = setOf(DataOrigin(garminPackage))
        private val readTotal = HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class)
        private val readActive = HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class)
        private val readExercise = HealthPermission.getReadPermission(ExerciseSessionRecord::class)

        val permissions: Set<String> = setOf(readTotal, readActive, readExercise)

        fun permissionContract(): ActivityResultContract<Set<String>, Set<String>> =
            PermissionController.createRequestPermissionResultContract()
    }
}
