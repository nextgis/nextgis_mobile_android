package com.nextgis.mobile.mapsafe.blockchain

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class BlockchainProfileStorageException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/** Encrypts locally stored blockchain network metadata with a non-exportable Android key. */
internal object BlockchainSettingsEnvelope {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "mapsafe_blockchain_profile_store_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128
    private const val IV_BYTES = 12
    private val magic = "MSBCFG1".toByteArray(Charsets.US_ASCII)

    fun encrypt(plainText: ByteArray): ByteArray {
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            cipher.updateAAD(magic)
            magic + cipher.iv + cipher.doFinal(plainText)
        } catch (error: BlockchainProfileStorageException) {
            throw error
        } catch (error: Exception) {
            throw BlockchainProfileStorageException(
                "The blockchain network settings could not be protected on this device.",
                error
            )
        }
    }

    fun decrypt(envelope: ByteArray): ByteArray {
        if (envelope.size <= magic.size + IV_BYTES ||
            !envelope.copyOfRange(0, magic.size).contentEquals(magic)
        ) {
            throw BlockchainProfileStorageException(
                "The local blockchain network settings have an invalid format."
            )
        }

        val ivStart = magic.size
        val cipherStart = ivStart + IV_BYTES
        val key = existingKey()
            ?: throw BlockchainProfileStorageException(
                "The device protection key for the blockchain network settings is missing."
            )
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(TAG_BITS, envelope.copyOfRange(ivStart, cipherStart))
            )
            cipher.updateAAD(magic)
            cipher.doFinal(envelope, cipherStart, envelope.size - cipherStart)
        } catch (error: Exception) {
            throw BlockchainProfileStorageException(
                "The blockchain network settings could not be unlocked on this installation.",
                error
            )
        }
    }

    private fun getOrCreateKey(): SecretKey {
        existingKey()?.let { return it }
        return try {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
                init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setRandomizedEncryptionRequired(true)
                        .build()
                )
                generateKey()
            }
        } catch (error: Exception) {
            throw BlockchainProfileStorageException(
                "The device protection key for blockchain settings could not be created.",
                error
            )
        }
    }

    private fun existingKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        return keyStore.getKey(KEY_ALIAS, null) as? SecretKey
    }
}
