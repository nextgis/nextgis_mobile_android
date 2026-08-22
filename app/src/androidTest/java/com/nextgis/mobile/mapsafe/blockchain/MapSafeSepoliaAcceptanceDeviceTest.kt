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
 * Opt-in live acceptance check against a public QGIS MapSafe Sepolia transaction.
 *
 * Enable with:
 * -Pandroid.testInstrumentationRunnerArguments.mapsafeLiveRpc=true
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class MapSafeSepoliaAcceptanceDeviceTest {
    @Test
    fun verifiesKnownLegacyQgisTransactionThroughLiveRpc() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("mapsafeLiveRpc") == "true")
        val profile = BlockchainNetworkPresets.defaults().activeProfile

        val report = EthereumTransactionVerifier().verify(
            profile = profile,
            transactionHash = KNOWN_TRANSACTION,
            localSha256 = KNOWN_RECORDED_SHA256
        )

        assertEquals(EthereumTransactionVerificationState.MATCH, report.state)
        assertEquals(KNOWN_RECORDED_SHA256, report.onChainHash)
        assertEquals(
            MapSafeIntegrityRecordFormat.LEGACY_QGIS_FILENAME_HASH,
            report.recordFormat
        )
        assertTrue(
            report.sender.equals(
                BlockchainNetworkPresets.QGIS_LEGACY_SENDER_ADDRESS,
                ignoreCase = true
            )
        )
    }

    private companion object {
        const val KNOWN_TRANSACTION =
            "0x78d57005d2bcfdc639ed41bd8c12d13691b12289fee5182f686310c46b94386f"
        const val KNOWN_RECORDED_SHA256 =
            "120794b31743a23a2da97d1fd89068486b7c7fdd52623ae7ae699a3393a99a30"
    }
}
