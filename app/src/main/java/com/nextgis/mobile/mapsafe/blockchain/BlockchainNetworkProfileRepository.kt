package com.nextgis.mobile.mapsafe.blockchain

import android.content.Context
import android.util.AtomicFile
import java.io.File

/** Stores network metadata only. Wallet credentials and private keys are never accepted here. */
class BlockchainNetworkProfileRepository(context: Context) {
    private val profileFile = File(
        context.applicationContext.noBackupFilesDir,
        "mapsafe/blockchain/network-profiles.ks"
    )

    @Synchronized
    fun load(): BlockchainNetworkProfiles {
        if (!profileFile.isFile) return BlockchainNetworkPresets.defaults()
        val decrypted = BlockchainSettingsEnvelope.decrypt(profileFile.readBytes())
        return try {
            BlockchainNetworkPresets.fillMissingPublicRpc(
                BlockchainNetworkProfileCodec.decode(decrypted)
            )
        } catch (error: Exception) {
            throw BlockchainProfileStorageException(
                "The saved blockchain network settings are damaged or unsupported.",
                error
            )
        } finally {
            decrypted.fill(0)
        }
    }

    @Synchronized
    fun save(configuration: BlockchainNetworkProfiles) {
        require(configuration.profiles.isNotEmpty()) {
            "At least one blockchain network profile is required."
        }
        require(configuration.profiles.any { it.id == configuration.activeProfileId }) {
            "The active blockchain network profile is missing."
        }
        val plainText = BlockchainNetworkProfileCodec.encode(configuration)
        val protectedBytes = try {
            BlockchainSettingsEnvelope.encrypt(plainText)
        } finally {
            plainText.fill(0)
        }
        try {
            writeAtomic(protectedBytes)
        } finally {
            protectedBytes.fill(0)
        }
    }

    @Synchronized
    fun saveProfile(
        profile: BlockchainNetworkProfile,
        makeActive: Boolean = true
    ): BlockchainNetworkProfiles {
        val current = load()
        val existingIndex = current.profiles.indexOfFirst { it.id == profile.id }
        val updatedProfiles = current.profiles.toMutableList().apply {
            if (existingIndex >= 0) set(existingIndex, profile) else add(profile)
        }
        val updated = BlockchainNetworkProfiles(
            activeProfileId = if (makeActive) profile.id else current.activeProfileId,
            profiles = updatedProfiles
        )
        save(updated)
        return updated
    }

    private fun writeAtomic(bytes: ByteArray) {
        profileFile.parentFile?.mkdirs()
        val atomicFile = AtomicFile(profileFile)
        val stream = atomicFile.startWrite()
        try {
            stream.write(bytes)
            stream.fd.sync()
            atomicFile.finishWrite(stream)
        } catch (error: Exception) {
            atomicFile.failWrite(stream)
            throw BlockchainProfileStorageException(
                "The blockchain network settings could not be saved.",
                error
            )
        }
    }
}
