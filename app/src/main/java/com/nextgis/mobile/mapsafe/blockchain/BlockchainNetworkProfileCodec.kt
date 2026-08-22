package com.nextgis.mobile.mapsafe.blockchain

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Properties

internal object BlockchainNetworkProfileCodec {
    private const val VERSION = "1"

    fun encode(configuration: BlockchainNetworkProfiles): ByteArray {
        val properties = Properties().apply {
            setProperty("version", VERSION)
            setProperty("activeProfileId", configuration.activeProfileId)
            setProperty("profileCount", configuration.profiles.size.toString())
            configuration.profiles.forEachIndexed { index, profile ->
                val prefix = "profile.$index."
                setProperty(prefix + "id", profile.id)
                setProperty(prefix + "displayName", profile.displayName)
                setProperty(prefix + "environment", profile.environment.name)
                setProperty(prefix + "chainId", profile.chainId.toString())
                setProperty(prefix + "rpcUrl", profile.rpcUrl)
                setProperty(prefix + "explorerBaseUrl", profile.explorerBaseUrl)
                setProperty(prefix + "contractAddress", profile.contractAddress)
                setProperty(prefix + "contractInterface", profile.contractInterface.name)
            }
        }
        return ByteArrayOutputStream().use { output ->
            properties.store(output, "MapSafe encrypted blockchain profiles")
            output.toByteArray()
        }
    }

    fun decode(bytes: ByteArray): BlockchainNetworkProfiles {
        val properties = Properties().apply { load(ByteArrayInputStream(bytes)) }
        require(properties.getProperty("version") == VERSION) {
            "Unsupported blockchain-profile format."
        }
        val count = properties.getProperty("profileCount")?.toIntOrNull()
            ?: error("Blockchain-profile count is missing.")
        require(count in 1..32) { "Blockchain-profile count is invalid." }
        val profiles = (0 until count).map { index ->
            val prefix = "profile.$index."
            BlockchainNetworkProfile(
                id = properties.required(prefix + "id"),
                displayName = properties.required(prefix + "displayName"),
                environment = BlockchainNetworkEnvironment.valueOf(properties.required(prefix + "environment")),
                chainId = properties.required(prefix + "chainId").toLong(),
                rpcUrl = properties.required(prefix + "rpcUrl"),
                explorerBaseUrl = properties.required(prefix + "explorerBaseUrl"),
                contractAddress = properties.required(prefix + "contractAddress"),
                contractInterface = MapSafeContractInterface.valueOf(
                    properties.required(prefix + "contractInterface")
                )
            )
        }
        val activeId = properties.required("activeProfileId")
        require(profiles.any { it.id == activeId }) { "The active blockchain profile is missing." }
        return BlockchainNetworkProfiles(activeId, profiles)
    }

    private fun Properties.required(key: String): String =
        getProperty(key) ?: error("Blockchain-profile field '$key' is missing.")
}
