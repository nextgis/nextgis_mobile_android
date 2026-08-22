package com.nextgis.mobile.mapsafe.service

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Utility methods for computing file digests used by MapSafe integrity workflows.
 */
object HashUtils {

    fun sha256(file: File): String {
        return file.inputStream().use(::sha256)
    }

    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            digest.update(buffer, 0, read)
        }
        return digest.digest().toHex()
    }

    fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
