package com.nextgis.mobile.mapsafe.crypto

import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Local file crypto helper for MapSafe.
 *
 * Uses AES/GCM/NoPadding with:
 * - 256-bit symmetric key
 * - 12-byte IV/nonce
 * - 128-bit authentication tag
 *
 * The encrypted output file format is:
 * [12-byte IV][ciphertext + authentication tag]
 */
object MapSafeCrypto {
    private const val ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_SIZE_BITS = 256
    private const val IV_SIZE_BYTES = 12
    private const val TAG_SIZE_BITS = 128

    data class CryptoResult(
        val outputFile: File,
        val keyBytes: ByteArray,
        val ivBytes: ByteArray,
        val algorithm: String = TRANSFORMATION
    )

    fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(ALGORITHM)
        generator.init(KEY_SIZE_BITS)
        return generator.generateKey()
    }

    fun generateKeyBytes(): ByteArray {
        return generateKey().encoded
    }

    fun encryptFile(
        inputFile: File,
        outputFile: File,
        keyBytes: ByteArray = generateKeyBytes()
    ): CryptoResult {
        require(inputFile.exists()) { "Input file does not exist: ${inputFile.absolutePath}" }

        val iv = ByteArray(IV_SIZE_BYTES)
        SecureRandom().nextBytes(iv)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        val key = SecretKeySpec(keyBytes, ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_SIZE_BITS, iv))

        val encryptedBytes = cipher.doFinal(inputFile.readBytes())

        outputFile.parentFile?.mkdirs()
        outputFile.outputStream().use { output ->
            output.write(iv)
            output.write(encryptedBytes)
        }

        return CryptoResult(
            outputFile = outputFile,
            keyBytes = keyBytes,
            ivBytes = iv
        )
    }

    fun decryptFile(
        encryptedFile: File,
        outputFile: File,
        keyBytes: ByteArray
    ): File {
        require(encryptedFile.exists()) { "Encrypted file does not exist: ${encryptedFile.absolutePath}" }

        val allBytes = encryptedFile.readBytes()
        require(allBytes.size > IV_SIZE_BYTES) { "Encrypted file is too small or invalid." }

        val iv = allBytes.copyOfRange(0, IV_SIZE_BYTES)
        val encryptedPayload = allBytes.copyOfRange(IV_SIZE_BYTES, allBytes.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        val key = SecretKeySpec(keyBytes, ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_SIZE_BITS, iv))

        val plainBytes = cipher.doFinal(encryptedPayload)

        outputFile.parentFile?.mkdirs()
        outputFile.writeBytes(plainBytes)

        return outputFile
    }

    fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Hex string length must be even." }
        return ByteArray(hex.length / 2) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
