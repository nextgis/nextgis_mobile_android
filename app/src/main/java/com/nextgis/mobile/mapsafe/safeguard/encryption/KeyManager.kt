package com.nextgis.mobile.mapsafe.safeguard.encryption

import java.util.UUID

/**
 * Placeholder key manager.
 *
 * Future versions will manage:
 * - RSA key pairs
 * - collaborator public keys
 * - local secure storage
 */
object KeyManager {

    data class Collaborator(
        val id: String,
        val displayName: String,
        val publicKey: String
    )

    fun generatePlaceholderKeyId(): String {
        return UUID.randomUUID().toString()
    }
}
