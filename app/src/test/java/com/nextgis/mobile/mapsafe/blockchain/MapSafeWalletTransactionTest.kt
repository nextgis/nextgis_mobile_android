package com.nextgis.mobile.mapsafe.blockchain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapSafeWalletTransactionTest {
    private val profile = BlockchainNetworkPresets.defaults().activeProfile

    @Test
    fun `external wallet transaction contains only zero-value canonical mint call`() {
        val digest = "ab".repeat(32)
        val transaction = MapSafeWalletTransactionCodec.create(
            profile,
            "0x244EAbEf05ACF009746Ce91fE1712Daf3857e620",
            "C:\\Downloads\\suva-original.geojson.pgp",
            digest
        )

        assertEquals("eip155:11155111", transaction.chainId)
        assertEquals("suva-original.geojson.pgp_$digest", transaction.canonicalRecord)
        assertTrue(transaction.data.startsWith("0xfb37e883"))
        assertTrue(transaction.jsonRpcParams.contains("\"value\":\"0x0\""))
        assertTrue(transaction.jsonRpcParams.contains(profile.contractAddress.lowercase()))
        assertTrue(transaction.jsonRpcParams.contains(transaction.data))
        assertFalse(transaction.canonicalRecord.contains("Downloads", ignoreCase = true))
        val decoded = MapSafeMintCallCodec.decode(transaction.data) as MapSafeMintCallValidation.Valid
        assertEquals(transaction.canonicalRecord, decoded.recordValue)
    }
}
