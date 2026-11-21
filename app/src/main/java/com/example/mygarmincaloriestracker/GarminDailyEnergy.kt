package com.example.mygarmincaloriestracker

import java.time.Instant

/** A daily snapshot can end at the last millisecond/second rather than at the next midnight. */
internal fun garminDailyValue(start: Instant, end: Instant, records: List<Triple<Instant, Instant, Double>>): Double? =
    records.asSequence()
        .filter { (from, to, kcal) ->
            from == start && to >= end.minusSeconds(1) && to <= end && kcal.isFinite() && kcal >= 0
        }
        .map { it.third }
        .distinct()
        .toList()
        .singleOrNull()

/** Garmin can export the same daily snapshot as adjacent slices instead of a single record. */
internal fun garminSegmentedDailyValue(start: Instant, end: Instant, records: List<Triple<Instant, Instant, Double>>): Double? {
    val segments = records.distinct().filter { (from, to, _) -> from < end && to > start }.sortedBy { it.first }
    if (segments.size < 2) return null
    var boundary = start
    var total = 0.0
    for ((from, to, kcal) in segments) {
        if (from < boundary || from > boundary.plusSeconds(1) || (boundary == start && from != start) ||
            to <= from || to > end || !kcal.isFinite() || kcal < 0) return null
        boundary = to
        total += kcal
    }
    return total.takeIf { it.isFinite() && boundary >= end.minusSeconds(1) }
}
