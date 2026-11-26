package com.example.mygarmincaloriestracker

import androidx.annotation.RequiresApi
import java.time.LocalDateTime

/** [forecast] is projected end-of-day burn. Positive balances and [changeKg] denote a surplus. */
data class Energy(
    val intake: Double,
    val burned: Double?,
    val forecast: Double?,
    val balance: Double?,
    val changeKg: Double?,
    val source: String,
    val resting: Double? = null,
    val active: Double? = null,
    val everyday: Double? = null,
    /** Recorded intake minus projected end-of-day burn; null when either is unknown. */
    val projectedBalance: Double? = null,
)

object TrackerMath {
    private const val SECONDS_PER_DAY = 86_400.0
    private const val KCAL_PER_KG = 7_700.0
    private const val SEDENTARY_FACTOR = 1.2

    /** Converts gross MET to net workout kcal, subtracting 1 MET already covered by baseline. */
    fun activityKcal(met: Double, weight: Double, minutes: Int): Double {
        require(met.isFinite() && met >= 0 && weight.isFinite() && weight > 0 && minutes >= 0)
        return (met - 1.0).coerceAtLeast(0.0) * 3.5 * weight / 200.0 * minutes
    }

    /**
     * Entry minutes are local logging/completion times. Today's Garmin totals must be imported
     * as an elapsed snapshot, not a daily goal. For today, resting energy is extrapolated
     * using the elapsed fraction of the local clock day, and recorded active energy is added
     * once without extrapolation. Garmin imports have no snapshot timestamp, so this is an
     * approximation; a stale import can underestimate projected resting burn.
     * [Energy.resting] is resting energy, or the configured baseline in manual mode.
     */
    @RequiresApi(26)
    fun energy(data: DayData, now: LocalDateTime): Energy {
        val today = now.toLocalDate()
        if (data.day > today) return Energy(0.0, null, null, null, null, "Keine Daten")

        val historical = data.day < today
        val time = now.toLocalTime()
        val cutoff = if (historical) 1439 else time.hour * 60 + time.minute
        val fraction = if (historical) 1.0 else
            (time.toSecondOfDay() + time.nano / 1e9) / SECONDS_PER_DAY
        val meals = data.meals.filter { it.minute <= cutoff }
        val intake = meals.sumOf { it.kcal }

        fun result(burned: Double?, forecast: Double?, source: String, resting: Double?, active: Double?, everyday: Double? = null): Energy {
            val balance = if (meals.isNotEmpty()) burned?.let { intake - it } else null
            val safeForecast = forecast?.takeIf { (historical || fraction > 0.0) && it.isFinite() }
            val projectedBalance = if (meals.isNotEmpty() && meals.all { it.kcal.isFinite() && it.kcal >= 0 } && intake.isFinite()) {
                safeForecast?.let { (intake - it).takeIf { value -> value.isFinite() } }
            } else null
            return Energy(intake, burned, safeForecast, balance, balance?.div(KCAL_PER_KG), source, resting, active, everyday, projectedBalance)
        }

        val workout = data.activities.filter { it.minute <= cutoff }.sumOf { it.kcal }
        val garmin = data.garmin
        val garminTotal = garmin?.total
        if (data.profile.useGarmin && garminTotal != null && garminTotal.isFinite() && garminTotal >= 0) {
            val active = garmin.active?.takeIf { it.isFinite() && it >= 0 }
            val includedActive = active?.takeIf { it <= garminTotal }
            val resting = includedActive?.let { garminTotal - it }
            val total = (garminTotal + workout).takeIf { it.isFinite() }
            val forecast = when {
                historical -> total
                fraction > 0.0 && resting != null -> resting / fraction + includedActive + workout
                else -> null
            }
            // A missing Garmin active component remains unknown, even when manual entries exist.
            return result(total, forecast, "Garmin", resting, includedActive?.plus(workout)?.takeIf { it.isFinite() })
        }

        val manual = data.profile.manual?.takeIf { it.isFinite() && it > 0 }
        val resting = if (manual == null) mifflin(data) else null
        val baseline = manual ?: resting?.times(SEDENTARY_FACTOR)
        val elapsedBaseline = baseline?.times(fraction)
        val source = when {
            manual != null -> "Manuell"
            baseline != null -> "Mifflin-St Jeor"
            else -> "Keine Daten"
        }
        return result(elapsedBaseline?.plus(workout), baseline?.plus(workout), source,
            (resting ?: manual)?.times(fraction), workout,
            resting?.let { it * (SEDENTARY_FACTOR - 1) * fraction })
    }

    /**
     * Counts consecutive completed historical days ending yesterday, with intake strictly less
     * than burn. Missing dates, meals, or energy break the streak; today is never included.
     * Duplicate dates are ambiguous and also break the streak. Input order does not matter.
     */
    @RequiresApi(26)
    fun deficitStreak(days: List<DayData>, now: LocalDateTime): Int {
        val byDay = days.groupBy { it.day }
        var day = now.toLocalDate().minusDays(1)
        var streak = 0
        while (true) {
            val data = byDay[day]?.singleOrNull() ?: break
            if (data.meals.isEmpty() || data.meals.any { !it.kcal.isFinite() || it.kcal < 0 }) break
            val energy = energy(data, now)
            val burn = energy.burned?.takeIf { it.isFinite() && it >= 0 } ?: break
            if (energy.balance == null || !energy.intake.isFinite() || energy.intake >= burn) break
            streak++
            day = day.minusDays(1)
        }
        return streak
    }

    // The equation estimates resting energy before adding everyday movement and logged workouts.
    @RequiresApi(26)
    private fun mifflin(data: DayData): Double? {
        val age = data.profile.age?.takeIf { it > 0 } ?: return null
        val height = data.profile.height?.takeIf { it.isFinite() && it > 0 } ?: return null
        val offset = data.profile.formulaOffset ?: return null
        val weight = sequenceOf(data.body, data.latestBody)
            .filterNotNull()
            .filter { it.day <= data.day }
            .mapNotNull { it.weight?.takeIf { w -> w.isFinite() && w > 0 } }
            .firstOrNull() ?: return null
        return (10 * weight + 6.25 * height - 5.0 * age + offset).takeIf { it.isFinite() && it > 0 }
    }
}
