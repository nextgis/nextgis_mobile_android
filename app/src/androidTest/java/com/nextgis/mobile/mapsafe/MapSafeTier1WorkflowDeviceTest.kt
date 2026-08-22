package com.nextgis.mobile.mapsafe

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.nextgis.mobile.MainApplication
import com.nextgis.mobile.activity.MainActivity
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpEngine
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpKeyCodec
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpKeyGenerator
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpKeyRepository
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpSignatureStatus
import com.nextgis.mobile.mapsafe.keys.PublicKeyDirectoryIdentity
import com.nextgis.mobile.mapsafe.keys.PublicKeyExchangeRepository
import com.nextgis.mobile.mapsafe.keys.PublicKeyObservation
import com.nextgis.mobile.mapsafe.keys.PublicKeyTrustState
import com.nextgis.mobile.mapsafe.service.DonutMaskingWorkflow
import com.nextgis.mobile.mapsafe.service.HashUtils
import com.nextgis.mobile.mapsafe.service.HexabinningWorkflow
import com.nextgis.mobile.mapsafe.service.MapSafeGeoJsonWorkflow
import com.nextgis.mobile.mapsafe.service.MapSafeSampleDataWorkflow
import com.nextgis.mobile.mapsafe.test.MapSafeTestDashboardActivity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicLong

@RunWith(AndroidJUnit4::class)
@LargeTest
class MapSafeTier1WorkflowDeviceTest {

    @Test
    fun completeDataWorkflowCreatesRealLayersProtectsVerifiesDecryptsAndDisplays() {
        val context = ApplicationProvider.getApplicationContext<MainApplication>()
        val dashboard = ActivityScenario.launch(MapSafeTestDashboardActivity::class.java)

        fun show(status: String, stage: String, detail: String) {
            dashboard.onActivity { activity ->
                MapSafeDeviceTestSupport.dashboard(activity, status, stage, detail)
            }
            if (status == "PASS") MapSafeDeviceTestSupport.production(stage, detail)
            else MapSafeDeviceTestSupport.simulated(stage, detail)
        }

        show("PASS", "START", "Tier 1 workflow is running against ${context.packageName}")
        val sample = MapSafeSampleDataWorkflow.createSampleLayer(context, context)
        assertEquals(30, sample.inserted)
        assertEquals(0, sample.failed)
        show("PASS", "LOAD", "created ${sample.layerName} with ${sample.inserted} points")

        val donut = DonutMaskingWorkflow.createMaskedLayer(
            context = context,
            app = context,
            sourceLayer = sample.layer,
            minDistanceMetres = 100.0,
            maxDistanceMetres = 2_000.0
        )
        assertEquals(sample.inserted, donut.maskedPoints)
        assertTrue(donut.spruillMeasure.privacyRatingPercent in 0.0..100.0)
        show(
            "PASS",
            "DONUT + SPRUILL",
            "${donut.maskedPoints} points; privacy=" +
                "${format(donut.spruillMeasure.privacyRatingPercent)}/100"
        )

        val hexbin = HexabinningWorkflow.createHexbinLayer(
            context = context,
            app = context,
            sourceLayer = sample.layer,
            resolution = 8
        )
        assertEquals(sample.inserted, hexbin.sourcePoints)
        assertTrue(hexbin.hexagons > 0)
        show(
            "PASS",
            "HEXBIN",
            "${hexbin.sourcePoints} points became ${hexbin.hexagons} cells using ${hexbin.engine.displayName}"
        )

        val maskedLayer = requireNotNull(context.map.getLayerByName(donut.outputLayerName))
            as com.nextgis.maplib.map.VectorLayer
        val workDirectory = File(context.cacheDir, "mapsafe/device-tier1/${System.nanoTime()}")
        val exported = MapSafeGeoJsonWorkflow.exportLayer(maskedLayer, workDirectory)
        assertEquals(donut.maskedPoints, exported.featureCount)
        show("PASS", "EXPORT", "${exported.featureCount} masked features exported as WGS84 GeoJSON")

        val hexbinLayer = requireNotNull(context.map.getLayerByName(hexbin.outputLayerName))
            as com.nextgis.maplib.map.VectorLayer
        val exportedHexbin = MapSafeGeoJsonWorkflow.exportLayer(hexbinLayer, workDirectory)
        assertEquals(hexbin.hexagons, exportedHexbin.featureCount)
        show(
            "PASS",
            "EXPORT",
            "${exportedHexbin.featureCount} hexagons exported as WGS84 GeoJSON without losing CRS"
        )

        val local = OpenPgpKeyGenerator.generate(
            "Tier 1 Local <local@example.test>",
            PASSPHRASE.copyOf(),
            rsaBits = 2048
        )
        val remote = OpenPgpKeyGenerator.generate(
            "Tier 1 Recipient <recipient@example.test>",
            REMOTE_PASSPHRASE.copyOf(),
            rsaBits = 2048
        )
        val keyRepository = OpenPgpKeyRepository(context)
        keyRepository.saveLocalIdentity(local)
        assertEquals(local.info.fingerprint, keyRepository.localIdentityInfo()?.fingerprint)
        assertNotNull(keyRepository.loadLocalSecretKeyRing())
        show("PASS", "PRIVATE KEY", "local secret key survived Android Keystore wrapping and reload")

        val exchange = PublicKeyExchangeRepository(context)
        val observation = PublicKeyObservation(
            identity = PublicKeyDirectoryIdentity(
                serverUrl = "https://nextgis.test.invalid",
                accountName = "tier1-test",
                groupId = 77,
                userId = 88
            ),
            displayName = remote.info.displayName,
            fingerprint = remote.info.fingerprint,
            keyVersion = 1,
            bucketId = 99,
            publishedAt = "2026-08-09T00:00:00Z"
        )
        val discovered = exchange.observe(
            observation,
            OpenPgpKeyCodec.encodePublicKeyRing(remote.publicKeyRing),
            System.currentTimeMillis()
        )
        assertEquals(PublicKeyTrustState.DISCOVERED, discovered.trustState)
        assertTrue(remote.info.fingerprint in exchange.nonSelectableFingerprints())
        val accepted = exchange.accept(discovered.recordId, keyRepository, System.currentTimeMillis())
        assertEquals(PublicKeyTrustState.ACCEPTED, accepted.trustState)
        assertFalse(remote.info.fingerprint in exchange.nonSelectableFingerprints())
        show(
            "SIMULATED",
            "NEXTGIS DIRECTORY",
            "discovered, fingerprint-checked, explicitly accepted, and selected a controlled recipient key"
        )

        val encryptedOutput = ByteArrayOutputStream()
        exported.file.inputStream().use { input ->
            OpenPgpEngine.encrypt(
                input = input,
                output = encryptedOutput,
                originalFileName = exported.fileName,
                recipients = listOf(local.publicKeyRing, remote.publicKeyRing),
                signingKeyRing = local.secretKeyRing,
                signingPassphrase = PASSPHRASE.copyOf()
            )
        }
        val encrypted = encryptedOutput.toByteArray()
        assertTrue(encrypted.isNotEmpty())
        show("PASS", "ENCRYPT", "one signed AES-256-GCM payload protected for two recipients")

        val ledger = InMemoryDeviceLedger()
        val receipt = ledger.notarise(encrypted)
        assertTrue(ledger.verify(receipt, encrypted))
        show("SIMULATED", "NOTARISE + VERIFY", "${receipt.transactionId}; SHA-256 hash matched")
        val changed = encrypted.copyOf().apply {
            val index = size / 2
            this[index] = (this[index].toInt() xor 1).toByte()
        }
        assertFalse(ledger.verify(receipt, changed))
        show("PASS", "TAMPER", "one-byte change failed hash verification")

        val decryptedOutput = ByteArrayOutputStream()
        val decryptResult = OpenPgpEngine.decrypt(
            input = ByteArrayInputStream(encrypted),
            output = decryptedOutput,
            secretKeyRings = listOf(requireNotNull(keyRepository.loadLocalSecretKeyRing())),
            passphrase = PASSPHRASE.copyOf(),
            verificationKeyRings = keyRepository.listPublicKeyRings()
        )
        assertEquals(OpenPgpSignatureStatus.VALID, decryptResult.signatureStatus)
        assertArrayEquals(exported.file.readBytes(), decryptedOutput.toByteArray())
        show("PASS", "DECRYPT", "integrity passed, signature valid, and original GeoJSON bytes recovered")

        val decryptedFile = File(workDirectory, "decrypted-${exported.fileName}").apply {
            writeBytes(decryptedOutput.toByteArray())
        }
        val imported = MapSafeGeoJsonWorkflow.importLayer(context, decryptedFile, exported.fileName)
        assertEquals(exported.featureCount, imported.featureCount)
        assertTrue(imported.extent.isInit)
        assertNotNull(context.map.getLayerByName(imported.layerName))
        show("PASS", "IMPORT", "created real layer ${imported.layerName} with ${imported.featureCount} features")
        MapSafeDeviceTestSupport.screenshot(context, "tier1-workflow-dashboard")
        dashboard.close()

        MapSafeDeviceTestSupport.prepareMainActivity(context)
        val mapIntent = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_MAPSAFE_LAYER_IMPORTED)
            .putExtra(MainActivity.EXTRA_MAPSAFE_LAYER_NAME, imported.layerName)
            .putExtra(MainActivity.EXTRA_MAPSAFE_FEATURE_COUNT, imported.featureCount)
            .putExtra(MainActivity.EXTRA_MAPSAFE_MIN_X, imported.extent.minX.toDouble())
            .putExtra(MainActivity.EXTRA_MAPSAFE_MAX_X, imported.extent.maxX.toDouble())
            .putExtra(MainActivity.EXTRA_MAPSAFE_MIN_Y, imported.extent.minY.toDouble())
            .putExtra(MainActivity.EXTRA_MAPSAFE_MAX_Y, imported.extent.maxY.toDouble())
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { mapScenario ->
            context.startActivity(
                mapIntent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            )
            MapSafeDeviceTestSupport.waitUntil("the decrypted layer to be selected") {
                var selected = false
                mapScenario.onActivity { activity ->
                    selected = activity.mapFragment?.selectedLayer?.name == imported.layerName
                }
                selected
            }
            MapSafeDeviceTestSupport.production(
                "VIEW",
                "decrypted layer selected in MainActivity and map zoom requested for its extent"
            )
            MapSafeDeviceTestSupport.screenshot(context, "tier1-decrypted-layer-map")
        }
    }

    private fun format(value: Double): String = String.format("%.2f", value)

    private class InMemoryDeviceLedger {
        data class Receipt(val transactionId: String, val sha256: String)
        private val entries = mutableMapOf<String, String>()

        fun notarise(artifact: ByteArray): Receipt {
            val transaction = "device-ledger-${NEXT_ID.incrementAndGet()}"
            return Receipt(transaction, HashUtils.sha256(artifact)).also {
                entries[transaction] = it.sha256
            }
        }

        fun verify(receipt: Receipt, artifact: ByteArray): Boolean =
            entries[receipt.transactionId] == receipt.sha256 &&
                receipt.sha256 == HashUtils.sha256(artifact)

        companion object {
            private val NEXT_ID = AtomicLong()
        }
    }

    companion object {
        private val PASSPHRASE = "tier one local recovery passphrase".toCharArray()
        private val REMOTE_PASSPHRASE = "tier one remote recovery passphrase".toCharArray()
    }
}
