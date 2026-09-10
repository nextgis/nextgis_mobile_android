package com.nextgis.mobile.mapsafe.community

import com.nextgis.mobile.mapsafe.keys.CachedPublicKeyRecord
import com.nextgis.mobile.mapsafe.keys.MapSafeSecurityPreferences
import com.nextgis.mobile.mapsafe.keys.PublicKeyDirectoryIdentity
import com.nextgis.mobile.mapsafe.keys.PublicKeyTrustState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommunityPackageAudienceRepositoryTest {
    private val selection = MapSafeSecurityPreferences.Selection(
        accountName = "Steven (data custodian)",
        serverUrl = "https://mapsafe.nextgis.com/",
        groupId = 77,
        groupName = "North Whangarei Community",
        currentUserId = 10,
        groupMemberCount = 4
    )

    @Test
    fun encryptionRecipientsMapToCurrentAndAcceptedCommunityUsers() {
        val record = CommunityPackageAudienceRepository.fromEncryptionRecipients(
            sha256 = "a".repeat(64),
            fileName = "north-whangarei.pgp",
            selection = selection,
            recipientFingerprints = listOf("AA11", "BB22"),
            localFingerprint = "AA11",
            directoryRecords = listOf(acceptedAmber())
        )

        assertNotNull(record)
        assertEquals(setOf(10L, 20L), record!!.audience.recipientUserIds)
        assertTrue(record.audience.unmappedFingerprints.isEmpty())
        assertTrue(record.audience.isReadyForRestrictedUpload)
    }

    @Test
    fun individuallyImportedKeyRemainsUnmappedAndBlocksRestrictedUpload() {
        val record = CommunityPackageAudienceRepository.fromEncryptionRecipients(
            sha256 = "a".repeat(64),
            fileName = "north-whangarei.pgp",
            selection = selection,
            recipientFingerprints = listOf("AA11", "CC33"),
            localFingerprint = "AA11",
            directoryRecords = listOf(acceptedAmber())
        )

        assertEquals(setOf("CC33"), record!!.audience.unmappedFingerprints)
        assertTrue(!record.audience.isReadyForRestrictedUpload)
    }

    private fun acceptedAmber() = CachedPublicKeyRecord(
        recordId = "amber",
        identity = PublicKeyDirectoryIdentity(
            serverUrl = "https://mapsafe.nextgis.com",
            accountName = "Amber",
            groupId = 77,
            userId = 20
        ),
        displayName = "Amber (authorised researcher)",
        observedFingerprint = "BB22",
        acceptedFingerprint = "BB22",
        previousFingerprints = emptySet(),
        keyVersion = 1,
        bucketId = 100,
        publishedAt = "2026-09-09T00:00:00Z",
        lastSeenAt = 1,
        trustState = PublicKeyTrustState.ACCEPTED
    )
}
