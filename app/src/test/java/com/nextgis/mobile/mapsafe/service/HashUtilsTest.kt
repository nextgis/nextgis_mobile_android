package com.nextgis.mobile.mapsafe.service

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream

class HashUtilsTest {
    @Test
    fun inputStreamSha256MatchesKnownDigest() {
        val input = ByteArrayInputStream("abc".toByteArray(Charsets.UTF_8))

        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            HashUtils.sha256(input)
        )
    }
}
