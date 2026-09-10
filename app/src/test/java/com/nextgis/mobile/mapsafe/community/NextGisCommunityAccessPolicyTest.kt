package com.nextgis.mobile.mapsafe.community

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NextGisCommunityAccessPolicyTest {
    private val steven = CommunityAudienceMember(10, "Steven (data custodian)", "AA11")
    private val amber = CommunityAudienceMember(20, "Amber (authorised researcher)", "BB22")

    @Test
    fun hierarchyRulesNeverPropagateCommunityReadIntoPackages() {
        val rules = NextGisCommunityAccessPolicy.communityFolder(7)

        assertTrue(rules.all { !it.propagate })
        assertEquals(
            setOf("resource:read", "resource:create"),
            rules.mapTo(mutableSetOf()) { "${it.scope}:${it.permission}" }
        )
    }

    @Test
    fun anonymisedArtifactIsReadableByTheCommunityGroup() {
        val rules = NextGisCommunityAccessPolicy.communityReadableArtifact(7)

        assertEquals(setOf(7L), rules.mapTo(mutableSetOf()) { it.principalId })
        assertEquals(setOf("resource:read", "data:read"), rules.mapTo(mutableSetOf()) {
            "${it.scope}:${it.permission}"
        })
    }

    @Test
    fun encryptedPackageIsLimitedToPublisherAndMappedRecipients() {
        val audience = CommunityArtifactAudience.selectedRecipients(listOf(amber, amber))
        val rules = NextGisCommunityAccessPolicy.encryptedPackage(steven.userId, audience)

        assertEquals(setOf(steven.userId, amber.userId), rules.mapTo(mutableSetOf()) { it.principalId })
        assertEquals(4, rules.size)
        assertTrue(rules.all { !it.propagate })
    }

    @Test(expected = IllegalArgumentException::class)
    fun encryptedPackageRejectsAnUnmappedOpenPgpRecipient() {
        val audience = CommunityArtifactAudience.selectedRecipients(
            listOf(amber),
            unmappedFingerprints = setOf("CC33")
        )

        NextGisCommunityAccessPolicy.encryptedPackage(steven.userId, audience)
    }

    @Test
    fun communityAudienceIsNotValidForEncryptedPackageUpload() {
        assertFalse(CommunityArtifactAudience.community().isReadyForRestrictedUpload)
    }
}
