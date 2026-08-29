package com.nextgis.mobile.mapsafe.crypto.openpgp

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Protects MapSafe's local, passphrase-protected OpenPGP secret-key blob at rest. */
internal object AndroidKeystoreEnvelope {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "mapsafe_openpgp_secret_store_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128
    private const val IV_BYTES = 12
    private val magic = "MSPGPKS1".toByteArray(Charsets.US_ASCII)

    fun encrypt(plainText: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(magic)
        val cipherText = cipher.doFinal(plainText)
        return magic + cipher.iv + cipherText
    }

    fun decrypt(envelope: ByteArray): ByteArray {
        if (envelope.size <= magic.size + IV_BYTES || !envelope.copyOfRange(0, magic.size).contentEquals(magic)) {
            throw OpenPgpException("The local MapSafe key store has an invalid format.")
        }
        val ivStart = magic.size
        val cipherStart = ivStart + IV_BYTES
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val key = existingKey()
            ?: throw OpenPgpException(
                "The Android Keystore protection key is missing. Restore the identity from its protected secret-key backup."
            )
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(TAG_BITS, envelope.copyOfRange(ivStart, cipherStart))
        )
        cipher.updateAAD(magic)
        return try {
            cipher.doFinal(envelope, cipherStart, envelope.size - cipherStart)
        } catch (error: Exception) {
            throw OpenPgpException(
                "The local OpenPGP identity cannot be unlocked on this installation.",
                error
            )
        }
    }

    private fun getOrCreateKey(): SecretKey {
        existingKey()?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
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
    }

    private fun existingKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        return keyStore.getKey(KEY_ALIAS, null) as? SecretKey
    }
}
