package com.nextgis.mobile.mapsafe.scenario

import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpContentProtection
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpEngine
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpKeyGenerator
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpSignatureStatus
import com.nextgis.mobile.mapsafe.safeguard.anonymise.DonutMasking
import com.nextgis.mobile.mapsafe.safeguard.anonymise.Hexabinning
import com.nextgis.mobile.mapsafe.safeguard.anonymise.SpruillMeasure
import com.nextgis.mobile.mapsafe.service.HashUtils
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * Console-friendly scenarios that compose MapSafe's implemented core operations.
 *
 * Blockchain interaction is deliberately represented by [InMemoryTestLedger] until the
 * production notarisation client exists. Android layer creation and visual map display
 * belong in connected-device tests and are reported here only as a verified handoff.
 */
class MapSafeWorkflowScenarioTest {

    @Test
    fun combinedModeDonutEncryptNotariseVerifyDecryptAndViewHandoff() {
        scenario("COMBINED / DONUT")
        val originals = sampleCoordinates()
        val masked = originals.map { original ->
            val result = DonutMasking.maskPoint(
                longitude = original.longitude,
                latitude = original.latitude,
                minDistanceMetres = 100.0,
                maxDistanceMetres = 350.0
            )
            assertTrue(result.distanceMetres in 100.0..350.0)
            SpruillMeasure.Coordinate(result.longitude, result.latitude)
        }
        passed("ANONYMISE", "${masked.size} points stayed inside the 100-350 m donut")

        val spruill = SpruillMeasure.calculate(originals, masked)
        assertEquals(masked.size, spruill.evaluatedPoints)
        assertTrue(spruill.privacyRatingPercent in 0.0..100.0)
        passed(
            "SPRUILL",
            "privacy=${format(spruill.privacyRatingPercent)}/100, " +
                "risk=${format(spruill.disclosureRiskPercent)}%, " +
                "parent-nearest=${spruill.parentNearestCount}/${spruill.evaluatedPoints}"
        )

        val payload = geoJsonPayload(masked)
        val protected = encrypt(payload, signed = true)
        assertFalse(protected.contentEquals(payload))
        passed("ENCRYPT", "AES-256-GCM package created for the scenario recipient")

        val ledger = InMemoryTestLedger()
        val receipt = ledger.notarise("masked-points.geojson.pgp", protected)
        simulated("NOTARISE", "${receipt.transactionId}, SHA-256=${receipt.sha256.take(16)}...")
        assertTrue(ledger.verify(receipt, protected))
        simulated("BLOCKCHAIN VERIFY", "stored and calculated hashes match")

        val recovered = decrypt(protected, expectedSignature = OpenPgpSignatureStatus.VALID)
        assertArrayEquals(payload, recovered)
        passed("DECRYPT", "plaintext and valid sender signature recovered")

        assertEquals(masked.size, featureCount(recovered))
        passed("VIEW HANDOFF", "valid GeoJSON contains ${masked.size} features for Android import")
    }

    @Test
    fun combinedModeHexbinEncryptNotariseVerifyDecryptAndViewHandoff() {
        scenario("COMBINED / HEXBIN")
        val cells = sampleCoordinates()
            .groupingBy { Hexabinning.pointToCell(it.latitude, it.longitude, resolution = 8) }
            .eachCount()
        assertEquals(sampleCoordinates().size, cells.values.sum())
        cells.keys.forEach { cellId ->
            assertEquals(6, Hexabinning.cellBoundaryToLatLon(cellId).size)
        }
        passed(
            "ANONYMISE",
            "${sampleCoordinates().size} points aggregated into ${cells.size} cells using " +
                Hexabinning.engine.displayName
        )

        val payload = hexbinPayload(cells)
        val protected = encrypt(payload, signed = true)
        passed("ENCRYPT", "signed AES-256-GCM hexbin package created")

        val ledger = InMemoryTestLedger()
        val receipt = ledger.notarise("hexbins.geojson.pgp", protected)
        simulated("NOTARISE", "${receipt.transactionId}, SHA-256=${receipt.sha256.take(16)}...")
        assertTrue(ledger.verify(receipt, protected))
        simulated("BLOCKCHAIN VERIFY", "stored and calculated hashes match")

        val recovered = decrypt(protected, expectedSignature = OpenPgpSignatureStatus.VALID)
        assertArrayEquals(payload, recovered)
        passed("DECRYPT", "hexbin payload and valid signature recovered")
        assertTrue(recovered.decodeToString().contains("point_count"))
        passed("VIEW HANDOFF", "hexbin GeoJSON is ready for Android import")
    }

    @Test
    fun individualModeRunsEverySafeguardCombination() {
        scenario("INDIVIDUAL MODE MATRIX")
        val combinations = listOf(
            Combination("A", anonymise = true, encrypt = false, notarise = false),
            Combination("E", anonymise = false, encrypt = true, notarise = false),
            Combination("N", anonymise = false, encrypt = false, notarise = true),
            Combination("A+E", anonymise = true, encrypt = true, notarise = false),
            Combination("A+N", anonymise = true, encrypt = false, notarise = true),
            Combination("E+N", anonymise = false, encrypt = true, notarise = true),
            Combination("A+E+N", anonymise = true, encrypt = true, notarise = true)
        )

        combinations.forEach { combination ->
            val source = "field observation for ${combination.name}".toByteArray()
            var artifact = source
            if (combination.anonymise) {
                val moved = DonutMasking.maskPoint(178.4419, -18.1416, 100.0, 200.0)
                assertTrue(moved.distanceMetres in 100.0..200.0)
                artifact = "${artifact.decodeToString()}|masked=${moved.longitude},${moved.latitude}"
                    .toByteArray()
            }
            val preEncryptionArtifact = artifact
            if (combination.encrypt) {
                artifact = encrypt(artifact, signed = false)
                assertFalse(artifact.contentEquals(preEncryptionArtifact))
            }
            if (combination.notarise) {
                val ledger = InMemoryTestLedger()
                val receipt = ledger.notarise("${combination.name}.mapsafe", artifact)
                assertTrue(ledger.verify(receipt, artifact))
                simulated(
                    "INDIVIDUAL ${combination.name} / NOTARISE",
                    "in-memory ledger stored and verified the production SHA-256 digest"
                )
            }
            if (combination.encrypt) {
                artifact = decrypt(artifact, expectedSignature = OpenPgpSignatureStatus.NOT_SIGNED)
                assertArrayEquals(preEncryptionArtifact, artifact)
            }
            passed(
                "INDIVIDUAL ${combination.name}",
                "selected production stages and any labelled simulation completed in safeguard order"
            )
        }
    }

    @Test
    fun changedProtectedArtifactFailsVerificationAndDecryption() {
        scenario("TAMPER DETECTION")
        val protected = encrypt("sensitive coordinates".toByteArray(), signed = true)
        val ledger = InMemoryTestLedger()
        val receipt = ledger.notarise("tamper-test.pgp", protected)
        val changed = protected.copyOf().apply {
            val index = size / 2
            this[index] = (this[index].toInt() xor 0x01).toByte()
        }

        assertFalse(ledger.verify(receipt, changed))
        simulated("BLOCKCHAIN VERIFY", "one-byte change correctly produced a hash mismatch")
        assertTrue(runCatching { decrypt(changed, OpenPgpSignatureStatus.VALID) }.isFailure)
        passed("DECRYPT", "OpenPGP integrity protection rejected the changed package")
    }

    private fun encrypt(payload: ByteArray, signed: Boolean): ByteArray {
        val output = ByteArrayOutputStream()
        val result = OpenPgpEngine.encrypt(
            input = ByteArrayInputStream(payload),
            output = output,
            originalFileName = "scenario.geojson",
            recipients = listOf(identity.publicKeyRing),
            signingKeyRing = identity.secretKeyRing.takeIf { signed },
            signingPassphrase = PASSPHRASE.copyOf().takeIf { signed }
        )
        assertEquals(OpenPgpContentProtection.AES_256_GCM, result.contentProtection)
        return output.toByteArray()
    }

    private fun decrypt(
        protected: ByteArray,
        expectedSignature: OpenPgpSignatureStatus
    ): ByteArray {
        val output = ByteArrayOutputStream()
        val result = OpenPgpEngine.decrypt(
            input = ByteArrayInputStream(protected),
            output = output,
            secretKeyRings = listOf(identity.secretKeyRing),
            passphrase = PASSPHRASE.copyOf(),
            verificationKeyRings = listOf(identity.publicKeyRing)
        )
        assertTrue(result.integrityProtected)
        assertEquals(expectedSignature, result.signatureStatus)
        return output.toByteArray()
    }

    private fun sampleCoordinates(): List<SpruillMeasure.Coordinate> = listOf(
        SpruillMeasure.Coordinate(178.4419, -18.1416),
        SpruillMeasure.Coordinate(178.4425, -18.1421),
        SpruillMeasure.Coordinate(178.4590, -18.1250),
        SpruillMeasure.Coordinate(178.4600, -18.1260)
    )

    private fun geoJsonPayload(points: List<SpruillMeasure.Coordinate>): ByteArray {
        val features = points.mapIndexed { index, point ->
            """{"type":"Feature","properties":{"site_id":${index + 1}},"geometry":{"type":"Point","coordinates":[${point.longitude},${point.latitude}]}}"""
        }
        return """{"type":"FeatureCollection","features":[${features.joinToString(",")}]}"""
            .toByteArray()
    }

    private fun hexbinPayload(cells: Map<String, Int>): ByteArray {
        val features = cells.entries.map { (cellId, count) ->
            val boundary = Hexabinning.cellBoundaryToLatLon(cellId)
            val closed = boundary + boundary.first()
            val coordinates = closed.joinToString(",") { (latitude, longitude) ->
                "[$longitude,$latitude]"
            }
            """{"type":"Feature","properties":{"cell_id":"$cellId","point_count":$count},"geometry":{"type":"Polygon","coordinates":[[$coordinates]]}}"""
        }
        return """{"type":"FeatureCollection","features":[${features.joinToString(",")}]}"""
            .toByteArray()
    }

    private fun featureCount(payload: ByteArray): Int {
        return Regex("\\\"type\\\":\\\"Feature\\\"").findAll(payload.decodeToString()).count()
    }

    private fun scenario(name: String) = println("\n[MAPSAFE][SCENARIO] $name")
    private fun passed(stage: String, detail: String) = println("[MAPSAFE][PASS][$stage] $detail")
    private fun simulated(stage: String, detail: String) =
        println("[MAPSAFE][SIMULATED][$stage] $detail")

    private fun format(value: Double): String = String.format("%.2f", value)

    private data class Combination(
        val name: String,
        val anonymise: Boolean,
        val encrypt: Boolean,
        val notarise: Boolean
    )

    private class InMemoryTestLedger {
        data class Receipt(
            val transactionId: String,
            val fileName: String,
            val sha256: String
        )

        private val entries = mutableMapOf<String, Receipt>()

        fun notarise(fileName: String, artifact: ByteArray): Receipt {
            val transactionId = "test-ledger-${NEXT_TRANSACTION.incrementAndGet()}"
            return Receipt(transactionId, fileName, HashUtils.sha256(artifact)).also {
                entries[transactionId] = it
            }
        }

        fun verify(receipt: Receipt, artifact: ByteArray): Boolean {
            val stored = entries[receipt.transactionId] ?: return false
            return stored == receipt && stored.sha256 == HashUtils.sha256(artifact)
        }

        companion object {
            private val NEXT_TRANSACTION = AtomicLong()
        }
    }

    companion object {
        private val PASSPHRASE = "mapsafe scenario recovery passphrase".toCharArray()
        private val identity by lazy {
            OpenPgpKeyGenerator.generate(
                userId = "MapSafe Scenario <scenario@example.test>",
                passphrase = PASSPHRASE.copyOf(),
                rsaBits = 2048
            )
        }
    }
}
