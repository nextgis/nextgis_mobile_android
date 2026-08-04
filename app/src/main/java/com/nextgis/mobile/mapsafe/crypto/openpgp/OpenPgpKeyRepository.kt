package com.nextgis.mobile.mapsafe.crypto.openpgp

import android.content.Context
import android.util.AtomicFile
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyDecryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * App-private OpenPGP key repository.
 *
 * Secret keys remain passphrase-protected OpenPGP keyrings and the local copy is
 * additionally encrypted by a non-exportable Android Keystore AES key.
 */
class OpenPgpKeyRepository(context: Context) {
    // Keystore keys are not restored with Android Auto Backup, so the wrapped
    // keyring must also be excluded. Users restore through an explicit export.
    private val root = File(context.applicationContext.noBackupFilesDir, "mapsafe/openpgp")
    private val recipientDirectory = File(root, "recipients")
    private val localPublicFile = File(root, "local-public.asc")
    private val localSecretFile = File(root, "local-secret.pgp.ks")

    init {
        recipientDirectory.mkdirs()
    }

    @Synchronized
    fun hasLocalIdentity(): Boolean = localPublicFile.isFile && localSecretFile.isFile

    @Synchronized
    fun saveLocalIdentity(material: OpenPgpKeyMaterial) {
        val protectedSecret = AndroidKeystoreEnvelope.encrypt(
            OpenPgpKeyCodec.encodeSecretKeyRing(material.secretKeyRing, armored = false)
        )
        writeAtomic(localSecretFile, protectedSecret)
        writeAtomic(localPublicFile, OpenPgpKeyCodec.encodePublicKeyRing(material.publicKeyRing))
    }

    @Synchronized
    fun importLocalIdentity(input: InputStream, passphrase: CharArray): OpenPgpKeyInfo {
        val secretRing = OpenPgpKeyCodec.readSecretKeyRings(input).singleOrNull()
            ?: throw OpenPgpException("Select a file containing exactly one OpenPGP secret keyring.")
        verifyPassphrase(secretRing, passphrase)
        val publicRing = secretRing.toCertificate()
        val material = OpenPgpKeyMaterial(
            publicKeyRing = publicRing,
            secretKeyRing = secretRing,
            info = OpenPgpKeyCodec.keyInfo(publicRing, hasSecretKey = true)
        )
        saveLocalIdentity(material)
        return material.info
    }

    @Synchronized
    fun loadLocalPublicKeyRing(): PGPPublicKeyRing? {
        if (!localPublicFile.isFile) return null
        return localPublicFile.inputStream().use { OpenPgpKeyCodec.readPublicKeyRings(it).singleOrNull() }
    }

    @Synchronized
    fun loadLocalSecretKeyRing(): PGPSecretKeyRing? {
        if (!localSecretFile.isFile) return null
        val decrypted = AndroidKeystoreEnvelope.decrypt(localSecretFile.readBytes())
        return try {
            OpenPgpKeyCodec.decodeSecretKeyRing(decrypted)
        } finally {
            decrypted.fill(0)
        }
    }

    @Synchronized
    fun localIdentityInfo(): OpenPgpKeyInfo? {
        return loadLocalPublicKeyRing()?.let { OpenPgpKeyCodec.keyInfo(it, hasSecretKey = true) }
    }

    @Synchronized
    fun importPublicKeys(input: InputStream): List<OpenPgpKeyInfo> {
        val keyRings = OpenPgpKeyCodec.readPublicKeyRings(input)
        if (keyRings.isEmpty()) throw OpenPgpException("No OpenPGP public keys were found.")
        return keyRings.map { ring ->
            val info = OpenPgpKeyCodec.keyInfo(ring)
            writeAtomic(
                File(recipientDirectory, "${info.fingerprint}.asc"),
                OpenPgpKeyCodec.encodePublicKeyRing(ring)
            )
            info
        }
    }

    @Synchronized
    fun listPublicKeyRings(): List<PGPPublicKeyRing> {
        val rings = mutableListOf<PGPPublicKeyRing>()
        loadLocalPublicKeyRing()?.let(rings::add)
        recipientDirectory.listFiles { file -> file.isFile && file.extension.equals("asc", true) }
            .orEmpty()
            .sortedBy { it.name }
            .forEach { file ->
                runCatching {
                    file.inputStream().use { OpenPgpKeyCodec.readPublicKeyRings(it) }
                }.getOrNull()?.let(rings::addAll)
            }
        return rings.distinctBy { OpenPgpKeyCodec.fingerprint(it.publicKey.fingerprint) }
    }

    @Synchronized
    fun listKeyInfo(): List<OpenPgpKeyInfo> {
        val localFingerprint = loadLocalPublicKeyRing()?.publicKey?.fingerprint
            ?.let(OpenPgpKeyCodec::fingerprint)
        return listPublicKeyRings().map { ring ->
            val fingerprint = OpenPgpKeyCodec.fingerprint(ring.publicKey.fingerprint)
            OpenPgpKeyCodec.keyInfo(ring, hasSecretKey = fingerprint == localFingerprint)
        }
    }

    @Synchronized
    fun exportLocalPublicKey(output: OutputStream) {
        val ring = loadLocalPublicKeyRing()
            ?: throw OpenPgpException("Create or import a local OpenPGP identity first.")
        output.write(OpenPgpKeyCodec.encodePublicKeyRing(ring))
    }

    @Synchronized
    fun exportLocalSecretKey(output: OutputStream) {
        val ring = loadLocalSecretKeyRing()
            ?: throw OpenPgpException("Create or import a local OpenPGP identity first.")
        output.write(OpenPgpKeyCodec.encodeSecretKeyRing(ring))
    }

    private fun verifyPassphrase(secretRing: PGPSecretKeyRing, passphrase: CharArray) {
        val secretKey = OpenPgpKeyCodec.findSigningSecretKey(secretRing)
            ?: throw OpenPgpException("The imported keyring has no signing-capable primary key.")
        try {
            val decryptor = BcPBESecretKeyDecryptorBuilder(BcPGPDigestCalculatorProvider())
                .build(passphrase)
            secretKey.extractPrivateKey(decryptor)
                ?: throw OpenPgpException("The imported private key is unavailable.")
        } catch (error: OpenPgpException) {
            throw error
        } catch (error: Exception) {
            throw OpenPgpException("The secret key could not be unlocked. Check the passphrase.", error)
        }
    }

    private fun writeAtomic(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val atomicFile = AtomicFile(file)
        val stream = atomicFile.startWrite()
        try {
            stream.write(bytes)
            stream.fd.sync()
            atomicFile.finishWrite(stream)
        } catch (error: Exception) {
            atomicFile.failWrite(stream)
            throw OpenPgpException("Could not save the OpenPGP key store.", error)
        }
    }
}
