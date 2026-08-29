package com.nextgis.mobile.mapsafe.crypto.openpgp

data class OpenPgpKeyInfo(
    val fingerprint: String,
    val keyId: Long,
    val userIds: List<String>,
    val canEncrypt: Boolean,
    val canSign: Boolean,
    val hasSecretKey: Boolean
) {
    val displayName: String
        get() = userIds.firstOrNull() ?: "OpenPGP key ${keyIdHex.takeLast(8)}"

    val keyIdHex: String
        get() = keyId.toULong().toString(16).uppercase().padStart(16, '0')
}

data class OpenPgpKeyMaterial(
    val publicKeyRing: org.bouncycastle.openpgp.PGPPublicKeyRing,
    val secretKeyRing: org.bouncycastle.openpgp.PGPSecretKeyRing,
    val info: OpenPgpKeyInfo
)

data class OpenPgpEncryptionResult(
    val recipientFingerprints: List<String>,
    val signerFingerprint: String?,
    val contentProtection: OpenPgpContentProtection
)

/**
 * Symmetric protection used for the single OpenPGP payload.
 *
 * New MapSafe packages use the RFC 9580 v2 SEIPD profile. The legacy profile
 * remains available only so older MapSafe/OpenPGP packages stay readable.
 */
enum class OpenPgpContentProtection(val displayName: String) {
    AES_256_GCM("AES-256-GCM"),
    AES_256_CFB_MDC("AES-256 with OpenPGP MDC (legacy)")
}

enum class OpenPgpSignatureStatus {
    NOT_SIGNED,
    VALID,
    INVALID,
    UNKNOWN_SIGNER
}

data class OpenPgpDecryptionResult(
    val originalFileName: String,
    val recipientKeyId: Long,
    val integrityProtected: Boolean,
    val contentProtection: OpenPgpContentProtection,
    val signatureStatus: OpenPgpSignatureStatus,
    val signerFingerprint: String? = null,
    val signerUserIds: List<String> = emptyList()
)

class OpenPgpException(message: String, cause: Throwable? = null) : Exception(message, cause)
