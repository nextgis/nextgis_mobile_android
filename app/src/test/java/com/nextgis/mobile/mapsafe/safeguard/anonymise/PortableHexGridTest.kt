package com.nextgis.mobile.mapsafe.safeguard.anonymise

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortableHexGridTest {

    @Test
    fun pointToCellIsStableAndSeparatesDistantPoints() {
        val first = PortableHexGrid.pointToCell(-18.1417, 178.4416, 8)
        val repeated = PortableHexGrid.pointToCell(-18.1417, 178.4416, 8)
        val distant = PortableHexGrid.pointToCell(-18.1148, 178.4818, 8)

        assertEquals(first, repeated)
        assertNotEquals(first, distant)
    }

    @Test
    fun cellBoundaryContainsSixValidCoordinates() {
        val cell = PortableHexGrid.pointToCell(-18.1417, 178.4416, 8)
        val boundary = PortableHexGrid.cellBoundaryToLatLon(cell)

        assertEquals(6, boundary.size)
        boundary.forEach { (latitude, longitude) ->
            assertTrue(latitude.isFinite())
            assertTrue(longitude.isFinite())
            assertTrue(latitude in -90.0..90.0)
            assertTrue(longitude in -180.0..180.0)
        }
    }
}
