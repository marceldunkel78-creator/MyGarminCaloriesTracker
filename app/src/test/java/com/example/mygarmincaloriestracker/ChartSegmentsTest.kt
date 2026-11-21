package com.example.mygarmincaloriestracker

import org.junit.Assert.assertEquals
import org.junit.Test

class ChartSegmentsTest {
    @Test fun bodyMeasurementsConnectAcrossEmptyDays() {
        assertEquals(listOf(0 to 3, 3 to 5), chartSegments(listOf(true, false, false, true, false, true), true))
    }

    @Test fun energyValuesDoNotBridgeMissingDays() {
        assertEquals(listOf(0 to 1, 4 to 5), chartSegments(listOf(true, true, false, false, true, true), false))
    }

    @Test fun aSingleOrMissingMeasurementHasNoLine() {
        assertEquals(emptyList<Pair<Int, Int>>(), chartSegments(listOf(false, true, false), true))
        assertEquals(emptyList<Pair<Int, Int>>(), chartSegments(emptyList(), true))
    }
}
