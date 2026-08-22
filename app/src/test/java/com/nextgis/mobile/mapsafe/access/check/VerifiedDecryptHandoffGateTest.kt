package com.nextgis.mobile.mapsafe.access.check

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifiedDecryptHandoffGateTest {
    private val contentUri = "content://com.nextgis.mobile.mapsafe.test/documents/package.pgp"
    private val hash = "ab".repeat(32)

    @Test
    fun allowsContentDocumentWithCanonicalLocalSha256() {
        assertTrue(VerifiedDecryptHandoffGate.isReady(contentUri, hash))
    }

    @Test
    fun blocksMissingOrMalformedFileIdentity() {
        assertFalse(VerifiedDecryptHandoffGate.isReady(null, hash))
        assertFalse(VerifiedDecryptHandoffGate.isReady("file:///sdcard/package.pgp", hash))
        assertFalse(VerifiedDecryptHandoffGate.isReady(contentUri, "abc123"))
        assertFalse(VerifiedDecryptHandoffGate.isReady(contentUri, hash.uppercase()))
    }
}
