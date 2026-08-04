package com.nextgis.mobile.mapsafe.safeguard.encryption

import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpEncryptionResult
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpEngine
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPSecretKeyRing
import java.io.InputStream
import java.io.OutputStream

/**
 * Streaming OpenPGP encryption facade used by Android document workflows.
 */
object EncryptFile {
    const val VERSION = "1.0"

    fun encrypt(
        input: InputStream,
        output: OutputStream,
        originalFileName: String,
        recipients: Collection<PGPPublicKeyRing>,
        signingKeyRing: PGPSecretKeyRing? = null,
        signingPassphrase: CharArray? = null
    ): OpenPgpEncryptionResult {
        return OpenPgpEngine.encrypt(
            input = input,
            output = output,
            originalFileName = originalFileName,
            recipients = recipients,
            signingKeyRing = signingKeyRing,
            signingPassphrase = signingPassphrase
        )
    }
}
