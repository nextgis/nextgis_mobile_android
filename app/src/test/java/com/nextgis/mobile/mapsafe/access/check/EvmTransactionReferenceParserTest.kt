package com.nextgis.mobile.mapsafe.access.check

import com.nextgis.mobile.mapsafe.blockchain.BlockchainNetworkPresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvmTransactionReferenceParserTest {
    private val hash = "0x" + "A1".repeat(32)
    private val normalizedHash = hash.lowercase()
    private val presets = BlockchainNetworkPresets.defaults()
    private val sepolia = presets.profiles.first { it.id == BlockchainNetworkPresets.SEPOLIA_ID }
    private val mainnet = presets.profiles.first { it.id == BlockchainNetworkPresets.MAINNET_ID }

    @Test
    fun acceptsAndNormalizesRawTransactionHashForActiveProfile() {
        val result = EvmTransactionReferenceParser.validate("  $hash  ", sepolia)

        assertTrue(result is EvmReferenceValidation.Valid)
        val reference = (result as EvmReferenceValidation.Valid).reference
        assertEquals(normalizedHash, reference.transactionHash)
        assertEquals(sepolia.id, reference.networkProfileId)
        assertEquals(11_155_111L, reference.chainId)
        assertEquals("https://sepolia.etherscan.io/tx/$normalizedHash", reference.canonicalUrl)
    }

    @Test
    fun acceptsExplorerUrlOnlyForSelectedProfile() {
        assertTrue(
            EvmTransactionReferenceParser.validate(
                "https://sepolia.etherscan.io/tx/$hash",
                sepolia
            ) is EvmReferenceValidation.Valid
        )
        assertTrue(
            EvmTransactionReferenceParser.validate(
                "https://etherscan.io/tx/$hash",
                sepolia
            ) is EvmReferenceValidation.Invalid
        )
        assertTrue(
            EvmTransactionReferenceParser.validate(
                "https://etherscan.io/tx/$hash",
                mainnet
            ) is EvmReferenceValidation.Valid
        )
    }

    @Test
    fun rejectsUnsafeUrlVariants() {
        val invalidValues = listOf(
            "http://sepolia.etherscan.io/tx/$hash",
            "https://user@sepolia.etherscan.io/tx/$hash",
            "https://sepolia.etherscan.io:443/tx/$hash",
            "https://sepolia.etherscan.io.evil.example/tx/$hash",
            "https://sepolia.etherscan.io/tx/$hash?source=share",
            "https://sepolia.etherscan.io/tx/$hash#eventlog"
        )

        invalidValues.forEach { value ->
            assertTrue(
                "Expected rejection for $value",
                EvmTransactionReferenceParser.validate(value, sepolia) is EvmReferenceValidation.Invalid
            )
        }
    }

    @Test
    fun rejectsMalformedHashesAndUnexpectedPaths() {
        val invalidValues = listOf(
            "0x1234",
            "0x" + "g".repeat(64),
            "https://sepolia.etherscan.io/address/$hash",
            "https://sepolia.etherscan.io/tx/0x1234",
            "https://sepolia.etherscan.io/tx/$hash/extra"
        )

        invalidValues.forEach { value ->
            assertTrue(
                "Expected rejection for $value",
                EvmTransactionReferenceParser.validate(value, sepolia) is EvmReferenceValidation.Invalid
            )
        }
    }
}
