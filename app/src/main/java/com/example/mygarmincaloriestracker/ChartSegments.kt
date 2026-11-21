package com.example.mygarmincaloriestracker

/** Adjacent slots for daily totals; connect across missing slots for actual measurements. */
internal fun chartSegments(present: List<Boolean>, connectGaps: Boolean): List<Pair<Int, Int>> {
    val result = mutableListOf<Pair<Int, Int>>()
    var previous: Int? = null
    present.forEachIndexed { index, hasValue ->
        if (hasValue) {
            previous?.let { result.add(it to index) }
            previous = index
        } else if (!connectGaps) {
            previous = null
        }
    }
    return result
}
