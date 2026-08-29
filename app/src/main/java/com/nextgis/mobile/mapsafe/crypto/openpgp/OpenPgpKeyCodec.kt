package com.nextgis.mobile.mapsafe.crypto.openpgp

import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection
import org.bouncycastle.openpgp.PGPSecretKey
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.PGPSecretKeyRingCollection
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Date

object OpenPgpKeyCodec {
    private val fingerprintCalculator = BcKeyFingerprintCalculator()

    fun readPublicKeyRings(input: InputStream): List<PGPPublicKeyRing> {
        return try {
            val decoded = PGPUtil.getDecoderStream(input)
            PGPPublicKeyRingCollection(decoded, fingerprintCalculator).keyRings.asSequence().toList()
        } catch (error: Exception) {
            throw OpenPgpException("The selected file does not contain a valid OpenPGP public key.", error)
        }
    }

    fun readSecretKeyRings(input: InputStream): List<PGPSecretKeyRing> {
        return try {
            val decoded = PGPUtil.getDecoderStream(input)
            PGPSecretKeyRingCollection(decoded, fingerprintCalculator).keyRings.asSequence().toList()
        } catch (error: Exception) {
            throw OpenPgpException("The selected file does not contain a valid OpenPGP secret key.", error)
        }
    }

    fun encodePublicKeyRing(keyRing: PGPPublicKeyRing, armored: Boolean = true): ByteArray {
        return encode(armored) { keyRing.encode(it) }
    }

    fun encodeSecretKeyRing(keyRing: PGPSecretKeyRing, armored: Boolean = true): ByteArray {
        return encode(armored) { keyRing.encode(it) }
    }

    fun decodePublicKeyRing(encoded: ByteArray): PGPPublicKeyRing {
        return readPublicKeyRings(ByteArrayInputStream(encoded)).singleOrNull()
            ?: throw OpenPgpException("Expected exactly one OpenPGP public key ring.")
    }

    fun decodeSecretKeyRing(encoded: ByteArray): PGPSecretKeyRing {
        return readSecretKeyRings(ByteArrayInputStream(encoded)).singleOrNull()
            ?: throw OpenPgpException("Expected exactly one OpenPGP secret key ring.")
    }

    fun keyInfo(keyRing: PGPPublicKeyRing, hasSecretKey: Boolean = false): OpenPgpKeyInfo {
        val primary = keyRing.publicKey
        return OpenPgpKeyInfo(
            fingerprint = fingerprint(primary.fingerprint),
            keyId = primary.keyID,
            userIds = primary.userIDs.asSequence().toList(),
            canEncrypt = keyRing.publicKeys.asSequence().any { it.isUsableEncryptionKey() },
            canSign = keyRing.publicKeys.asSequence().any { it.isSigningCapable() },
            hasSecretKey = hasSecretKey
        )
    }

    fun keyInfo(keyRing: PGPSecretKeyRing): OpenPgpKeyInfo {
        val primary = keyRing.publicKey
        return OpenPgpKeyInfo(
            fingerprint = fingerprint(primary.fingerprint),
            keyId = primary.keyID,
            userIds = primary.userIDs.asSequence().toList(),
            canEncrypt = keyRing.secretKeys.asSequence().any { it.publicKey.isUsableEncryptionKey() },
            canSign = keyRing.secretKeys.asSequence().any { it.isSigningKey },
            hasSecretKey = true
        )
    }

    fun findEncryptionKey(keyRing: PGPPublicKeyRing, now: Date = Date()): PGPPublicKey? {
        return keyRing.publicKeys.asSequence().firstOrNull { key ->
            key.isUsableEncryptionKey() && !key.isRevoked &&
                (key.validSeconds == 0L || key.creationTime.time + key.validSeconds * 1000L > now.time)
        }
    }

    fun findSigningSecretKey(keyRing: PGPSecretKeyRing): PGPSecretKey? {
        return keyRing.secretKeys.asSequence().firstOrNull { it.isSigningKey && !it.publicKey.isRevoked }
    }

    fun findPublicKey(keyRings: Collection<PGPPublicKeyRing>, keyId: Long): PGPPublicKey? {
        return keyRings.asSequence().mapNotNull { it.getPublicKey(keyId) }.firstOrNull()
    }

    fun fingerprint(bytes: ByteArray): String {
        return bytes.joinToString("") { byte -> "%02X".format(byte.toInt() and 0xff) }
    }

    private fun PGPPublicKey.isUsableEncryptionKey(): Boolean {
        return isEncryptionKey
    }

    private fun PGPPublicKey.isSigningCapable(): Boolean {
        return algorithm == PGPPublicKey.RSA_SIGN ||
            algorithm == PGPPublicKey.RSA_GENERAL ||
            algorithm == PGPPublicKey.DSA ||
            algorithm == PGPPublicKey.ECDSA ||
            algorithm == PGPPublicKey.EDDSA_LEGACY ||
            algorithm == PGPPublicKey.Ed25519
    }

    private inline fun encode(armored: Boolean, writer: (OutputStream) -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        if (armored) {
            ArmoredOutputStream(bytes).use(writer)
        } else {
            writer(bytes)
        }
        return bytes.toByteArray()
    }
}
