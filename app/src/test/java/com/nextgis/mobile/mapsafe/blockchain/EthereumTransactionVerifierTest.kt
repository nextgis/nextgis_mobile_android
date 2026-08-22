package com.nextgis.mobile.mapsafe.blockchain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EthereumTransactionVerifierTest {
    private val transactionHash = "0x" + "12".repeat(32)
    private val localHash = "ab".repeat(32)
    private val sender = "0x" + "34".repeat(20)
    private val contract = "0x8dD5Ca941A9F839062b6589A2E3f701458B011A9"

    @Test
    fun confirmsCanonicalHashMatch() {
        val gateway = FakeGateway(recordValue = MapSafeIntegrityRecordCodec.encodeSha256(localHash))

        val report = EthereumTransactionVerifier(gateway).verify(
            completeProfile(),
            transactionHash,
            localHash
        )

        assertEquals(EthereumTransactionVerificationState.MATCH, report.state)
        assertEquals(localHash, report.onChainHash)
        assertEquals(MapSafeIntegrityRecordFormat.MAPSAFE_V1, report.recordFormat)
        assertEquals(sender, report.sender)
        assertEquals(101L, report.blockNumber)
    }

    @Test
    fun reportsHashMismatchWithoutTreatingTransactionAsInvalid() {
        val otherHash = "cd".repeat(32)
        val gateway = FakeGateway(recordValue = MapSafeIntegrityRecordCodec.encodeSha256(otherHash))

        val report = EthereumTransactionVerifier(gateway).verify(
            completeProfile(),
            transactionHash,
            localHash
        )

        assertEquals(EthereumTransactionVerificationState.HASH_MISMATCH, report.state)
        assertEquals(otherHash, report.onChainHash)
    }

    @Test
    fun acceptsLegacyQgisRecordForReadOnlyVerification() {
        val gateway = FakeGateway(recordValue = "collected_sites.zip_$localHash")

        val report = EthereumTransactionVerifier(gateway).verify(
            completeProfile(),
            transactionHash,
            localHash
        )

        assertEquals(EthereumTransactionVerificationState.MATCH, report.state)
        assertEquals(MapSafeIntegrityRecordFormat.LEGACY_QGIS_FILENAME_HASH, report.recordFormat)
    }

    @Test
    fun wrongChainStopsBeforeTransactionLookup() {
        val gateway = FakeGateway(
            chainId = 1L,
            recordValue = MapSafeIntegrityRecordCodec.encodeSha256(localHash)
        )

        val report = EthereumTransactionVerifier(gateway).verify(
            completeProfile(),
            transactionHash,
            localHash
        )

        assertEquals(EthereumTransactionVerificationState.INVALID, report.state)
        assertEquals(0, gateway.transactionCalls)
        assertTrue(report.message.contains("chain ID 1"))
    }

    @Test
    fun reportsPendingTransactionSeparately() {
        val gateway = FakeGateway(
            recordValue = MapSafeIntegrityRecordCodec.encodeSha256(localHash),
            receipt = null
        )

        val report = EthereumTransactionVerifier(gateway).verify(
            completeProfile(),
            transactionHash,
            localHash
        )

        assertEquals(EthereumTransactionVerificationState.PENDING, report.state)
    }

    @Test
    fun rejectsFailedWrongContractAndUnrelatedCalls() {
        val record = MapSafeIntegrityRecordCodec.encodeSha256(localHash)
        val failed = EthereumTransactionVerifier(
            FakeGateway(recordValue = record, receiptStatus = 0L)
        ).verify(completeProfile(), transactionHash, localHash)
        val wrongContract = EthereumTransactionVerifier(
            FakeGateway(recordValue = record, transactionTo = "0x" + "56".repeat(20))
        ).verify(completeProfile(), transactionHash, localHash)
        val gateway = FakeGateway(recordValue = record)
        gateway.inputOverride = "0xdeadbeef"
        val unrelated = EthereumTransactionVerifier(gateway)
            .verify(completeProfile(), transactionHash, localHash)
        val transferredValue = EthereumTransactionVerifier(
            FakeGateway(recordValue = record, transactionValue = "0x1")
        ).verify(completeProfile(), transactionHash, localHash)

        assertEquals(EthereumTransactionVerificationState.INVALID, failed.state)
        assertEquals(EthereumTransactionVerificationState.INVALID, wrongContract.state)
        assertEquals(EthereumTransactionVerificationState.INVALID, unrelated.state)
        assertEquals(EthereumTransactionVerificationState.INVALID, transferredValue.state)
    }

    private fun completeProfile(): BlockchainNetworkProfile =
        BlockchainNetworkPresets.defaults().activeProfile.copy(
            rpcUrl = "https://rpc.example.org",
            contractAddress = contract
        )

    private inner class FakeGateway(
        private val chainId: Long = 11_155_111L,
        private val recordValue: String,
        private val transactionTo: String = contract,
        private val transactionValue: String = "0x0",
        private val receiptStatus: Long = 1L,
        private val receipt: EthereumTransactionReceipt? = EthereumTransactionReceipt(
            transactionHash = transactionHash,
            from = sender,
            to = transactionTo,
            blockNumber = 101L,
            status = receiptStatus
        )
    ) : EthereumTransactionRpcGateway {
        var transactionCalls = 0
        var inputOverride: String? = null

        override fun chainId(rpcUrl: String): Long = chainId

        override fun transactionByHash(
            rpcUrl: String,
            transactionHash: String
        ): EthereumTransaction {
            transactionCalls++
            return EthereumTransaction(
                hash = this@EthereumTransactionVerifierTest.transactionHash,
                from = sender,
                to = transactionTo,
                input = inputOverride ?: MapSafeMintCallCodec.encode(recordValue),
                value = transactionValue,
                blockNumber = 101L
            )
        }

        override fun transactionReceipt(
            rpcUrl: String,
            transactionHash: String
        ): EthereumTransactionReceipt? = receipt
    }
}
