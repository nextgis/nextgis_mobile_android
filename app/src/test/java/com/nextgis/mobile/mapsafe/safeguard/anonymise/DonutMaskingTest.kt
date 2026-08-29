package com.nextgis.mobile.mapsafe.safeguard.anonymise

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class DonutMaskingTest {

    @Test
    fun generatedPointsStayInsideUserDistanceBounds() {
        repeat(250) {
            val result = DonutMasking.maskPoint(
                longitude = 174.7762,
                latitude = -41.2865,
                minDistanceMetres = 50.0,
                maxDistanceMetres = 150.0
            )

            assertTrue(result.distanceMetres in 50.0..150.0)
            assertTrue(result.longitude in -180.0..180.0)
            assertTrue(result.latitude in -90.0..90.0)
            assertEquals(
                result.distanceMetres,
                haversineDistanceMetres(-41.2865, 174.7762, result.latitude, result.longitude),
                0.05
            )
        }
    }

    @Test
    fun zeroDistanceLeavesPointUnchanged() {
        val result = DonutMasking.maskPoint(
            longitude = 178.4419,
            latitude = -18.1416,
            minDistanceMetres = 0.0,
            maxDistanceMetres = 0.0
        )

        assertEquals(178.4419, result.longitude, 1e-9)
        assertEquals(-18.1416, result.latitude, 1e-9)
        assertEquals(0.0, result.distanceMetres, 0.0)
    }

    private fun haversineDistanceMetres(
        latitude1: Double,
        longitude1: Double,
        latitude2: Double,
        longitude2: Double
    ): Double {
        val lat1 = Math.toRadians(latitude1)
        val lat2 = Math.toRadians(latitude2)
        val deltaLat = lat2 - lat1
        val deltaLon = Math.toRadians(longitude2 - longitude1)
        val a = sin(deltaLat / 2.0) * sin(deltaLat / 2.0) +
            cos(lat1) * cos(lat2) * sin(deltaLon / 2.0) * sin(deltaLon / 2.0)
        return 2.0 * 6371000.0 * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}
