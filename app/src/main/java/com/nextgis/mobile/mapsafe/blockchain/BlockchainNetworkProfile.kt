package com.nextgis.mobile.mapsafe.blockchain

import java.net.URI

enum class BlockchainNetworkEnvironment(val displayName: String) {
    TESTNET("Test network"),
    PRODUCTION("Production network")
}

enum class MapSafeContractInterface(
    val displayName: String,
    val mintFunctionSignature: String,
    val mintFunctionSelector: String,
    val requiredRuntimeSelectors: Set<String>,
    val requiredErc165InterfaceId: String
) {
    LOCATION_NFT_V1(
        displayName = "MapSafe Location NFT v1 - mintNFT(string)",
        mintFunctionSignature = "mintNFT(string)",
        mintFunctionSelector = "0xfb37e883",
        requiredRuntimeSelectors = linkedSetOf(
            "0xfb37e883", // mintNFT(string)
            "0xb9e0db35"  // locations(uint256)
        ),
        requiredErc165InterfaceId = "0x80ac58cd" // ERC-721
    )
}

data class BlockchainNetworkProfile(
    val id: String,
    val displayName: String,
    val environment: BlockchainNetworkEnvironment,
    val chainId: Long,
    val rpcUrl: String,
    val explorerBaseUrl: String,
    val contractAddress: String,
    val contractInterface: MapSafeContractInterface = MapSafeContractInterface.LOCATION_NFT_V1
) {
    init {
        require(PROFILE_ID_PATTERN.matches(id)) { "Blockchain profile ID is invalid." }
    }

    val isProduction: Boolean
        get() = environment == BlockchainNetworkEnvironment.PRODUCTION

    fun normalized(): BlockchainNetworkProfile = copy(
        displayName = displayName.trim(),
        rpcUrl = rpcUrl.trim(),
        explorerBaseUrl = explorerBaseUrl.trim().trimEnd('/'),
        contractAddress = contractAddress.trim()
    )

    private companion object {
        val PROFILE_ID_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
    }
}

data class BlockchainNetworkProfiles(
    val activeProfileId: String,
    val profiles: List<BlockchainNetworkProfile>
) {
    init {
        require(profiles.isNotEmpty()) { "At least one blockchain profile is required." }
        require(profiles.map { it.id }.distinct().size == profiles.size) {
            "Blockchain profile IDs must be unique."
        }
        require(profiles.any { it.id == activeProfileId }) {
            "The active blockchain profile is missing."
        }
    }

    val activeProfile: BlockchainNetworkProfile
        get() = profiles.first { it.id == activeProfileId }
}

sealed class BlockchainProfileValidation {
    data class Valid(val profile: BlockchainNetworkProfile) : BlockchainProfileValidation()
    data class Invalid(val errors: List<String>) : BlockchainProfileValidation()
}

object BlockchainNetworkProfileValidator {
    private val contractAddressPattern = Regex("^0x[0-9a-fA-F]{40}$")

    fun validate(profile: BlockchainNetworkProfile): BlockchainProfileValidation {
        val normalized = profile.normalized()
        val errors = buildList {
            if (normalized.displayName.isBlank()) add("Network name is required.")
            if (normalized.displayName.length > 64) add("Network name must be 64 characters or fewer.")
            if (normalized.chainId <= 0L) add("Chain ID must be a positive whole number.")
            validateHttpsUrl(normalized.rpcUrl, "RPC endpoint", allowPathAndQuery = true)?.let(::add)
            validateHttpsUrl(normalized.explorerBaseUrl, "Explorer base URL", allowPathAndQuery = false)?.let(::add)
            if (!contractAddressPattern.matches(normalized.contractAddress)) {
                add("Contract address must be 0x followed by 40 hexadecimal characters.")
            }
        }
        return if (errors.isEmpty()) {
            BlockchainProfileValidation.Valid(normalized)
        } else {
            BlockchainProfileValidation.Invalid(errors)
        }
    }

    private fun validateHttpsUrl(
        value: String,
        label: String,
        allowPathAndQuery: Boolean
    ): String? {
        if (value.isBlank()) return "$label is required."
        val uri = runCatching { URI(value) }.getOrNull()
            ?: return "$label is malformed."
        if (uri.isOpaque || !uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) {
            return "$label must be a valid HTTPS URL."
        }
        if (uri.userInfo != null || uri.rawFragment != null) {
            return "$label must not contain credentials or a fragment."
        }
        if (uri.port < -1 || uri.port > 65_535) return "$label contains an invalid port."
        if (!allowPathAndQuery) {
            if (uri.rawQuery != null) return "$label must not contain query parameters."
            if (uri.rawPath.orEmpty().let { it.isNotEmpty() && it != "/" }) {
                return "$label must contain only the explorer origin, without a path."
            }
        }
        return null
    }
}

object BlockchainNetworkPresets {
    const val SEPOLIA_ID = "ethereum-sepolia"
    const val MAINNET_ID = "ethereum-mainnet"
    const val CUSTOM_ID = "custom-evm"
    const val SEPOLIA_PUBLIC_RPC_URL = "https://ethereum-sepolia-rpc.publicnode.com"
    const val QGIS_LEGACY_SENDER_ADDRESS = "0x244EAbEf05ACF009746Ce91fE1712Daf3857e620"
    const val QGIS_LEGACY_LOCATION_ADDRESS = "0x8dD5Ca941A9F839062b6589A2E3f701458B011A9"

    fun fillMissingPublicRpc(configuration: BlockchainNetworkProfiles): BlockchainNetworkProfiles {
        val updated = configuration.profiles.map { profile ->
            if (profile.id == SEPOLIA_ID && profile.rpcUrl.isBlank()) {
                profile.copy(rpcUrl = SEPOLIA_PUBLIC_RPC_URL)
            } else {
                profile
            }
        }
        return if (updated == configuration.profiles) {
            configuration
        } else {
            BlockchainNetworkProfiles(configuration.activeProfileId, updated)
        }
    }

    fun defaults(): BlockchainNetworkProfiles = BlockchainNetworkProfiles(
        activeProfileId = SEPOLIA_ID,
        profiles = listOf(
            BlockchainNetworkProfile(
                id = SEPOLIA_ID,
                displayName = "Ethereum Sepolia",
                environment = BlockchainNetworkEnvironment.TESTNET,
                chainId = 11_155_111L,
                rpcUrl = SEPOLIA_PUBLIC_RPC_URL,
                explorerBaseUrl = "https://sepolia.etherscan.io",
                contractAddress = QGIS_LEGACY_LOCATION_ADDRESS
            ),
            BlockchainNetworkProfile(
                id = MAINNET_ID,
                displayName = "Ethereum Mainnet",
                environment = BlockchainNetworkEnvironment.PRODUCTION,
                chainId = 1L,
                rpcUrl = "",
                explorerBaseUrl = "https://etherscan.io",
                contractAddress = ""
            ),
            BlockchainNetworkProfile(
                id = CUSTOM_ID,
                displayName = "Custom EVM network",
                environment = BlockchainNetworkEnvironment.TESTNET,
                chainId = 0L,
                rpcUrl = "",
                explorerBaseUrl = "",
                contractAddress = ""
            )
        )
    )
}
