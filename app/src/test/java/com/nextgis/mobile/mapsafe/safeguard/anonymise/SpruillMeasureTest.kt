package com.nextgis.mobile.mapsafe.safeguard.anonymise

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SpruillMeasureTest {

    @Test
    fun reportsMaximumDisclosureRiskWhenEveryParentIsNearest() {
        val result = SpruillMeasure.calculate(
            originalPoints = listOf(point(0.0), point(0.1)),
            maskedPoints = listOf(point(0.001), point(0.099))
        )

        assertEquals(2, result.evaluatedPoints)
        assertEquals(2, result.parentNearestCount)
        assertEquals(100.0, result.disclosureRiskPercent, 0.0)
        assertEquals(0.0, result.privacyRatingPercent, 0.0)
    }

    @Test
    fun reportsMaximumPrivacyRatingWhenAnotherOriginalIsAlwaysNearest() {
        val result = SpruillMeasure.calculate(
            originalPoints = listOf(point(0.0), point(0.1)),
            maskedPoints = listOf(point(0.099), point(0.001))
        )

        assertEquals(0, result.parentNearestCount)
        assertEquals(0.0, result.disclosureRiskPercent, 0.0)
        assertEquals(100.0, result.privacyRatingPercent, 0.0)
    }

    @Test
    fun reportsProportionAndInversePrivacyRating() {
        val result = SpruillMeasure.calculate(
            originalPoints = listOf(point(0.0), point(0.1)),
            maskedPoints = listOf(point(0.001), point(0.001))
        )

        assertEquals(1, result.parentNearestCount)
        assertEquals(50.0, result.disclosureRiskPercent, 0.0)
        assertEquals(50.0, result.privacyRatingPercent, 0.0)
    }

    @Test
    fun treatsAnEqualDistanceParentAsParentNearest() {
        val result = SpruillMeasure.calculate(
            originalPoints = listOf(point(0.0), point(0.0)),
            maskedPoints = listOf(point(0.001), point(0.001))
        )

        assertEquals(2, result.parentNearestCount)
        assertEquals(100.0, result.disclosureRiskPercent, 0.0)
    }

    @Test
    fun rejectsUnpairedDatasets() {
        assertThrows(IllegalArgumentException::class.java) {
            SpruillMeasure.calculate(
                originalPoints = listOf(point(0.0)),
                maskedPoints = emptyList()
            )
        }
    }

    private fun point(longitude: Double): SpruillMeasure.Coordinate {
        return SpruillMeasure.Coordinate(longitude = longitude, latitude = 0.0)
    }
}
