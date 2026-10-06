package com.nextgis.mobile.mapsafe

import com.nextgis.maplibui.util.AccountNameUtil
import org.junit.Assert.assertEquals
import org.junit.Test

class AccountNameUtilTest {
    @Test
    fun unusedServerNameRemainsUnchanged() {
        assertEquals(
            "mapsafe.nextgis.com",
            AccountNameUtil.uniqueName("mapsafe.nextgis.com", "steven") { false }
        )
    }

    @Test
    fun anotherLoginOnTheSameServerGetsReadableUniqueName() {
        val existing = setOf("mapsafe.nextgis.com")
        assertEquals(
            "mapsafe.nextgis.com (amber)",
            AccountNameUtil.uniqueName("mapsafe.nextgis.com", "amber", existing::contains)
        )
    }

    @Test
    fun repeatedDisplayNameReceivesNumericSuffix() {
        val existing = setOf(
            "mapsafe.nextgis.com",
            "mapsafe.nextgis.com (amber)",
            "mapsafe.nextgis.com (amber) 2"
        )
        assertEquals(
            "mapsafe.nextgis.com (amber) 3",
            AccountNameUtil.uniqueName("mapsafe.nextgis.com", "amber", existing::contains)
        )
    }
}
