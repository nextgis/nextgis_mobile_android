package com.nextgis.mobile.mapsafe.access.check

import java.net.URI

/** Pure gate used after an encrypted document has a canonical local SHA-256. */
internal object VerifiedDecryptHandoffGate {
    fun isReady(
        sourceUri: String?,
        localSha256: String?
    ): Boolean {
        val contentUri = runCatching { URI(sourceUri.orEmpty()) }.getOrNull()
        return contentUri?.scheme.equals("content", ignoreCase = true) &&
            localSha256?.matches(SHA_256_PATTERN) == true
    }

    private val SHA_256_PATTERN = Regex("[0-9a-f]{64}")
}
