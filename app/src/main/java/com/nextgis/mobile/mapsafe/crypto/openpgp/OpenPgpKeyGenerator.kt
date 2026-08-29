package com.nextgis.mobile.mapsafe.crypto.openpgp

import org.bouncycastle.bcpg.AEADAlgorithmTags
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.bcpg.sig.Features
import org.bouncycastle.bcpg.sig.KeyFlags
import org.bouncycastle.bcpg.sig.PreferredAEADCiphersuites
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.generators.RSAKeyPairGenerator
import org.bouncycastle.crypto.params.RSAKeyGenerationParameters
import org.bouncycastle.openpgp.PGPKeyRingGenerator
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyEncryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider
import org.bouncycastle.openpgp.operator.bc.BcPGPKeyPair
import java.math.BigInteger
import java.security.SecureRandom
import java.util.Date

object OpenPgpKeyGenerator {
    const val DEFAULT_RSA_BITS = 3072
    private const val SECRET_KEY_S2K_COUNT = 0xE0

    fun generate(
        userId: String,
        passphrase: CharArray,
        rsaBits: Int = DEFAULT_RSA_BITS,
        secureRandom: SecureRandom = SecureRandom(),
        creationTime: Date = Date()
    ): OpenPgpKeyMaterial {
        require(userId.isNotBlank()) { "An OpenPGP user ID is required." }
        require(passphrase.isNotEmpty()) { "A recovery passphrase is required." }
        require(rsaBits >= 2048) { "RSA keys must be at least 2048 bits." }

        try {
            val primary = BcPGPKeyPair(
                PublicKeyAlgorithmTags.RSA_SIGN,
                generateRsaKeyPair(rsaBits, secureRandom),
                creationTime
            )
            val encryptionSubkey = BcPGPKeyPair(
                PublicKeyAlgorithmTags.RSA_ENCRYPT,
                generateRsaKeyPair(rsaBits, secureRandom),
                creationTime
            )

            val digestProvider = BcPGPDigestCalculatorProvider()
            val sha1 = digestProvider.get(HashAlgorithmTags.SHA1)
            val sha256 = digestProvider.get(HashAlgorithmTags.SHA256)

            val primaryPackets = PGPSignatureSubpacketGenerator().apply {
                setKeyFlags(false, KeyFlags.CERTIFY_OTHER or KeyFlags.SIGN_DATA)
                setPreferredSymmetricAlgorithms(
                    false,
                    intArrayOf(SymmetricKeyAlgorithmTags.AES_256, SymmetricKeyAlgorithmTags.AES_128)
                )
                setPreferredHashAlgorithms(
                    false,
                    intArrayOf(HashAlgorithmTags.SHA256, HashAlgorithmTags.SHA512)
                )
                setFeature(
                    false,
                    (Features.FEATURE_MODIFICATION_DETECTION.toInt() or
                        Features.FEATURE_SEIPD_V2.toInt()).toByte()
                )
                setPreferredAEADCiphersuites(
                    PreferredAEADCiphersuites.builder(false)
                        .addCombination(SymmetricKeyAlgorithmTags.AES_256, AEADAlgorithmTags.GCM)
                )
            }.generate()

            val encryptionPackets = PGPSignatureSubpacketGenerator().apply {
                setKeyFlags(false, KeyFlags.ENCRYPT_COMMS or KeyFlags.ENCRYPT_STORAGE)
            }.generate()

            val signer = BcPGPContentSignerBuilder(primary.publicKey.algorithm, HashAlgorithmTags.SHA256)
                .setSecureRandom(secureRandom)
            val secretKeyEncryptor = BcPBESecretKeyEncryptorBuilder(
                SymmetricKeyAlgorithmTags.AES_256,
                sha256,
                SECRET_KEY_S2K_COUNT
            ).setSecureRandom(secureRandom).build(passphrase)

            val generator = PGPKeyRingGenerator(
                PGPSignature.POSITIVE_CERTIFICATION,
                primary,
                userId.trim(),
                sha1,
                primaryPackets,
                null,
                signer,
                secretKeyEncryptor
            )
            generator.addSubKey(encryptionSubkey, encryptionPackets, null)

            val publicRing = generator.generatePublicKeyRing()
            val secretRing = generator.generateSecretKeyRing()
            return OpenPgpKeyMaterial(
                publicKeyRing = publicRing,
                secretKeyRing = secretRing,
                info = OpenPgpKeyCodec.keyInfo(publicRing, hasSecretKey = true)
            )
        } catch (error: Exception) {
            throw OpenPgpException("OpenPGP key generation failed.", error)
        }
    }

    private fun generateRsaKeyPair(bits: Int, secureRandom: SecureRandom): AsymmetricCipherKeyPair {
        return RSAKeyPairGenerator().apply {
            init(
                RSAKeyGenerationParameters(
                    BigInteger.valueOf(0x10001L),
                    secureRandom,
                    bits,
                    100
                )
            )
        }.generateKeyPair()
    }
}
