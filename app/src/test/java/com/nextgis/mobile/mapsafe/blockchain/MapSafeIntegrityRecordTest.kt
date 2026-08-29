package com.nextgis.mobile.mapsafe.blockchain

import org.bouncycastle.crypto.digests.KeccakDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapSafeIntegrityRecordTest {
    private val upperHash = "A1".repeat(32)
    private val normalizedHash = upperHash.lowercase()

    @Test
    fun canonicalRecordIsVersionedLowercaseAndOmitsFileName() {
        val encoded = MapSafeIntegrityRecordCodec.encodeSha256(upperHash)

        assertEquals("mapsafe:v1:sha256:$normalizedHash", encoded)
        assertTrue(!encoded.contains("dataset"))
        val parsed = MapSafeIntegrityRecordCodec.parse(encoded)
            as MapSafeIntegrityRecordValidation.Valid
        assertEquals(normalizedHash, parsed.record.sha256)
        assertEquals(MapSafeIntegrityRecordFormat.MAPSAFE_V1, parsed.record.format)
        assertNull(parsed.record.legacyFileName)
    }

    @Test
    fun readsLegacyQgisFilenameHashUsingFinalUnderscore() {
        val parsed = MapSafeIntegrityRecordCodec.parse(
            "masked_sensitive_sites.zip_$upperHash"
        ) as MapSafeIntegrityRecordValidation.Valid

        assertEquals(normalizedHash, parsed.record.sha256)
        assertEquals(
            MapSafeIntegrityRecordFormat.LEGACY_QGIS_FILENAME_HASH,
            parsed.record.format
        )
        assertEquals("masked_sensitive_sites.zip", parsed.record.legacyFileName)
    }

    @Test
    fun rejectsMalformedOrUnknownRecords() {
        val invalidValues = listOf(
            "mapsafe:v1:sha256:1234",
            "mapsafe:v2:sha256:$normalizedHash",
            "filename_1234",
            normalizedHash
        )

        invalidValues.forEach { value ->
            assertTrue(
                "Expected rejection for $value",
                MapSafeIntegrityRecordCodec.parse(value) is MapSafeIntegrityRecordValidation.Invalid
            )
        }
    }

    @Test
    fun qgisAbiSelectorMatchesMintNftStringSignature() {
        val signature = MapSafeContractInterface.LOCATION_NFT_V1.mintFunctionSignature
            .toByteArray(Charsets.US_ASCII)
        val digest = ByteArray(32)
        KeccakDigest(256).apply {
            update(signature, 0, signature.size)
            doFinal(digest, 0)
        }
        val calculatedSelector = "0x" + digest.take(4).joinToString("") { "%02x".format(it) }

        assertEquals("0xfb37e883", calculatedSelector)
        assertEquals(
            calculatedSelector,
            MapSafeContractInterface.LOCATION_NFT_V1.mintFunctionSelector
        )
    }

    @Test
    fun detectsBothRequiredSelectorsInRuntimeBytecode() {
        val code = "0x63fb37e883600063b9e0db356000"

        assertTrue(
            MapSafeContractAbiValidator.missingRuntimeSelectors(
                MapSafeContractInterface.LOCATION_NFT_V1,
                code
            ).isEmpty()
        )
    }
}
