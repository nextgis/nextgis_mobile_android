package com.nextgis.mobile.mapsafe.crypto.openpgp

import org.bouncycastle.bcpg.AEADAlgorithmTags
import org.bouncycastle.bcpg.SymmetricEncIntegrityPacket
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.bcpg.sig.PreferredAEADCiphersuites
import org.bouncycastle.openpgp.PGPEncryptedDataList
import org.bouncycastle.openpgp.PGPObjectFactory
import org.bouncycastle.openpgp.PGPPublicKeyEncryptedData
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class OpenPgpEngineTest {
    @Test
    fun encryptedPackageCanBeDecryptedByEveryRecipientAndSignatureIsVerified() {
        val plainText = ByteArray(1024 * 1024 + 137) { index -> (index * 31).toByte() }

        val encrypted = ByteArrayOutputStream()
        val encryptedResult = OpenPgpEngine.encrypt(
            input = ByteArrayInputStream(plainText),
            output = encrypted,
            originalFileName = "sample-points.geojson",
            recipients = listOf(alice.publicKeyRing, bob.publicKeyRing),
            signingKeyRing = alice.secretKeyRing,
            signingPassphrase = ALICE_PASSPHRASE.copyOf()
        )

        assertEquals(2, encryptedResult.recipientFingerprints.size)
        assertEquals(alice.info.fingerprint, encryptedResult.signerFingerprint)
        assertEquals(OpenPgpContentProtection.AES_256_GCM, encryptedResult.contentProtection)

        val encryptedData = encryptedData(encrypted.toByteArray())
        val packet = encryptedData.encData as SymmetricEncIntegrityPacket
        assertTrue(encryptedData.isAEAD)
        assertEquals(SymmetricKeyAlgorithmTags.AES_256, packet.cipherAlgorithm)
        assertEquals(AEADAlgorithmTags.GCM, packet.aeadAlgorithm)
        assertEquals(SymmetricEncIntegrityPacket.VERSION_2, packet.version)

        listOf(alice to ALICE_PASSPHRASE, bob to BOB_PASSPHRASE).forEach { (recipient, passphrase) ->
            val decrypted = ByteArrayOutputStream()
            val result = OpenPgpEngine.decrypt(
                input = ByteArrayInputStream(encrypted.toByteArray()),
                output = decrypted,
                secretKeyRings = listOf(recipient.secretKeyRing),
                passphrase = passphrase.copyOf(),
                verificationKeyRings = listOf(alice.publicKeyRing, bob.publicKeyRing)
            )

            assertArrayEquals(plainText, decrypted.toByteArray())
            assertEquals("sample-points.geojson", result.originalFileName)
            assertTrue(result.integrityProtected)
            assertEquals(OpenPgpContentProtection.AES_256_GCM, result.contentProtection)
            assertEquals(OpenPgpSignatureStatus.VALID, result.signatureStatus)
            assertEquals(alice.info.fingerprint, result.signerFingerprint)
        }
    }

    @Test
    fun nonRecipientCannotDecryptPackage() {
        val encrypted = ByteArrayOutputStream()
        OpenPgpEngine.encrypt(
            input = ByteArrayInputStream("private map data".toByteArray()),
            output = encrypted,
            originalFileName = "private.txt",
            recipients = listOf(alice.publicKeyRing, bob.publicKeyRing)
        )

        val error = runCatching {
            OpenPgpEngine.decrypt(
                input = ByteArrayInputStream(encrypted.toByteArray()),
                output = ByteArrayOutputStream(),
                secretKeyRings = listOf(charlie.secretKeyRing),
                passphrase = CHARLIE_PASSPHRASE.copyOf()
            )
        }.exceptionOrNull()

        assertTrue(error is OpenPgpException)
        assertTrue(error?.message?.contains("not encrypted for a key", ignoreCase = true) == true)
    }

    @Test
    fun unknownSignerIsReportedSeparatelyFromEncryptionIntegrity() {
        val plainText = "signed field package".toByteArray()
        val encrypted = ByteArrayOutputStream()
        OpenPgpEngine.encrypt(
            input = ByteArrayInputStream(plainText),
            output = encrypted,
            originalFileName = "field.txt",
            recipients = listOf(bob.publicKeyRing),
            signingKeyRing = alice.secretKeyRing,
            signingPassphrase = ALICE_PASSPHRASE.copyOf()
        )

        val decrypted = ByteArrayOutputStream()
        val result = OpenPgpEngine.decrypt(
            input = ByteArrayInputStream(encrypted.toByteArray()),
            output = decrypted,
            secretKeyRings = listOf(bob.secretKeyRing),
            passphrase = BOB_PASSPHRASE.copyOf(),
            verificationKeyRings = listOf(bob.publicKeyRing)
        )

        assertArrayEquals(plainText, decrypted.toByteArray())
        assertEquals(OpenPgpSignatureStatus.UNKNOWN_SIGNER, result.signatureStatus)
        assertTrue(result.integrityProtected)
    }

    @Test
    fun modifiedCiphertextIsRejected() {
        val encrypted = ByteArrayOutputStream()
        OpenPgpEngine.encrypt(
            input = ByteArrayInputStream(ByteArray(32 * 1024) { it.toByte() }),
            output = encrypted,
            originalFileName = "tamper-test.bin",
            recipients = listOf(alice.publicKeyRing)
        )
        val modified = encrypted.toByteArray().apply {
            val index = size / 2
            this[index] = (this[index].toInt() xor 0x01).toByte()
        }

        val error = runCatching {
            OpenPgpEngine.decrypt(
                input = ByteArrayInputStream(modified),
                output = ByteArrayOutputStream(),
                secretKeyRings = listOf(alice.secretKeyRing),
                passphrase = ALICE_PASSPHRASE.copyOf()
            )
        }.exceptionOrNull()

        assertTrue(error is OpenPgpException)
    }

    @Test
    fun legacyMdcPackageRemainsDecryptable() {
        val plainText = "legacy MapSafe package".toByteArray()
        val encrypted = ByteArrayOutputStream()
        OpenPgpEngine.encrypt(
            input = ByteArrayInputStream(plainText),
            output = encrypted,
            originalFileName = "legacy.txt",
            recipients = listOf(alice.publicKeyRing),
            contentProtection = OpenPgpContentProtection.AES_256_CFB_MDC
        )

        val decrypted = ByteArrayOutputStream()
        val result = OpenPgpEngine.decrypt(
            input = ByteArrayInputStream(encrypted.toByteArray()),
            output = decrypted,
            secretKeyRings = listOf(alice.secretKeyRing),
            passphrase = ALICE_PASSPHRASE.copyOf()
        )

        assertArrayEquals(plainText, decrypted.toByteArray())
        assertEquals(OpenPgpContentProtection.AES_256_CFB_MDC, result.contentProtection)
    }

    @Test
    fun generatedSecretKeysUseStrengthenedIteratedS2k() {
        val iterationCount = alice.secretKeyRing.secretKey.s2K.iterationCount
        assertTrue("Expected at least 16 MiB of S2K hashing", iterationCount >= 16L * 1024L * 1024L)
    }

    @Test
    fun generatedCertificateAdvertisesRfc9580AesGcmSupport() {
        val primary = alice.publicKeyRing.publicKey
        val userId = primary.userIDs.asSequence().first()
        val certification = primary.getSignaturesForID(userId).asSequence().first()
        val subpackets = certification.hashedSubPackets

        assertTrue(subpackets.features.supportsSEIPDv2())
        assertTrue(
            subpackets.preferredAEADCiphersuites.isSupported(
                PreferredAEADCiphersuites.Combination(
                    SymmetricKeyAlgorithmTags.AES_256,
                    AEADAlgorithmTags.GCM
                )
            )
        )
    }

    @Test
    fun publicAndSecretKeyArmorRoundTrip() {
        val publicEncoded = OpenPgpKeyCodec.encodePublicKeyRing(alice.publicKeyRing)
        val secretEncoded = OpenPgpKeyCodec.encodeSecretKeyRing(alice.secretKeyRing)

        assertTrue(publicEncoded.decodeToString().startsWith("-----BEGIN PGP PUBLIC KEY BLOCK-----"))
        assertTrue(secretEncoded.decodeToString().startsWith("-----BEGIN PGP PRIVATE KEY BLOCK-----"))
        assertEquals(
            alice.info.fingerprint,
            OpenPgpKeyCodec.keyInfo(OpenPgpKeyCodec.decodePublicKeyRing(publicEncoded)).fingerprint
        )
        assertEquals(
            alice.info.fingerprint,
            OpenPgpKeyCodec.keyInfo(OpenPgpKeyCodec.decodeSecretKeyRing(secretEncoded)).fingerprint
        )
    }

    companion object {
        private val ALICE_PASSPHRASE = "correct horse battery staple".toCharArray()
        private val BOB_PASSPHRASE = "bob field recovery passphrase".toCharArray()
        private val CHARLIE_PASSPHRASE = "charlie recovery passphrase".toCharArray()

        private val alice by lazy {
            OpenPgpKeyGenerator.generate(
                userId = "Alice Fieldworker <alice@example.test>",
                passphrase = ALICE_PASSPHRASE.copyOf(),
                rsaBits = 2048
            )
        }
        private val bob by lazy {
            OpenPgpKeyGenerator.generate(
                userId = "Bob Recipient <bob@example.test>",
                passphrase = BOB_PASSPHRASE.copyOf(),
                rsaBits = 2048
            )
        }
        private val charlie by lazy {
            OpenPgpKeyGenerator.generate(
                userId = "Charlie Other <charlie@example.test>",
                passphrase = CHARLIE_PASSPHRASE.copyOf(),
                rsaBits = 2048
            )
        }

        private fun encryptedData(encoded: ByteArray): PGPPublicKeyEncryptedData {
            val decoder = PGPUtil.getDecoderStream(ByteArrayInputStream(encoded))
            val factory = PGPObjectFactory(decoder, BcKeyFingerprintCalculator())
            val encryptedList = generateSequence(factory::nextObject)
                .filterIsInstance<PGPEncryptedDataList>()
                .first()
            return encryptedList.encryptedDataObjects.asSequence()
                .filterIsInstance<PGPPublicKeyEncryptedData>()
                .first()
        }
    }
}
