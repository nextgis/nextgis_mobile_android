package com.nextgis.mobile.mapsafe.blockchain

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in live acceptance check against the public filename-bound MapSafe Sepolia registry.
 *
 * Enable with:
 * -Pandroid.testInstrumentationRunnerArguments.mapsafeLiveRpc=true
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class MapSafeSepoliaAcceptanceDeviceTest {
    @Test
    fun verifiesKnownFilenameBoundTransactionThroughLiveRpc() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("mapsafeLiveRpc") == "true")
        val profile = BlockchainNetworkPresets.defaults().activeProfile

        val report = EthereumTransactionVerifier().verify(
            profile = profile,
            transactionHash = KNOWN_TRANSACTION,
            localSha256 = KNOWN_RECORDED_SHA256,
            expectedFileName = KNOWN_RECORDED_FILE_NAME
        )

        assertEquals(EthereumTransactionVerificationState.MATCH, report.state)
        assertEquals(KNOWN_RECORDED_SHA256, report.onChainHash)
        assertEquals(
            MapSafeIntegrityRecordFormat.FILENAME_HASH,
            report.recordFormat
        )
        assertEquals(KNOWN_RECORDED_FILE_NAME, report.onChainFileName)
        assertTrue(
            report.sender.equals(
                BlockchainNetworkPresets.QGIS_LEGACY_SENDER_ADDRESS,
                ignoreCase = true
            )
        )
    }

    private companion object {
        const val KNOWN_TRANSACTION =
            "0xbf22807cf1b7345d6df9a3a48179b3dd1f9d54c62e89164c640da57bc729a60e"
        const val KNOWN_RECORDED_SHA256 =
            "ea87365faf7e885463329aae555eef03c9f7d3d3041e53a0e0e2d842b95b5625"
        const val KNOWN_RECORDED_FILE_NAME = "mapsafe-live-test.pgp"
    }
}
