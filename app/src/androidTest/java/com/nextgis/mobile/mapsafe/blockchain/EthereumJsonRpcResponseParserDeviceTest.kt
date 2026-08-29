package com.nextgis.mobile.mapsafe.blockchain

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EthereumJsonRpcResponseParserDeviceTest {
    @Test
    fun parsesMatchingJsonRpcResult() {
        val result = EthereumJsonRpcResponseParser.result(
            """{"jsonrpc":"2.0","id":7,"result":"0xaa36a7"}""",
            expectedId = 7L
        )

        assertEquals("0xaa36a7", result)
    }

    @Test(expected = EthereumRpcException::class)
    fun rejectsJsonRpcError() {
        EthereumJsonRpcResponseParser.result(
            """{"jsonrpc":"2.0","id":8,"error":{"code":-32601,"message":"not supported"}}""",
            expectedId = 8L
        )
    }

    @Test
    fun parsesTransactionObjectAndNullPendingReceipt() {
        val hash = "0x" + "12".repeat(32)
        val sender = "0x" + "34".repeat(20)
        val contract = "0x" + "56".repeat(20)
        val transactionValue = EthereumJsonRpcResponseParser.resultValue(
            """{"jsonrpc":"2.0","id":9,"result":{"hash":"$hash","from":"$sender","to":"$contract","input":"0xfb37e883","value":"0x0","blockNumber":"0x65"}}""",
            expectedId = 9L
        )
        val transaction = EthereumTransactionJsonParser.transaction(transactionValue)
        val receiptValue = EthereumJsonRpcResponseParser.resultValue(
            """{"jsonrpc":"2.0","id":10,"result":null}""",
            expectedId = 10L
        )
        val minedReceiptValue = EthereumJsonRpcResponseParser.resultValue(
            """{"jsonrpc":"2.0","id":11,"result":{"transactionHash":"$hash","from":"$sender","to":"$contract","blockNumber":"0x65","status":"0x1"}}""",
            expectedId = 11L
        )
        val minedReceipt = EthereumTransactionJsonParser.receipt(minedReceiptValue)

        assertEquals(hash, transaction?.hash)
        assertEquals(101L, transaction?.blockNumber)
        assertNull(EthereumTransactionJsonParser.receipt(receiptValue))
        assertEquals(1L, minedReceipt?.status)
        assertEquals(101L, minedReceipt?.blockNumber)
    }
}
