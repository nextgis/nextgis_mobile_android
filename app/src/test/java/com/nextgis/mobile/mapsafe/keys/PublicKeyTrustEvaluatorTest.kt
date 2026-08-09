package com.nextgis.mobile.mapsafe.keys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicKeyTrustEvaluatorTest {
    private val identity = PublicKeyDirectoryIdentity(
        serverUrl = "https://example.nextgis.com",
        accountName = "field-team",
        groupId = 20,
        userId = 10
    )

    @Test
    fun firstObservationRequiresExplicitAcceptance() {
        val record = PublicKeyTrustEvaluator.observe("record", null, observation(FINGERPRINT_A), 100)

        assertEquals(PublicKeyTrustState.DISCOVERED, record.trustState)
        assertNull(record.acceptedFingerprint)
        assertTrue(FINGERPRINT_A in record.nonSelectableFingerprints())
    }

    @Test
    fun acceptedFingerprintRemainsAcceptedWhenDirectoryIsUnchanged() {
        val discovered = PublicKeyTrustEvaluator.observe("record", null, observation(FINGERPRINT_A), 100)
        val accepted = PublicKeyTrustEvaluator.accept(discovered, 110)
        val observedAgain = PublicKeyTrustEvaluator.observe(
            "record",
            accepted,
            observation(FINGERPRINT_A, version = 1),
            120
        )

        assertEquals(PublicKeyTrustState.ACCEPTED, observedAgain.trustState)
        assertEquals(FINGERPRINT_A, observedAgain.acceptedFingerprint)
        assertFalse(FINGERPRINT_A in observedAgain.nonSelectableFingerprints())
    }

    @Test
    fun changedFingerprintIsQuarantinedUntilAccepted() {
        val discovered = PublicKeyTrustEvaluator.observe("record", null, observation(FINGERPRINT_A), 100)
        val accepted = PublicKeyTrustEvaluator.accept(discovered, 110)
        val changed = PublicKeyTrustEvaluator.observe(
            "record",
            accepted,
            observation(FINGERPRINT_B, version = 2),
            120
        )

        assertEquals(PublicKeyTrustState.CHANGE_PENDING, changed.trustState)
        assertEquals(FINGERPRINT_A, changed.acceptedFingerprint)
        assertTrue(FINGERPRINT_A in changed.nonSelectableFingerprints())
        assertTrue(FINGERPRINT_B in changed.nonSelectableFingerprints())

        val replacement = PublicKeyTrustEvaluator.accept(changed, 130)
        assertEquals(PublicKeyTrustState.ACCEPTED, replacement.trustState)
        assertEquals(FINGERPRINT_B, replacement.acceptedFingerprint)
        assertTrue(FINGERPRINT_A in replacement.previousFingerprints)
        assertTrue(FINGERPRINT_A in replacement.nonSelectableFingerprints())
        assertFalse(FINGERPRINT_B in replacement.nonSelectableFingerprints())

        val changedBack = PublicKeyTrustEvaluator.observe(
            "record",
            replacement,
            observation(FINGERPRINT_A, version = 3),
            140
        )
        val restored = PublicKeyTrustEvaluator.accept(changedBack, 150)
        assertEquals(FINGERPRINT_A, restored.acceptedFingerprint)
        assertFalse(FINGERPRINT_A in restored.nonSelectableFingerprints())
        assertTrue(FINGERPRINT_B in restored.nonSelectableFingerprints())
    }

    @Test
    fun removedMemberKeyCannotBeSelectedForNewEncryption() {
        val discovered = PublicKeyTrustEvaluator.observe("record", null, observation(FINGERPRINT_A), 100)
        val accepted = PublicKeyTrustEvaluator.accept(discovered, 110)
        val removed = PublicKeyTrustEvaluator.memberRemoved(accepted, 120)

        assertEquals(PublicKeyTrustState.MEMBER_REMOVED, removed.trustState)
        assertTrue(FINGERPRINT_A in removed.nonSelectableFingerprints())
    }

    @Test
    fun missingDirectoryEntryBlocksPreviouslyAcceptedKey() {
        val discovered = PublicKeyTrustEvaluator.observe("record", null, observation(FINGERPRINT_A), 100)
        val accepted = PublicKeyTrustEvaluator.accept(discovered, 110)
        val missing = PublicKeyTrustEvaluator.directoryEntryMissing(accepted, 120)

        assertEquals(PublicKeyTrustState.SUPERSEDED, missing.trustState)
        assertTrue(FINGERPRINT_A in missing.nonSelectableFingerprints())
    }

    @Test
    fun revokedDirectoryEntryCannotBeAccepted() {
        val revoked = PublicKeyTrustEvaluator.observe(
            "record",
            null,
            observation(FINGERPRINT_A, status = "revoked"),
            100
        )

        assertEquals(PublicKeyTrustState.REVOKED, revoked.trustState)
        assertFalse(revoked.needsUserReview)
        assertTrue(FINGERPRINT_A in revoked.nonSelectableFingerprints())
    }

    private fun observation(
        fingerprint: String,
        version: Int = 1,
        status: String = "active"
    ) = PublicKeyObservation(
        identity = identity,
        displayName = "Test User",
        fingerprint = fingerprint,
        keyVersion = version,
        bucketId = 99,
        publishedAt = "2026-08-09T00:00:00Z",
        directoryStatus = status
    )

    companion object {
        private const val FINGERPRINT_A = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        private const val FINGERPRINT_B = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"
    }
}
