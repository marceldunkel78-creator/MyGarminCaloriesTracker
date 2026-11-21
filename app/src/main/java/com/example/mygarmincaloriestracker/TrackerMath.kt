package com.example.mygarmincaloriestracker

import androidx.annotation.RequiresApi
import java.time.LocalDateTime

/** [forecast] is projected end-of-day burn. Positive [balance] and [changeKg] denote a surplus. */
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
     * as an elapsed snapshot, not a daily goal. An elapsed Garmin snapshot isn't extrapolated.
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
            return Energy(intake, burned, forecast, balance, balance?.div(KCAL_PER_KG), source, resting, active, everyday)
        }

        val garmin = data.garmin
        val garminTotal = garmin?.total
        if (data.profile.useGarmin && garminTotal != null && garminTotal.isFinite() && garminTotal >= 0) {
            val active = garmin.active?.takeIf { it.isFinite() && it >= 0 }
            if (data.profile.garminTotalIsResting) {
                val total = active?.let { garminTotal + it }?.takeIf { it.isFinite() }
                return result(total, total?.takeIf { historical }, "Garmin", garminTotal, active)
            }
            val includedActive = active?.takeIf { it <= garminTotal }
            return result(garminTotal, garminTotal.takeIf { historical }, "Garmin",
                includedActive?.let { garminTotal - it }, includedActive)
        }

        val workout = data.activities.filter { it.minute <= cutoff }.sumOf { it.kcal }
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
