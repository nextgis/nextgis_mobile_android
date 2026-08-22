package com.nextgis.mobile.mapsafe.access.check

import com.nextgis.mobile.mapsafe.blockchain.BlockchainNetworkProfile
import java.net.URI
import java.util.Locale

data class EvmTransactionReference(
    val transactionHash: String,
    val canonicalUrl: String,
    val networkProfileId: String,
    val networkName: String,
    val chainId: Long
)

sealed class EvmReferenceValidation {
    data class Valid(val reference: EvmTransactionReference) : EvmReferenceValidation()
    data class Invalid(val message: String) : EvmReferenceValidation()
}

/** Validates a raw EVM hash or a transaction URL belonging to the active network profile. */
object EvmTransactionReferenceParser {
    private val transactionHashPattern = Regex("^0x[0-9a-fA-F]{64}$")
    private val transactionPathPattern = Regex("^/tx/(0x[0-9a-fA-F]{64})/?$")

    fun validate(
        value: String,
        profile: BlockchainNetworkProfile
    ): EvmReferenceValidation {
        val explorer = explorerOrigin(profile)
            ?: return EvmReferenceValidation.Invalid(
                "Configure a valid HTTPS explorer URL for ${profile.displayName} first."
            )
        val candidate = value.trim()
        if (candidate.isEmpty()) {
            return EvmReferenceValidation.Invalid(
                "Enter a ${profile.displayName} transaction URL or transaction hash."
            )
        }
        if (transactionHashPattern.matches(candidate)) {
            return valid(candidate, profile, explorer)
        }
        if (candidate.startsWith("0x", ignoreCase = true)) {
            return EvmReferenceValidation.Invalid(
                "A transaction hash must be 0x followed by 64 hexadecimal characters."
            )
        }

        val uri = runCatching { URI(candidate) }.getOrNull()
            ?: return EvmReferenceValidation.Invalid("The transaction URL is malformed.")
        if (uri.isOpaque || !uri.scheme.equals("https", ignoreCase = true)) {
            return EvmReferenceValidation.Invalid("Use an HTTPS transaction explorer URL.")
        }
        if (uri.userInfo != null) {
            return EvmReferenceValidation.Invalid(
                "Transaction URLs must not contain credentials."
            )
        }
        if (!sameOrigin(uri, explorer)) {
            return EvmReferenceValidation.Invalid(
                "Use the configured ${profile.displayName} explorer (${explorer.host})."
            )
        }
        if (uri.rawQuery != null || uri.rawFragment != null) {
            return EvmReferenceValidation.Invalid(
                "Remove query parameters and fragments from the transaction URL."
            )
        }
        val hash = transactionPathPattern.matchEntire(uri.rawPath.orEmpty())
            ?.groupValues
            ?.get(1)
            ?: return EvmReferenceValidation.Invalid(
                "Use a transaction URL in the form ${profile.explorerBaseUrl.trimEnd('/')}/tx/0x..."
            )
        return valid(hash, profile, explorer)
    }

    private fun valid(
        hash: String,
        profile: BlockchainNetworkProfile,
        explorer: URI
    ): EvmReferenceValidation.Valid {
        val normalizedHash = hash.lowercase(Locale.US)
        val explorerBase = URI(
            explorer.scheme.lowercase(Locale.US),
            null,
            explorer.host.lowercase(Locale.US),
            explorer.port,
            null,
            null,
            null
        ).toString().trimEnd('/')
        return EvmReferenceValidation.Valid(
            EvmTransactionReference(
                transactionHash = normalizedHash,
                canonicalUrl = "$explorerBase/tx/$normalizedHash",
                networkProfileId = profile.id,
                networkName = profile.displayName,
                chainId = profile.chainId
            )
        )
    }

    private fun explorerOrigin(profile: BlockchainNetworkProfile): URI? {
        val uri = runCatching { URI(profile.explorerBaseUrl.trim()) }.getOrNull() ?: return null
        val path = uri.rawPath.orEmpty()
        return uri.takeIf {
            !it.isOpaque &&
                it.scheme.equals("https", ignoreCase = true) &&
                !it.host.isNullOrBlank() &&
                it.userInfo == null &&
                it.rawQuery == null &&
                it.rawFragment == null &&
                (path.isEmpty() || path == "/")
        }
    }

    private fun sameOrigin(candidate: URI, explorer: URI): Boolean =
        candidate.scheme.equals(explorer.scheme, ignoreCase = true) &&
            candidate.host.equals(explorer.host, ignoreCase = true) &&
            candidate.port == explorer.port
}
