package com.example.mygarmincaloriestracker

import androidx.annotation.RequiresApi
import java.time.LocalDate

/** Daily samples, before any monthly graph aggregation. Endpoints are inclusive. */
@RequiresApi(26)
internal object StatsSummary {
    fun sum(samples: List<Pair<LocalDate, Double>>, from: LocalDate, to: LocalDate, today: LocalDate): Double? {
        val values = samples.filter { (date, value) -> date >= from && date <= to && date <= today && value.isFinite() }
        if (values.isEmpty()) return null
        return values.sumOf { it.second }.takeIf { it.isFinite() }
    }

    fun difference(samples: List<Pair<LocalDate, Double>>, from: LocalDate, to: LocalDate, today: LocalDate): Double? {
        val values = samples.filter { (date, value) -> date >= from && date <= to && date <= today && value.isFinite() }
        val first = values.minByOrNull { it.first } ?: return null
        val last = values.maxByOrNull { it.first } ?: return null
        if (first.first == last.first) return null
        return (last.second - first.second).takeIf { it.isFinite() }
    }
}
