package com.nextgis.mobile.mapsafe.safeguard.encryption

import android.content.Context
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpKeyInfo
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpKeyRepository

/**
 * Entry point for MapSafe's app-private OpenPGP key repository.
 * Private-key material is never placed in this collaborator model.
 */
object KeyManager {

    data class Collaborator(
        val fingerprint: String,
        val displayName: String,
        val userIds: List<String>,
        val canEncrypt: Boolean,
        val isLocalIdentity: Boolean
    )

    fun repository(context: Context): OpenPgpKeyRepository {
        return OpenPgpKeyRepository(context.applicationContext)
    }

    fun collaborators(context: Context): List<Collaborator> {
        return repository(context).listKeyInfo().map { it.toCollaborator() }
    }

    private fun OpenPgpKeyInfo.toCollaborator(): Collaborator {
        return Collaborator(
            fingerprint = fingerprint,
            displayName = displayName,
            userIds = userIds,
            canEncrypt = canEncrypt,
            isLocalIdentity = hasSecretKey
        )
    }
}
