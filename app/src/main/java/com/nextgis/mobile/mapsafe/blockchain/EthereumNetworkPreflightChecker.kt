package com.nextgis.mobile.mapsafe.blockchain

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

internal enum class BlockchainCheckState {
    PASS,
    FAIL,
    SKIPPED
}

internal data class BlockchainCheckStep(
    val state: BlockchainCheckState,
    val message: String
)

internal data class BlockchainNetworkCheckReport(
    val profileId: String,
    val networkName: String,
    val configuredChainId: Long,
    val rpcChainId: Long?,
    val rpc: BlockchainCheckStep,
    val chain: BlockchainCheckStep,
    val contract: BlockchainCheckStep,
    val contractInterface: BlockchainCheckStep
) {
    val succeeded: Boolean
        get() = rpc.state == BlockchainCheckState.PASS &&
            chain.state == BlockchainCheckState.PASS &&
            contract.state == BlockchainCheckState.PASS &&
            contractInterface.state == BlockchainCheckState.PASS
}

internal interface EthereumJsonRpcGateway {
    fun chainId(rpcUrl: String): Long
    fun codeAt(rpcUrl: String, contractAddress: String): String
    fun supportsInterface(rpcUrl: String, contractAddress: String, interfaceId: String): Boolean
}

/** Performs read-only checks. It has no wallet, signing, or transaction APIs. */
internal class EthereumNetworkPreflightChecker(
    private val gateway: EthereumJsonRpcGateway = OkHttpEthereumJsonRpcGateway()
) {
    fun check(profile: BlockchainNetworkProfile): BlockchainNetworkCheckReport {
        val actualChainId = try {
            gateway.chainId(profile.rpcUrl)
        } catch (error: EthereumRpcException) {
            return failedRpcReport(profile, error.publicMessage)
        } catch (_: Exception) {
            return failedRpcReport(profile, "The RPC endpoint could not be checked.")
        }

        val rpcStep = BlockchainCheckStep(
            BlockchainCheckState.PASS,
            "The RPC endpoint returned a valid Ethereum chain ID."
        )
        if (actualChainId != profile.chainId) {
            return BlockchainNetworkCheckReport(
                profileId = profile.id,
                networkName = profile.displayName,
                configuredChainId = profile.chainId,
                rpcChainId = actualChainId,
                rpc = rpcStep,
                chain = BlockchainCheckStep(
                    BlockchainCheckState.FAIL,
                    "Configured chain ID ${profile.chainId} does not match RPC chain ID $actualChainId."
                ),
                contract = BlockchainCheckStep(
                    BlockchainCheckState.SKIPPED,
                    "Contract check was not run on the wrong network."
                ),
                contractInterface = BlockchainCheckStep(
                    BlockchainCheckState.SKIPPED,
                    "Contract-interface check was not run on the wrong network."
                )
            )
        }

        val code = try {
            gateway.codeAt(profile.rpcUrl, profile.contractAddress)
        } catch (error: EthereumRpcException) {
            return BlockchainNetworkCheckReport(
                profileId = profile.id,
                networkName = profile.displayName,
                configuredChainId = profile.chainId,
                rpcChainId = actualChainId,
                rpc = rpcStep,
                chain = matchingChainStep(profile.chainId),
                contract = BlockchainCheckStep(
                    BlockchainCheckState.FAIL,
                    "Contract lookup failed: ${error.publicMessage}"
                ),
                contractInterface = BlockchainCheckStep(
                    BlockchainCheckState.SKIPPED,
                    "Contract-interface check could not run."
                )
            )
        } catch (_: Exception) {
            return BlockchainNetworkCheckReport(
                profileId = profile.id,
                networkName = profile.displayName,
                configuredChainId = profile.chainId,
                rpcChainId = actualChainId,
                rpc = rpcStep,
                chain = matchingChainStep(profile.chainId),
                contract = BlockchainCheckStep(
                    BlockchainCheckState.FAIL,
                    "The contract address could not be checked."
                ),
                contractInterface = BlockchainCheckStep(
                    BlockchainCheckState.SKIPPED,
                    "Contract-interface check could not run."
                )
            )
        }

        val codeBytes = try {
            EthereumHex.codeByteCount(code)
        } catch (error: EthereumRpcException) {
            return BlockchainNetworkCheckReport(
                profileId = profile.id,
                networkName = profile.displayName,
                configuredChainId = profile.chainId,
                rpcChainId = actualChainId,
                rpc = rpcStep,
                chain = matchingChainStep(profile.chainId),
                contract = BlockchainCheckStep(
                    BlockchainCheckState.FAIL,
                    error.publicMessage
                ),
                contractInterface = BlockchainCheckStep(
                    BlockchainCheckState.SKIPPED,
                    "Contract-interface check could not run."
                )
            )
        }
        val contractStep = if (codeBytes == 0) {
            BlockchainCheckStep(
                BlockchainCheckState.FAIL,
                "No deployed contract bytecode was found at ${profile.contractAddress}. " +
                    "Legacy transaction-input verification may still work, but this address " +
                    "must not be used for new notarisation."
            )
        } else {
            BlockchainCheckStep(
                BlockchainCheckState.PASS,
                "Deployed contract bytecode was found (${formatBytes(codeBytes)})."
            )
        }
        if (codeBytes == 0) {
            return BlockchainNetworkCheckReport(
                profileId = profile.id,
                networkName = profile.displayName,
                configuredChainId = profile.chainId,
                rpcChainId = actualChainId,
                rpc = rpcStep,
                chain = matchingChainStep(profile.chainId),
                contract = contractStep,
                contractInterface = BlockchainCheckStep(
                    BlockchainCheckState.SKIPPED,
                    "Contract-interface check requires deployed bytecode."
                )
            )
        }

        val missingSelectors = MapSafeContractAbiValidator.missingRuntimeSelectors(
            profile.contractInterface,
            code
        )
        if (missingSelectors.isNotEmpty()) {
            return BlockchainNetworkCheckReport(
                profileId = profile.id,
                networkName = profile.displayName,
                configuredChainId = profile.chainId,
                rpcChainId = actualChainId,
                rpc = rpcStep,
                chain = matchingChainStep(profile.chainId),
                contract = contractStep,
                contractInterface = BlockchainCheckStep(
                    BlockchainCheckState.FAIL,
                    "Expected MapSafe selectors were not found in the runtime bytecode: ${missingSelectors.joinToString()}."
                )
            )
        }

        val supportsRequiredInterface = try {
            gateway.supportsInterface(
                profile.rpcUrl,
                profile.contractAddress,
                profile.contractInterface.requiredErc165InterfaceId
            )
        } catch (error: EthereumRpcException) {
            return BlockchainNetworkCheckReport(
                profileId = profile.id,
                networkName = profile.displayName,
                configuredChainId = profile.chainId,
                rpcChainId = actualChainId,
                rpc = rpcStep,
                chain = matchingChainStep(profile.chainId),
                contract = contractStep,
                contractInterface = BlockchainCheckStep(
                    BlockchainCheckState.FAIL,
                    "ERC-165 interface query failed: ${error.publicMessage}"
                )
            )
        } catch (_: Exception) {
            return BlockchainNetworkCheckReport(
                profileId = profile.id,
                networkName = profile.displayName,
                configuredChainId = profile.chainId,
                rpcChainId = actualChainId,
                rpc = rpcStep,
                chain = matchingChainStep(profile.chainId),
                contract = contractStep,
                contractInterface = BlockchainCheckStep(
                    BlockchainCheckState.FAIL,
                    "The ERC-165 interface query could not be completed."
                )
            )
        }
        val interfaceStep = if (supportsRequiredInterface) {
            BlockchainCheckStep(
                BlockchainCheckState.PASS,
                "ERC-721 support was reported and the expected MapSafe selectors were detected."
            )
        } else {
            BlockchainCheckStep(
                BlockchainCheckState.FAIL,
                "The contract does not report ERC-721 support through ERC-165."
            )
        }
        return BlockchainNetworkCheckReport(
            profileId = profile.id,
            networkName = profile.displayName,
            configuredChainId = profile.chainId,
            rpcChainId = actualChainId,
            rpc = rpcStep,
            chain = matchingChainStep(profile.chainId),
            contract = contractStep,
            contractInterface = interfaceStep
        )
    }

    private fun failedRpcReport(
        profile: BlockchainNetworkProfile,
        message: String
    ): BlockchainNetworkCheckReport = BlockchainNetworkCheckReport(
        profileId = profile.id,
        networkName = profile.displayName,
        configuredChainId = profile.chainId,
        rpcChainId = null,
        rpc = BlockchainCheckStep(BlockchainCheckState.FAIL, message),
        chain = BlockchainCheckStep(
            BlockchainCheckState.SKIPPED,
            "Chain ID comparison could not run."
        ),
        contract = BlockchainCheckStep(
            BlockchainCheckState.SKIPPED,
            "Contract check could not run."
        ),
        contractInterface = BlockchainCheckStep(
            BlockchainCheckState.SKIPPED,
            "Contract-interface check could not run."
        )
    )

    private fun matchingChainStep(chainId: Long): BlockchainCheckStep = BlockchainCheckStep(
        BlockchainCheckState.PASS,
        "Configured chain ID $chainId matches the RPC endpoint."
    )

    private fun formatBytes(bytes: Int): String =
        if (bytes == 1) "1 byte" else "$bytes bytes"
}

internal class EthereumRpcException(
    val publicMessage: String,
    cause: Throwable? = null
) : Exception(publicMessage, cause)

internal object EthereumHex {
    private val quantityPattern = Regex("^0x(?:0|[1-9a-fA-F][0-9a-fA-F]*)$")
    private val dataPattern = Regex("^0x(?:[0-9a-fA-F]{2})*$")

    fun parseQuantity(value: String): Long {
        if (!quantityPattern.matches(value)) {
            throw EthereumRpcException("The RPC endpoint returned an invalid chain ID.")
        }
        return value.substring(2).toLongOrNull(16)
            ?: throw EthereumRpcException("The RPC chain ID is too large for this app.")
    }

    fun codeByteCount(value: String): Int {
        if (!dataPattern.matches(value)) {
            throw EthereumRpcException("The RPC endpoint returned invalid contract bytecode.")
        }
        return (value.length - 2) / 2
    }

    fun parseAbiBoolean(value: String): Boolean {
        if (!Regex("^0x[0-9a-fA-F]{64}$").matches(value)) {
            throw EthereumRpcException("The RPC endpoint returned an invalid ABI boolean.")
        }
        return when (value.substring(2).toLongOrNull(16)) {
            0L -> false
            1L -> true
            else -> throw EthereumRpcException("The RPC endpoint returned an invalid ABI boolean.")
        }
    }
}

internal object EthereumAbi {
    private val interfaceIdPattern = Regex("^0x[0-9a-fA-F]{8}$")

    fun supportsInterfaceCall(interfaceId: String): String {
        if (!interfaceIdPattern.matches(interfaceId)) {
            throw EthereumRpcException("The configured ERC-165 interface ID is invalid.")
        }
        return "0x01ffc9a7" + interfaceId.removePrefix("0x") + "0".repeat(56)
    }
}

internal object EthereumJsonRpcResponseParser {
    fun result(responseBody: String, expectedId: Long): String {
        val value = resultValue(responseBody, expectedId)
        return value as? String
            ?: throw EthereumRpcException("The RPC result had an unexpected type.")
    }

    fun resultValue(responseBody: String, expectedId: Long): Any? {
        val root = try {
            JSONObject(responseBody)
        } catch (error: Exception) {
            throw EthereumRpcException("The RPC endpoint returned malformed JSON.", error)
        }
        if (root.optString("jsonrpc") != "2.0") {
            throw EthereumRpcException("The endpoint did not return a JSON-RPC 2.0 response.")
        }
        val responseId = when (val id = root.opt("id")) {
            is Number -> id.toLong()
            is String -> id.toLongOrNull()
            else -> null
        }
        if (responseId != expectedId) {
            throw EthereumRpcException("The RPC response ID did not match the request.")
        }
        if (root.has("error") && !root.isNull("error")) {
            val error = root.optJSONObject("error")
            val code = error?.optLong("code")
            val message = error?.optString("message")
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                ?.take(120)
                ?.takeIf(String::isNotBlank)
            val detail = buildString {
                append("The RPC endpoint returned an error")
                code?.let { append(" ($it)") }
                message?.let { append(": $it") }
                append('.')
            }
            throw EthereumRpcException(detail)
        }
        if (!root.has("result")) {
            throw EthereumRpcException("The RPC response did not contain a result.")
        }
        return root.opt("result").takeUnless { it === JSONObject.NULL }
    }
}

internal class EthereumJsonRpcCaller(
    private val client: OkHttpClient = defaultClient()
) {
    private val requestIds = AtomicLong(0L)

    fun call(rpcUrl: String, method: String, params: JSONArray): Any? {
        val requestId = requestIds.incrementAndGet()
        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", method)
            .put("params", params)
            .put("id", requestId)
            .toString()
        val request = try {
            Request.Builder()
                .url(rpcUrl)
                .header("Accept", JSON_MEDIA_TYPE.toString())
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()
        } catch (error: Exception) {
            throw EthereumRpcException("The configured RPC endpoint is invalid.", error)
        }

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw EthereumRpcException(
                        "The RPC endpoint returned HTTP ${response.code}."
                    )
                }
                val responseBody = readLimited(response.body.byteStream())
                return EthereumJsonRpcResponseParser.resultValue(responseBody, requestId)
            }
        } catch (error: EthereumRpcException) {
            throw error
        } catch (error: IOException) {
            throw EthereumRpcException(
                "The RPC endpoint could not be reached securely.",
                error
            )
        } catch (error: Exception) {
            throw EthereumRpcException("The RPC request failed.", error)
        }
    }

    fun callString(rpcUrl: String, method: String, params: JSONArray): String =
        call(rpcUrl, method, params) as? String
            ?: throw EthereumRpcException("The RPC result had an unexpected type.")

    private fun readLimited(input: java.io.InputStream): String {
        input.use { source ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            var total = 0
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_RESPONSE_BYTES) {
                    throw EthereumRpcException("The RPC response was unexpectedly large.")
                }
                output.write(buffer, 0, count)
            }
            return output.toString(Charsets.UTF_8.name())
        }
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 2 * 1_024 * 1_024
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}

internal class OkHttpEthereumJsonRpcGateway(
    private val caller: EthereumJsonRpcCaller = EthereumJsonRpcCaller()
) : EthereumJsonRpcGateway {
    override fun chainId(rpcUrl: String): Long =
        EthereumHex.parseQuantity(caller.callString(rpcUrl, "eth_chainId", JSONArray()))

    override fun codeAt(rpcUrl: String, contractAddress: String): String {
        val result = caller.callString(
            rpcUrl,
            "eth_getCode",
            JSONArray().put(contractAddress).put("latest")
        )
        EthereumHex.codeByteCount(result)
        return result
    }

    override fun supportsInterface(
        rpcUrl: String,
        contractAddress: String,
        interfaceId: String
    ): Boolean {
        val result = caller.callString(
            rpcUrl,
            "eth_call",
            JSONArray()
                .put(
                    JSONObject()
                        .put("to", contractAddress)
                        .put("data", EthereumAbi.supportsInterfaceCall(interfaceId))
                )
                .put("latest")
        )
        return EthereumHex.parseAbiBoolean(result)
    }
}
