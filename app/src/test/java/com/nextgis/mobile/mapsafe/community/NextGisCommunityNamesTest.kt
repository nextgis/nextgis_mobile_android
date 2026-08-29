package com.nextgis.mobile.mapsafe.community

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NextGisCommunityNamesTest {
    @Test
    fun hierarchyKeysAreStableAndScopedToAuthenticationGroup() {
        assertEquals("mapsafe_root", NextGisCommunityNames.rootKey)
        assertEquals("mapsafe_community_g42", NextGisCommunityNames.communityKey(42))
        assertEquals("mapsafe_public_keys_g42", NextGisCommunityNames.publicKeysKey(42))
        assertEquals("mapsafe_layers_g42", NextGisCommunityNames.layersKey(42))
        assertEquals("mapsafe_packages_g42", NextGisCommunityNames.packagesKey(42))
        assertEquals(
            "/api/resource/17/feature/23/attachment/31/download",
            NextGisCommunityNames.attachmentDownloadPath(17, 23, 31)
        )
    }

    @Test
    fun artifactKeysAreAsciiAndUniqueAcrossRecords() {
        val first = NextGisCommunityNames.artifactKey(
            42,
            7,
            "11111111-2222-3333-4444-555555555555"
        )
        val second = NextGisCommunityNames.artifactKey(
            42,
            7,
            "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        )

        assertEquals("mapsafe_artifact_g42_u7_1111111122223333", first)
        assertNotEquals(first, second)
        assertTrue(first.matches(Regex("^[a-z0-9_]+$")))
    }

    @Test
    fun blockchainReferenceIsRecordedOnlyWithHashAndExplorerLocation() {
        assertFalse(CommunityBlockchainReference(networkName = "Sepolia", chainId = 11_155_111).isRecorded)
        assertFalse(CommunityBlockchainReference(transactionHash = "0x123").isRecorded)
        assertTrue(
            CommunityBlockchainReference(
                transactionHash = "0x123",
                explorerUrl = "https://sepolia.etherscan.io/tx/0x123"
            ).isRecorded
        )
    }
}
