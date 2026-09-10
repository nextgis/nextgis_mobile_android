package com.nextgis.mobile.mapsafe.blockchain

import java.util.Locale

internal data class MapSafeWalletTransaction(
    val chainId: String,
    val from: String,
    val to: String,
    val data: String,
    val canonicalRecord: String,
    val jsonRpcParams: String
)

/** Builds the zero-value `eth_sendTransaction` request approved by an external wallet. */
internal object MapSafeWalletTransactionCodec {
    private val addressPattern = Regex("^0x[0-9a-fA-F]{40}$")

    fun create(
        profile: BlockchainNetworkProfile,
        accountAddress: String,
        fileName: String,
        sha256: String
    ): MapSafeWalletTransaction {
        val validatedProfile = when (val result = BlockchainNetworkProfileValidator.validate(profile)) {
            is BlockchainProfileValidation.Valid -> result.profile
            is BlockchainProfileValidation.Invalid -> error(result.errors.joinToString(" "))
        }
        require(addressPattern.matches(accountAddress)) { "The connected wallet address is invalid." }
        val canonicalRecord = MapSafeIntegrityRecordCodec.encodeFileHash(fileName, sha256)
        val calldata = MapSafeMintCallCodec.encode(
            canonicalRecord,
            validatedProfile.contractInterface
        )
        val from = accountAddress.lowercase(Locale.US)
        val to = validatedProfile.contractAddress.lowercase(Locale.US)
        // Every interpolated value is validated hexadecimal data, so no JSON escaping is needed.
        val params = """[{"from":"$from","to":"$to","data":"$calldata","value":"0x0"}]"""
        return MapSafeWalletTransaction(
            chainId = "eip155:${validatedProfile.chainId}",
            from = from,
            to = to,
            data = calldata,
            canonicalRecord = canonicalRecord,
            jsonRpcParams = params
        )
    }
}
