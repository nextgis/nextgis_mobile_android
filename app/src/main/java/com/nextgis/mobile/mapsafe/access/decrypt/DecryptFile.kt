package com.nextgis.mobile.mapsafe.access.decrypt

import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpDecryptionResult
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpEngine
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPSecretKeyRing
import java.io.InputStream
import java.io.OutputStream

/**
 * Streaming OpenPGP decryption facade used by Android document workflows.
 */
object DecryptFile {
    const val VERSION = "1.0"

    fun decrypt(
        input: InputStream,
        output: OutputStream,
        secretKeyRings: Collection<PGPSecretKeyRing>,
        passphrase: CharArray,
        verificationKeyRings: Collection<PGPPublicKeyRing> = emptyList()
    ): OpenPgpDecryptionResult {
        return OpenPgpEngine.decrypt(
            input = input,
            output = output,
            secretKeyRings = secretKeyRings,
            passphrase = passphrase,
            verificationKeyRings = verificationKeyRings
        )
    }
}
