package com.nextgis.mobile.mapsafe.blockchain

import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

internal data class EthereumTransaction(
    val hash: String,
    val from: String,
    val to: String?,
    val input: String,
    val value: String,
    val blockNumber: Long?
)

internal data class EthereumTransactionReceipt(
    val transactionHash: String,
    val from: String,
    val to: String?,
    val blockNumber: Long,
    val status: Long
)

internal interface EthereumTransactionRpcGateway {
    fun chainId(rpcUrl: String): Long
    fun transactionByHash(rpcUrl: String, transactionHash: String): EthereumTransaction?
    fun transactionReceipt(
        rpcUrl: String,
        transactionHash: String
    ): EthereumTransactionReceipt?
}

internal class OkHttpEthereumTransactionRpcGateway(
    private val caller: EthereumJsonRpcCaller = EthereumJsonRpcCaller()
) : EthereumTransactionRpcGateway {
    override fun chainId(rpcUrl: String): Long = EthereumHex.parseQuantity(
        caller.callString(rpcUrl, "eth_chainId", JSONArray())
    )

    override fun transactionByHash(
        rpcUrl: String,
        transactionHash: String
    ): EthereumTransaction? = EthereumTransactionJsonParser.transaction(
        caller.call(
            rpcUrl,
            "eth_getTransactionByHash",
            JSONArray().put(transactionHash)
        )
    )

    override fun transactionReceipt(
        rpcUrl: String,
        transactionHash: String
    ): EthereumTransactionReceipt? = EthereumTransactionJsonParser.receipt(
        caller.call(
            rpcUrl,
            "eth_getTransactionReceipt",
            JSONArray().put(transactionHash)
        )
    )
}

internal object EthereumTransactionJsonParser {
    private val hashPattern = Regex("^0x[0-9a-fA-F]{64}$")
    private val addressPattern = Regex("^0x[0-9a-fA-F]{40}$")
    private val dataPattern = Regex("^0x(?:[0-9a-fA-F]{2})*$")
    private val quantityPattern = Regex("^0x(?:0|[1-9a-fA-F][0-9a-fA-F]*)$")

    fun transaction(value: Any?): EthereumTransaction? {
        if (value == null) return null
        val json = value as? JSONObject
            ?: throw EthereumRpcException("The transaction result had an unexpected type.")
        val hash = required(json, "hash", "transaction hash", hashPattern)
        val from = required(json, "from", "transaction sender", addressPattern)
        val to = nullable(json, "to", "transaction recipient", addressPattern)
        val input = required(json, "input", "transaction input", dataPattern)
        val transactionValue = required(json, "value", "transaction value", quantityPattern)
        val blockNumber = nullableQuantity(json, "blockNumber", "transaction block number")
        return EthereumTransaction(hash, from, to, input, transactionValue, blockNumber)
    }

    fun receipt(value: Any?): EthereumTransactionReceipt? {
        if (value == null) return null
        val json = value as? JSONObject
            ?: throw EthereumRpcException("The transaction receipt had an unexpected type.")
        val transactionHash = required(
            json,
            "transactionHash",
            "receipt transaction hash",
            hashPattern
        )
        val from = required(json, "from", "receipt sender", addressPattern)
        val to = nullable(json, "to", "receipt recipient", addressPattern)
        val blockNumber = requiredQuantity(json, "blockNumber", "receipt block number")
        val status = requiredQuantity(json, "status", "receipt status")
        if (status !in 0L..1L) {
            throw EthereumRpcException("The RPC endpoint returned an invalid receipt status.")
        }
        return EthereumTransactionReceipt(transactionHash, from, to, blockNumber, status)
    }

    private fun required(
        json: JSONObject,
        key: String,
        label: String,
        pattern: Regex
    ): String {
        val result = json.opt(key) as? String
            ?: throw EthereumRpcException("The RPC endpoint omitted the $label.")
        if (!pattern.matches(result)) {
            throw EthereumRpcException("The RPC endpoint returned an invalid $label.")
        }
        return result
    }

    private fun nullable(
        json: JSONObject,
        key: String,
        label: String,
        pattern: Regex
    ): String? {
        if (!json.has(key) || json.isNull(key)) return null
        return required(json, key, label, pattern)
    }

    private fun requiredQuantity(json: JSONObject, key: String, label: String): Long {
        val value = required(json, key, label, quantityPattern)
        return value.substring(2).toLongOrNull(16)
            ?: throw EthereumRpcException("The $label is too large for this app.")
    }

    private fun nullableQuantity(json: JSONObject, key: String, label: String): Long? {
        if (!json.has(key) || json.isNull(key)) return null
        return requiredQuantity(json, key, label)
    }
}

internal enum class EthereumTransactionVerificationState {
    MATCH,
    HASH_MISMATCH,
    PENDING,
    INVALID
}

internal data class EthereumTransactionVerificationReport(
    val state: EthereumTransactionVerificationState,
    val message: String,
    val onChainHash: String? = null,
    val recordFormat: MapSafeIntegrityRecordFormat? = null,
    val sender: String? = null,
    val blockNumber: Long? = null
)

/** Retrieves and verifies a transaction without signing or changing blockchain state. */
internal class EthereumTransactionVerifier(
    private val gateway: EthereumTransactionRpcGateway = OkHttpEthereumTransactionRpcGateway()
) {
    fun verify(
        profile: BlockchainNetworkProfile,
        transactionHash: String,
        localSha256: String
    ): EthereumTransactionVerificationReport {
        val validatedProfile = when (val validation = BlockchainNetworkProfileValidator.validate(profile)) {
            is BlockchainProfileValidation.Valid -> validation.profile
            is BlockchainProfileValidation.Invalid -> return invalid(
                "The active blockchain profile is incomplete. Review its network settings."
            )
        }
        if (!TRANSACTION_HASH_PATTERN.matches(transactionHash)) {
            return invalid("The transaction hash is invalid.")
        }
        if (!SHA256_PATTERN.matches(localSha256)) {
            return invalid("The calculated local SHA-256 is invalid.")
        }

        return try {
            verifyFromRpc(validatedProfile, transactionHash, localSha256.lowercase(Locale.US))
        } catch (error: EthereumRpcException) {
            invalid(error.publicMessage)
        } catch (_: Exception) {
            invalid("The blockchain transaction could not be verified.")
        }
    }

    private fun verifyFromRpc(
        profile: BlockchainNetworkProfile,
        requestedHash: String,
        localSha256: String
    ): EthereumTransactionVerificationReport {
        val rpcChainId = gateway.chainId(profile.rpcUrl)
        if (rpcChainId != profile.chainId) {
            return invalid(
                "The RPC endpoint returned chain ID $rpcChainId, but ${profile.displayName} is configured for chain ID ${profile.chainId}."
            )
        }

        val transaction = gateway.transactionByHash(profile.rpcUrl, requestedHash)
            ?: return invalid("The transaction was not found on ${profile.displayName}.")
        if (!transaction.hash.equals(requestedHash, ignoreCase = true)) {
            return invalid("The RPC endpoint returned a different transaction hash.")
        }

        val receipt = gateway.transactionReceipt(profile.rpcUrl, requestedHash)
            ?: return EthereumTransactionVerificationReport(
                EthereumTransactionVerificationState.PENDING,
                "The transaction is pending and has no receipt yet. Try again after it is mined."
            )
        if (transaction.blockNumber == null) {
            return invalid("The transaction receipt exists, but the transaction is not marked as mined.")
        }
        if (!receipt.transactionHash.equals(requestedHash, ignoreCase = true)) {
            return invalid("The RPC endpoint returned a receipt for a different transaction.")
        }
        if (receipt.status != 1L) {
            return invalid("The transaction was mined but failed, so it did not create a valid record.")
        }
        if (transaction.blockNumber != receipt.blockNumber) {
            return invalid("The transaction and receipt block numbers do not agree.")
        }
        if (!transaction.from.equals(receipt.from, ignoreCase = true)) {
            return invalid("The transaction and receipt sender addresses do not agree.")
        }
        if (!sameAddress(transaction.to, profile.contractAddress) ||
            !sameAddress(receipt.to, profile.contractAddress)
        ) {
            return invalid("The transaction was not sent to the configured MapSafe contract.")
        }
        if (transaction.value != "0x0") {
            return invalid("The mintNFT call transferred value even though the configured function is non-payable.")
        }

        val decoded = when (
            val result = MapSafeMintCallCodec.decode(transaction.input, profile.contractInterface)
        ) {
            is MapSafeMintCallValidation.Valid -> result.recordValue
            is MapSafeMintCallValidation.Invalid -> return invalid(result.message)
        }
        val record = when (val result = MapSafeIntegrityRecordCodec.parse(decoded)) {
            is MapSafeIntegrityRecordValidation.Valid -> result.record
            is MapSafeIntegrityRecordValidation.Invalid -> return invalid(result.message)
        }
        val matched = record.sha256.equals(localSha256, ignoreCase = true)
        return EthereumTransactionVerificationReport(
            state = if (matched) {
                EthereumTransactionVerificationState.MATCH
            } else {
                EthereumTransactionVerificationState.HASH_MISMATCH
            },
            message = if (matched) {
                "Match confirmed. The blockchain record contains the same SHA-256 as the selected encrypted file."
            } else {
                "Hash mismatch. The transaction records a different encrypted file."
            },
            onChainHash = record.sha256,
            recordFormat = record.format,
            sender = transaction.from,
            blockNumber = receipt.blockNumber
        )
    }

    private fun sameAddress(value: String?, expected: String): Boolean =
        value?.equals(expected, ignoreCase = true) == true

    private fun invalid(message: String) = EthereumTransactionVerificationReport(
        EthereumTransactionVerificationState.INVALID,
        message
    )

    private companion object {
        val TRANSACTION_HASH_PATTERN = Regex("^0x[0-9a-fA-F]{64}$")
        val SHA256_PATTERN = Regex("^[0-9a-fA-F]{64}$")
    }
}
