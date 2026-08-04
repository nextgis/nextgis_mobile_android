package com.nextgis.mobile.mapsafe.crypto.openpgp

import org.bouncycastle.bcpg.CompressionAlgorithmTags
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.openpgp.PGPCompressedData
import org.bouncycastle.openpgp.PGPCompressedDataGenerator
import org.bouncycastle.openpgp.PGPEncryptedDataList
import org.bouncycastle.openpgp.PGPEncryptedDataGenerator
import org.bouncycastle.openpgp.PGPLiteralData
import org.bouncycastle.openpgp.PGPLiteralDataGenerator
import org.bouncycastle.openpgp.PGPObjectFactory
import org.bouncycastle.openpgp.PGPOnePassSignature
import org.bouncycastle.openpgp.PGPOnePassSignatureList
import org.bouncycastle.openpgp.PGPPublicKeyEncryptedData
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPSecretKey
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureGenerator
import org.bouncycastle.openpgp.PGPSignatureList
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyDecryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPContentVerifierBuilderProvider
import org.bouncycastle.openpgp.operator.bc.BcPGPDataEncryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider
import org.bouncycastle.openpgp.operator.bc.BcPublicKeyDataDecryptorFactory
import org.bouncycastle.openpgp.operator.bc.BcPublicKeyKeyEncryptionMethodGenerator
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.Date

object OpenPgpEngine {
    private const val BUFFER_SIZE = 64 * 1024

    fun encrypt(
        input: InputStream,
        output: OutputStream,
        originalFileName: String,
        recipients: Collection<PGPPublicKeyRing>,
        signingKeyRing: PGPSecretKeyRing? = null,
        signingPassphrase: CharArray? = null,
        secureRandom: SecureRandom = SecureRandom()
    ): OpenPgpEncryptionResult {
        val recipientKeys = recipients
            .map { ring ->
                val key = OpenPgpKeyCodec.findEncryptionKey(ring)
                    ?: throw OpenPgpException("${OpenPgpKeyCodec.keyInfo(ring).displayName} has no usable encryption key.")
                ring to key
            }
            .distinctBy { it.second.keyID }

        if (recipientKeys.isEmpty()) {
            throw OpenPgpException("Select at least one recipient.")
        }

        var signatureGenerator: PGPSignatureGenerator? = null
        var signerFingerprint: String? = null
        if (signingKeyRing != null) {
            val passphrase = signingPassphrase
                ?: throw OpenPgpException("The signing-key passphrase is required.")
            val signingKey = OpenPgpKeyCodec.findSigningSecretKey(signingKeyRing)
                ?: throw OpenPgpException("The local OpenPGP identity has no signing key.")
            try {
                val decryptor = BcPBESecretKeyDecryptorBuilder(BcPGPDigestCalculatorProvider())
                    .build(passphrase)
                val privateKey = signingKey.extractPrivateKey(decryptor)
                    ?: throw OpenPgpException("The signing private key is unavailable.")
                signatureGenerator = PGPSignatureGenerator(
                    BcPGPContentSignerBuilder(signingKey.publicKey.algorithm, HashAlgorithmTags.SHA256)
                        .setSecureRandom(secureRandom)
                ).apply {
                    init(PGPSignature.BINARY_DOCUMENT, privateKey)
                    signingKey.publicKey.userIDs.asSequence().firstOrNull()?.let { userId ->
                        val packets = PGPSignatureSubpacketGenerator().apply {
                            setSignerUserID(false, userId)
                        }.generate()
                        setHashedSubpackets(packets)
                    }
                }
                signerFingerprint = OpenPgpKeyCodec.fingerprint(signingKeyRing.publicKey.fingerprint)
            } catch (error: OpenPgpException) {
                throw error
            } catch (error: Exception) {
                throw OpenPgpException("The signing key could not be unlocked. Check the passphrase.", error)
            }
        }

        try {
            val encryptor = PGPEncryptedDataGenerator(
                BcPGPDataEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256)
                    .setWithIntegrityPacket(true)
                    .setSecureRandom(secureRandom)
            )
            recipientKeys.forEach { (_, key) ->
                encryptor.addMethod(
                    BcPublicKeyKeyEncryptionMethodGenerator(key).setSecureRandom(secureRandom)
                )
            }

            val encryptedOutput = encryptor.open(output, ByteArray(BUFFER_SIZE))
            val compressor = PGPCompressedDataGenerator(CompressionAlgorithmTags.ZIP)
            val compressedOutput = compressor.open(encryptedOutput, ByteArray(BUFFER_SIZE))

            signatureGenerator?.generateOnePassVersion(false)?.encode(compressedOutput)

            val literalGenerator = PGPLiteralDataGenerator()
            val literalOutput = literalGenerator.open(
                compressedOutput,
                PGPLiteralData.BINARY,
                sanitizeFileName(originalFileName),
                Date(),
                ByteArray(BUFFER_SIZE)
            )
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                literalOutput.write(buffer, 0, count)
                signatureGenerator?.update(buffer, 0, count)
            }
            literalGenerator.close()
            signatureGenerator?.generate()?.encode(compressedOutput)
            compressor.close()
            encryptor.close()

            return OpenPgpEncryptionResult(
                recipientFingerprints = recipientKeys.map { (ring, _) ->
                    OpenPgpKeyCodec.fingerprint(ring.publicKey.fingerprint)
                },
                signerFingerprint = signerFingerprint
            )
        } catch (error: OpenPgpException) {
            throw error
        } catch (error: Exception) {
            throw OpenPgpException("OpenPGP encryption failed.", error)
        }
    }

    fun decrypt(
        input: InputStream,
        output: OutputStream,
        secretKeyRings: Collection<PGPSecretKeyRing>,
        passphrase: CharArray,
        verificationKeyRings: Collection<PGPPublicKeyRing> = emptyList()
    ): OpenPgpDecryptionResult {
        if (secretKeyRings.isEmpty()) {
            throw OpenPgpException("No local OpenPGP secret key is available.")
        }

        try {
            val decoder = PGPUtil.getDecoderStream(input)
            val outerFactory = PGPObjectFactory(decoder, BcKeyFingerprintCalculator())
            val encryptedList = findEncryptedDataList(outerFactory)
                ?: throw OpenPgpException("The selected file is not an encrypted OpenPGP message.")

            var selectedData: PGPPublicKeyEncryptedData? = null
            var selectedSecretKey: PGPSecretKey? = null

            encryptedList.encryptedDataObjects.asSequence()
                .filterIsInstance<PGPPublicKeyEncryptedData>()
                .forEach { encryptedData ->
                    if (selectedData != null) return@forEach
                    secretKeyRings.forEach { ring ->
                        val candidate = ring.getSecretKey(encryptedData.keyID)
                        if (candidate != null) {
                            selectedData = encryptedData
                            selectedSecretKey = candidate
                        }
                    }
                }

            val encryptedData = selectedData
                ?: throw OpenPgpException("This package was not encrypted for a key held by this device.")
            val secretKey = selectedSecretKey
                ?: throw OpenPgpException("The matching OpenPGP secret key is unavailable.")

            val privateKey = try {
                val keyDecryptor = BcPBESecretKeyDecryptorBuilder(BcPGPDigestCalculatorProvider())
                    .build(passphrase)
                secretKey.extractPrivateKey(keyDecryptor)
            } catch (error: Exception) {
                throw OpenPgpException("The private key could not be unlocked. Check the passphrase.", error)
            } ?: throw OpenPgpException("The matching OpenPGP private key is unavailable.")

            val clearStream = encryptedData.getDataStream(BcPublicKeyDataDecryptorFactory(privateKey))
            var messageFactory = PGPObjectFactory(clearStream, BcKeyFingerprintCalculator())
            var message = messageFactory.nextObject()
            if (message is PGPCompressedData) {
                messageFactory = PGPObjectFactory(message.dataStream, BcKeyFingerprintCalculator())
                message = messageFactory.nextObject()
            }

            var onePassSignature: PGPOnePassSignature? = null
            var verificationKey = message.let {
                if (it is PGPOnePassSignatureList && !it.isEmpty) {
                    onePassSignature = it[0]
                    message = messageFactory.nextObject()
                    OpenPgpKeyCodec.findPublicKey(verificationKeyRings, onePassSignature!!.keyID)
                } else {
                    null
                }
            }

            if (onePassSignature != null && verificationKey != null) {
                onePassSignature!!.init(BcPGPContentVerifierBuilderProvider(), verificationKey)
            }

            val literalData = message as? PGPLiteralData
                ?: throw OpenPgpException("The OpenPGP message does not contain a file payload.")
            val buffer = ByteArray(BUFFER_SIZE)
            literalData.inputStream.use { literalInput ->
                while (true) {
                    val count = literalInput.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    output.write(buffer, 0, count)
                    if (verificationKey != null) {
                        onePassSignature?.update(buffer, 0, count)
                    }
                }
            }
            output.flush()

            val signatureObject = messageFactory.nextObject()
            val signatureStatus = when {
                onePassSignature == null -> OpenPgpSignatureStatus.NOT_SIGNED
                verificationKey == null -> OpenPgpSignatureStatus.UNKNOWN_SIGNER
                signatureObject !is PGPSignatureList || signatureObject.isEmpty -> OpenPgpSignatureStatus.INVALID
                onePassSignature!!.verify(signatureObject[0]) -> OpenPgpSignatureStatus.VALID
                else -> OpenPgpSignatureStatus.INVALID
            }

            val integrityProtected = encryptedData.isIntegrityProtected
            if (!integrityProtected) {
                throw OpenPgpException("The encrypted package has no integrity protection and was rejected.")
            }
            if (!encryptedData.verify()) {
                throw OpenPgpException("The encrypted package failed its integrity check and may have been modified.")
            }

            return OpenPgpDecryptionResult(
                originalFileName = sanitizeFileName(literalData.fileName),
                recipientKeyId = secretKey.keyID,
                integrityProtected = true,
                signatureStatus = signatureStatus,
                signerFingerprint = verificationKey?.let { OpenPgpKeyCodec.fingerprint(it.fingerprint) },
                signerUserIds = verificationKey?.userIDs?.asSequence()?.toList().orEmpty()
            )
        } catch (error: OpenPgpException) {
            throw error
        } catch (error: Exception) {
            throw OpenPgpException("OpenPGP decryption failed.", error)
        }
    }

    private fun findEncryptedDataList(factory: PGPObjectFactory): PGPEncryptedDataList? {
        repeat(4) {
            when (val value = factory.nextObject()) {
                null -> return null
                is PGPEncryptedDataList -> return value
            }
        }
        return null
    }

    private fun sanitizeFileName(fileName: String): String {
        val clean = fileName.substringAfterLast('/').substringAfterLast('\\').trim()
        return clean.takeIf { it.isNotEmpty() } ?: "mapsafe-data"
    }
}
