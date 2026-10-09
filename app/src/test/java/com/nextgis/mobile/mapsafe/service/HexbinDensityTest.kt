package com.nextgis.mobile.mapsafe.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HexbinDensityTest {

    @Test
    fun singleCountDatasetUsesTheLightestClass() {
        assertEquals(0, HexbinDensity.classForCount(1, 1))
    }

    @Test
    fun maximumCountUsesTheDarkestClass() {
        assertEquals(
            HexbinDensity.CLASS_COUNT - 1,
            HexbinDensity.classForCount(100, 100)
        )
    }

    @Test
    fun classesNeverBecomeLighterAsCountsIncrease() {
        val classes = (1..100).map { HexbinDensity.classForCount(it, 100) }

        assertTrue(classes.zipWithNext().all { (lower, higher) -> lower <= higher })
        assertTrue(classes.distinct().size > 1)
    }
}
