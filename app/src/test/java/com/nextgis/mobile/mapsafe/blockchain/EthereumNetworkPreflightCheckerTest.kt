package com.nextgis.mobile.mapsafe.blockchain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EthereumNetworkPreflightCheckerTest {
    @Test
    fun passesWhenRpcChainMatchesAndContractCodeExists() {
        val gateway = FakeGateway(chainId = 11_155_111L, code = VALID_MAPSAFE_CODE)

        val report = EthereumNetworkPreflightChecker(gateway).check(completeProfile())

        assertTrue(report.succeeded)
        assertEquals(BlockchainCheckState.PASS, report.rpc.state)
        assertEquals(BlockchainCheckState.PASS, report.chain.state)
        assertEquals(BlockchainCheckState.PASS, report.contract.state)
        assertEquals(BlockchainCheckState.PASS, report.contractInterface.state)
        assertEquals(1, gateway.codeCalls)
        assertEquals(1, gateway.interfaceCalls)
    }

    @Test
    fun wrongChainStopsBeforeContractLookup() {
        val gateway = FakeGateway(chainId = 1L, code = VALID_MAPSAFE_CODE)

        val report = EthereumNetworkPreflightChecker(gateway).check(completeProfile())

        assertFalse(report.succeeded)
        assertEquals(BlockchainCheckState.PASS, report.rpc.state)
        assertEquals(BlockchainCheckState.FAIL, report.chain.state)
        assertEquals(BlockchainCheckState.SKIPPED, report.contract.state)
        assertEquals(BlockchainCheckState.SKIPPED, report.contractInterface.state)
        assertEquals(0, gateway.codeCalls)
        assertEquals(0, gateway.interfaceCalls)
    }

    @Test
    fun emptyCodeReportsMissingContract() {
        val report = EthereumNetworkPreflightChecker(
            FakeGateway(chainId = 11_155_111L, code = "0x")
        ).check(completeProfile())

        assertFalse(report.succeeded)
        assertEquals(BlockchainCheckState.FAIL, report.contract.state)
        assertEquals(BlockchainCheckState.SKIPPED, report.contractInterface.state)
        assertTrue(report.contract.message.contains("No deployed contract bytecode"))
    }

    @Test
    fun malformedCodeResponseReportsContractFailure() {
        val report = EthereumNetworkPreflightChecker(
            FakeGateway(chainId = 11_155_111L, code = "not-hex")
        ).check(completeProfile())

        assertFalse(report.succeeded)
        assertEquals(BlockchainCheckState.FAIL, report.contract.state)
        assertTrue(report.contract.message.contains("invalid contract bytecode"))
    }

    @Test
    fun missingMapSafeSelectorsFailsInterfaceCheckWithoutEthCall() {
        val gateway = FakeGateway(code = "0x60016000")

        val report = EthereumNetworkPreflightChecker(gateway).check(completeProfile())

        assertEquals(BlockchainCheckState.PASS, report.contract.state)
        assertEquals(BlockchainCheckState.FAIL, report.contractInterface.state)
        assertTrue(report.contractInterface.message.contains("0xfb37e883"))
        assertEquals(0, gateway.interfaceCalls)
    }

    @Test
    fun erc721RejectionFailsInterfaceCheck() {
        val report = EthereumNetworkPreflightChecker(
            FakeGateway(code = VALID_MAPSAFE_CODE, supportsInterface = false)
        ).check(completeProfile())

        assertEquals(BlockchainCheckState.FAIL, report.contractInterface.state)
        assertTrue(report.contractInterface.message.contains("does not report ERC-721"))
    }

    @Test
    fun rpcFailureSkipsDependentChecks() {
        val report = EthereumNetworkPreflightChecker(
            FakeGateway(chainError = EthereumRpcException("RPC unavailable."))
        ).check(completeProfile())

        assertEquals(BlockchainCheckState.FAIL, report.rpc.state)
        assertEquals(BlockchainCheckState.SKIPPED, report.chain.state)
        assertEquals(BlockchainCheckState.SKIPPED, report.contract.state)
        assertEquals(BlockchainCheckState.SKIPPED, report.contractInterface.state)
    }

    @Test
    fun parsesCanonicalEthereumHexValues() {
        assertEquals(11_155_111L, EthereumHex.parseQuantity("0xaa36a7"))
        assertEquals(0L, EthereumHex.parseQuantity("0x0"))
        assertEquals(4, EthereumHex.codeByteCount("0x60016000"))
        assertEquals(0, EthereumHex.codeByteCount("0x"))
        assertTrue(EthereumHex.parseAbiBoolean("0x" + "0".repeat(63) + "1"))
        assertFalse(EthereumHex.parseAbiBoolean("0x" + "0".repeat(64)))
        assertEquals(
            "0x01ffc9a780ac58cd" + "0".repeat(56),
            EthereumAbi.supportsInterfaceCall("0x80ac58cd")
        )
    }

    @Test(expected = EthereumRpcException::class)
    fun rejectsQuantityWithLeadingZero() {
        EthereumHex.parseQuantity("0x01")
    }

    private fun completeProfile(): BlockchainNetworkProfile =
        BlockchainNetworkPresets.defaults().activeProfile.copy(
            rpcUrl = "https://rpc.example.org"
        )

    private class FakeGateway(
        private val chainId: Long = 11_155_111L,
        private val code: String = "0x6000",
        private val supportsInterface: Boolean = true,
        private val chainError: EthereumRpcException? = null,
        private val codeError: EthereumRpcException? = null,
        private val interfaceError: EthereumRpcException? = null
    ) : EthereumJsonRpcGateway {
        var codeCalls = 0
        var interfaceCalls = 0

        override fun chainId(rpcUrl: String): Long {
            chainError?.let { throw it }
            return chainId
        }

        override fun codeAt(rpcUrl: String, contractAddress: String): String {
            codeCalls++
            codeError?.let { throw it }
            return code
        }

        override fun supportsInterface(
            rpcUrl: String,
            contractAddress: String,
            interfaceId: String
        ): Boolean {
            interfaceCalls++
            interfaceError?.let { throw it }
            return supportsInterface
        }
    }

    private companion object {
        const val VALID_MAPSAFE_CODE = "0x63fb37e88363b9e0db35"
    }
}
