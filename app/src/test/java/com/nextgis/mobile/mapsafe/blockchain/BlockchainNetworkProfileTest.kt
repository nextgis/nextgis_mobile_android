package com.nextgis.mobile.mapsafe.blockchain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockchainNetworkProfileTest {
    @Test
    fun defaultsSelectSepoliaWithPublicReadOnlyRpc() {
        val defaults = BlockchainNetworkPresets.defaults()

        assertEquals(BlockchainNetworkPresets.SEPOLIA_ID, defaults.activeProfile.id)
        assertEquals(11_155_111L, defaults.activeProfile.chainId)
        assertEquals(
            BlockchainNetworkPresets.SEPOLIA_PUBLIC_RPC_URL,
            defaults.activeProfile.rpcUrl
        )
    }

    @Test
    fun fillsOnlyMissingLegacySepoliaRpcOnUpgrade() {
        val defaults = BlockchainNetworkPresets.defaults()
        val legacy = BlockchainNetworkProfiles(
            activeProfileId = BlockchainNetworkPresets.SEPOLIA_ID,
            profiles = defaults.profiles.map { profile ->
                when (profile.id) {
                    BlockchainNetworkPresets.SEPOLIA_ID -> profile.copy(rpcUrl = "")
                    BlockchainNetworkPresets.CUSTOM_ID -> profile.copy(rpcUrl = "")
                    else -> profile
                }
            }
        )

        val upgraded = BlockchainNetworkPresets.fillMissingPublicRpc(legacy)

        assertEquals(
            BlockchainNetworkPresets.SEPOLIA_PUBLIC_RPC_URL,
            upgraded.profiles.first { it.id == BlockchainNetworkPresets.SEPOLIA_ID }.rpcUrl
        )
        assertTrue(
            upgraded.profiles.first { it.id == BlockchainNetworkPresets.CUSTOM_ID }.rpcUrl.isBlank()
        )
    }

    @Test
    fun validatesAndNormalizesCompleteHttpsProfile() {
        val candidate = BlockchainNetworkPresets.defaults().activeProfile.copy(
            displayName = "  Research Sepolia  ",
            rpcUrl = "  https://rpc.example.org/project/123  ",
            explorerBaseUrl = "https://sepolia.etherscan.io/"
        )

        val result = BlockchainNetworkProfileValidator.validate(candidate)

        assertTrue(result is BlockchainProfileValidation.Valid)
        val profile = (result as BlockchainProfileValidation.Valid).profile
        assertEquals("Research Sepolia", profile.displayName)
        assertEquals("https://rpc.example.org/project/123", profile.rpcUrl)
        assertEquals("https://sepolia.etherscan.io", profile.explorerBaseUrl)
    }

    @Test
    fun rejectsUnsafeOrIncompleteNetworkDetails() {
        val candidate = BlockchainNetworkPresets.defaults().activeProfile.copy(
            chainId = 0L,
            rpcUrl = "http://rpc.example.org",
            explorerBaseUrl = "https://etherscan.io/tx/",
            contractAddress = "0x1234"
        )

        val result = BlockchainNetworkProfileValidator.validate(candidate)

        assertTrue(result is BlockchainProfileValidation.Invalid)
        val errors = (result as BlockchainProfileValidation.Invalid).errors
        assertTrue(errors.any { it.contains("Chain ID") })
        assertTrue(errors.any { it.contains("RPC endpoint") })
        assertTrue(errors.any { it.contains("explorer origin") })
        assertTrue(errors.any { it.contains("Contract address") })
    }

    @Test
    fun codecRoundTripsAllProfilesAndActiveSelection() {
        val defaults = BlockchainNetworkPresets.defaults()
        val configuration = defaults.copy(activeProfileId = BlockchainNetworkPresets.MAINNET_ID)

        val decoded = BlockchainNetworkProfileCodec.decode(
            BlockchainNetworkProfileCodec.encode(configuration)
        )

        assertEquals(configuration, decoded)
        assertEquals("Ethereum Mainnet", decoded.activeProfile.displayName)
    }
}
