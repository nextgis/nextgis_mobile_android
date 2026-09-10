package com.nextgis.mobile.mapsafe

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.nextgis.maplib.map.VectorLayer
import com.nextgis.maplibui.mapui.VectorLayerUI
import com.nextgis.mobile.BuildConfig
import com.nextgis.mobile.MainApplication
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpEngine
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpKeyGenerator
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpSignatureStatus
import com.nextgis.mobile.mapsafe.safeguard.anonymise.SpruillMeasure
import com.nextgis.mobile.mapsafe.service.DonutMaskingWorkflow
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.random.Random

/**
 * Reusable Android performance protocol for the MapSafe paper.
 *
 * The benchmark deliberately excludes UI interaction, initial dataset import, key
 * generation, recipient discovery, and network access. It measures the five operations
 * reported in the paper table:
 *
 * 1. the coordinate-only halo-masking core;
 * 2. that same core plus Spruill assessment;
 * 3. the complete production masking backend from source-layer reading through saving;
 * 4. signed AES-256-GCM OpenPGP encryption for one RSA-3072 recipient; and
 * 5. OpenPGP private-key unlock, decryption, integrity checking, and signature verification.
 *
 * The five point counts are generated with typical and rich multi-attribute profiles,
 * producing different file sizes at the same feature count. Attachments are excluded
 * because selected-layer MapSafe packages currently contain GeoJSON geometry and
 * attributes only. Paper mode remains physical-device-only by default; the saved runner
 * can explicitly permit a quick emulator run whose metadata is marked ineligible for
 * publication.
 *
 * Results, reusable GeoJSON fixtures, raw and summary CSV, metadata, and a LaTeX table
 * are written under the app's external files directory for the PowerShell runner to pull.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class MapSafePerformanceBenchmarkTest {

    @Volatile
    private var benchmarkSink = 0L

    private var warmUpRuns = PAPER_WARM_UP_RUNS
    private var measuredRuns = PAPER_MEASURED_RUNS
    private var protocolName = PROTOCOL_PAPER
    private var runningOnEmulator = false
    private var datasetSource = DATASET_SOURCE_GENERATED

    @Test
    fun recordMaskingEncryptionAndDecryptionForRealisticFieldDatasets() {
        val context = ApplicationProvider.getApplicationContext<MainApplication>()
        configureProtocol()
        runningOnEmulator = isProbablyEmulator()
        val allowEmulator = InstrumentationRegistry.getArguments()
            .getString(ARG_ALLOW_EMULATOR)
            ?.toBooleanStrictOrNull() == true
        if (!allowEmulator) {
            assertFalse(
                "Paper performance measurements must run on a physical Android device, not an emulator. " +
                    "Use the saved runner's -AllowEmulator option only for explicitly preliminary results.",
                runningOnEmulator
            )
        }
        requireAcceptableThermalState(context)
        val environmentAtStart = captureEnvironment(context)
        cleanupStaleBenchmarkLayers(context)

        val datasets = loadDatasets()
        assertEquals(POINT_COUNTS.size * AttributeProfile.entries.size, datasets.size)
        assertEquals(POINT_COUNTS, datasets.filter { it.profile == AttributeProfile.TYPICAL }.map(Dataset::pointCount))
        assertEquals(POINT_COUNTS, datasets.filter { it.profile == AttributeProfile.RICH }.map(Dataset::pointCount))

        val passphrase = "MapSafe automated benchmark recovery passphrase".toCharArray()
        val identity = try {
            OpenPgpKeyGenerator.generate(
                userId = "MapSafe Automated Benchmark <benchmark@example.test>",
                passphrase = passphrase,
                rsaBits = OpenPgpKeyGenerator.DEFAULT_RSA_BITS
            )
        } catch (error: Throwable) {
            passphrase.fill('\u0000')
            throw error
        }

        try {
            val encryptedBaselines = datasets.associate { dataset ->
                val encrypted = encrypt(dataset.geoJson, identity, passphrase)
                val decrypted = decrypt(encrypted, identity, passphrase)
                assertArrayEquals(dataset.geoJson, decrypted.plainText)
                assertEquals(OpenPgpSignatureStatus.VALID, decrypted.signatureStatus)
                dataset.id to encrypted
            }

            val runId = utcTimestamp()
            val outputDirectory = File(
                context.getExternalFilesDir(null) ?: context.filesDir,
                "mapsafe-benchmark/$runId"
            )
            check(outputDirectory.mkdirs() || outputDirectory.isDirectory) {
                "Could not create benchmark output directory ${outputDirectory.absolutePath}."
            }
            writeDatasets(File(outputDirectory, "datasets"), datasets)
            writeDatasetManifest(File(outputDirectory, "dataset-manifest.csv"), datasets, encryptedBaselines)

            val rawRows = mutableListOf<RawRow>()
            val maskingPhaseRows = mutableListOf<MaskingPhaseRow>()
            val summaries = mutableListOf<SummaryRow>()
            val cells = datasets.flatMap { dataset ->
                CellOperation.entries.map { operation -> Cell(dataset, operation) }
            }.shuffled(Random(CELL_ORDER_SEED))

            cells.forEachIndexed { cellIndex, cell ->
                requireAcceptableThermalState(context)
                Log.i(
                    LOG_TAG,
                    "CELL ${cellIndex + 1}/${cells.size}: ${cell.dataset.id}, " +
                        cell.operation.csvName
                )
                val encryptedBytes = requireNotNull(encryptedBaselines[cell.dataset.id]).size
                when (cell.operation) {
                    CellOperation.MASK_WORKFLOW -> {
                        val runs = measureMaskWorkflow(context, cell.dataset)
                        maskingPhaseRows += runs.mapIndexed { index, run ->
                            MaskingPhaseRow.from(cell.dataset, index + 1, run)
                        }
                        listOf(
                            Operation.MASK_CORE to runs.map { it.result.maskingDurationNanos }.toLongArray(),
                            Operation.MASK_WITH_SPRUILL to runs.map {
                                it.result.maskingDurationNanos + it.result.spruillDurationNanos
                            }.toLongArray(),
                            Operation.MASK_WORKFLOW_TOTAL to runs.map {
                                it.result.workflowDurationNanos
                            }.toLongArray()
                        ).forEach { (operation, durations) ->
                            appendMeasurements(
                                rawRows = rawRows,
                                summaries = summaries,
                                dataset = cell.dataset,
                                encryptedBytes = encryptedBytes,
                                operation = operation,
                                durationsNanos = durations
                            )
                        }
                    }
                    CellOperation.ENCRYPT, CellOperation.DECRYPT -> {
                        val operation = when (cell.operation) {
                            CellOperation.ENCRYPT -> Operation.ENCRYPT
                            CellOperation.DECRYPT -> Operation.DECRYPT
                            else -> error("Unexpected masking operation.")
                        }
                        val durations = measureCryptoCell(
                            cell = cell,
                            identity = identity,
                            passphrase = passphrase,
                            encryptedBaseline = requireNotNull(encryptedBaselines[cell.dataset.id])
                        )
                        appendMeasurements(
                            rawRows = rawRows,
                            summaries = summaries,
                            dataset = cell.dataset,
                            encryptedBytes = encryptedBytes,
                            operation = operation,
                            durationsNanos = durations
                        )
                    }
                }
                // Checkpoint after every complete cell. Long physical-phone runs can
                // outlive an ADB transport; these files remain pullable after reconnecting.
                writeRawCsv(File(outputDirectory, "raw-measurements.csv"), rawRows)
                writeMaskingPhaseCsv(
                    File(outputDirectory, "masking-phase-measurements.csv"),
                    maskingPhaseRows
                )
                writeSummaryCsv(File(outputDirectory, "summary.csv"), summaries)
                writeProgress(
                    output = File(outputDirectory, "run-progress.json"),
                    completedCells = cellIndex + 1,
                    totalCells = cells.size,
                    complete = false
                )
            }

            requireAcceptableThermalState(context)
            writeRawCsv(File(outputDirectory, "raw-measurements.csv"), rawRows)
            writeMaskingPhaseCsv(
                File(outputDirectory, "masking-phase-measurements.csv"),
                maskingPhaseRows
            )
            writeSummaryCsv(File(outputDirectory, "summary.csv"), summaries)
            writeLatexTable(File(outputDirectory, "paper-table.tex"), datasets, summaries)
            writeMetadata(
                output = File(outputDirectory, "metadata.json"),
                context = context,
                runId = runId,
                datasets = datasets,
                summaries = summaries,
                environmentAtStart = environmentAtStart
            )
            writeProgress(
                output = File(outputDirectory, "run-progress.json"),
                completedCells = cells.size,
                totalCells = cells.size,
                complete = true
            )

            summaries.sortedWith(
                compareBy<SummaryRow> { it.pointCount }
                    .thenBy { it.attributeProfile }
                    .thenBy { it.operation.tableOrder }
            ).forEach { summary ->
                Log.i(LOG_TAG, "RESULT,${summary.toCsv()}")
            }
            Log.i(LOG_TAG, "OUTPUT,${outputDirectory.absolutePath}")
            assertTrue("The benchmark sink was not updated.", benchmarkSink != 0L)
        } finally {
            passphrase.fill('\u0000')
        }
    }

    private fun measureCryptoCell(
        cell: Cell,
        identity: com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpKeyMaterial,
        passphrase: CharArray,
        encryptedBaseline: ByteArray
    ): LongArray {
        repeat(warmUpRuns) {
            consumeCrypto(
                cell,
                executeCrypto(cell, identity, passphrase, encryptedBaseline)
            )
        }

        return LongArray(measuredRuns) {
            val startedAt = SystemClock.elapsedRealtimeNanos()
            val outcome = executeCrypto(cell, identity, passphrase, encryptedBaseline)
            val elapsed = SystemClock.elapsedRealtimeNanos() - startedAt
            consumeCrypto(cell, outcome)
            elapsed
        }
    }

    private fun executeCrypto(
        cell: Cell,
        identity: com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpKeyMaterial,
        passphrase: CharArray,
        encryptedBaseline: ByteArray
    ): Any = when (cell.operation) {
        CellOperation.ENCRYPT -> encrypt(cell.dataset.geoJson, identity, passphrase)
        CellOperation.DECRYPT -> decrypt(encryptedBaseline, identity, passphrase)
        CellOperation.MASK_WORKFLOW -> error("Masking uses the production workflow measurement path.")
    }

    private fun consumeCrypto(cell: Cell, outcome: Any) {
        benchmarkSink = benchmarkSink xor when (cell.operation) {
            CellOperation.ENCRYPT -> {
                val encrypted = outcome as ByteArray
                assertTrue(encrypted.isNotEmpty())
                encrypted.size.toLong() xor encrypted.last().toLong()
            }
            CellOperation.DECRYPT -> {
                val decrypted = outcome as DecryptOutcome
                assertArrayEquals(cell.dataset.geoJson, decrypted.plainText)
                assertEquals(OpenPgpSignatureStatus.VALID, decrypted.signatureStatus)
                    decrypted.plainText.size.toLong() xor decrypted.signatureStatus.ordinal.toLong()
            }
            CellOperation.MASK_WORKFLOW -> error("Masking uses its own result validator.")
        }
    }

    private fun measureMaskWorkflow(
        context: MainApplication,
        dataset: Dataset
    ): List<MaskingRun> {
        val source = createBenchmarkSourceLayer(context, dataset)
        try {
            repeat(warmUpRuns) {
                val run = executeMaskWorkflow(context, source, dataset)
                try {
                    consumeMaskWorkflow(source, dataset, run)
                } finally {
                    removeBenchmarkLayer(context, run.result.outputLayer)
                }
            }

            return List(measuredRuns) {
                val run = executeMaskWorkflow(context, source, dataset)
                try {
                    consumeMaskWorkflow(source, dataset, run)
                } finally {
                    removeBenchmarkLayer(context, run.result.outputLayer)
                }
                run
            }
        } finally {
            removeBenchmarkLayer(context, source)
        }
    }

    private fun executeMaskWorkflow(
        context: MainApplication,
        source: VectorLayer,
        dataset: Dataset
    ): MaskingRun {
        val outerStartedAt = SystemClock.elapsedRealtimeNanos()
        val result = DonutMaskingWorkflow.createMaskedLayer(
            context = context,
            app = context,
            sourceLayer = source,
            minDistanceMetres = MASK_MIN_METRES,
            maxDistanceMetres = MASK_MAX_METRES
        )
        val outerElapsedNanos = SystemClock.elapsedRealtimeNanos() - outerStartedAt
        return MaskingRun(dataset.id, result, outerElapsedNanos)
    }

    private fun consumeMaskWorkflow(
        source: VectorLayer,
        dataset: Dataset,
        run: MaskingRun
    ) {
        val result = run.result
        assertEquals(dataset.id, run.datasetId)
        assertEquals(dataset.pointCount, result.totalPoints)
        assertEquals(dataset.pointCount, result.maskedPoints)
        assertEquals(dataset.pointCount, result.inserted)
        assertEquals(0, result.failed)
        assertEquals(dataset.pointCount, result.spruillMeasure.evaluatedPoints)
        assertEquals(dataset.pointCount, result.outputLayer.query(null).size)
        assertTrue(result.spruillMeasure.privacyRatingPercent in 0.0..100.0)
        assertTrue(result.averageDistanceMetres in MASK_MIN_METRES..MASK_MAX_METRES)
        assertTrue(result.outputLayer.fields.size >= source.fields.size + MASK_METADATA_FIELD_COUNT)

        val measuredPhases = result.sourceReadDurationNanos +
            result.maskingDurationNanos +
            result.spruillDurationNanos +
            result.featureConstructionDurationNanos +
            result.outputWriteDurationNanos
        assertTrue("Every masking phase must record a positive duration.", measuredPhases > 0L)
        assertTrue(
            "The complete masking workflow must contain all measured internal phases.",
            result.workflowDurationNanos >= measuredPhases
        )
        assertTrue(
            "The independent outer clock must contain the production workflow timer.",
            run.outerElapsedNanos >= result.workflowDurationNanos
        )
        benchmarkSink = benchmarkSink xor
            result.inserted.toLong() xor
            result.spruillMeasure.parentNearestCount.toLong() xor
            result.workflowDurationNanos
    }

    private fun createBenchmarkSourceLayer(
        context: MainApplication,
        dataset: Dataset
    ): VectorLayer {
        val stagingDirectory = File(context.cacheDir, "mapsafe/benchmark-source").apply {
            check(mkdirs() || isDirectory) { "Could not create benchmark source staging directory." }
        }
        val sourceFile = File(
            stagingDirectory,
            "${dataset.id}-${System.nanoTime()}.geojson"
        ).apply { writeBytes(dataset.geoJson) }
        val map = context.map
        val layer = VectorLayerUI(context, map.createLayerStorage()).apply {
            name = "$BENCHMARK_LAYER_PREFIX${dataset.id} ${System.nanoTime()}"
            isVisible = false
        }
        var addedToMap = false
        try {
            layer.createFromGeoJson(sourceFile, null)
            check(layer.isValid) { "Benchmark source ${dataset.id} is not a valid vector layer." }
            check(layer.query(null).size == dataset.pointCount) {
                "Benchmark source ${dataset.id} did not import all ${dataset.pointCount} points."
            }
            map.addLayer(layer)
            addedToMap = true
            layer.notifyLayerChanged()
            map.save()
            return layer
        } catch (error: Throwable) {
            if (addedToMap) runCatching { map.removeLayer(layer); map.save() }
            runCatching { layer.delete(true) }
            throw error
        } finally {
            sourceFile.delete()
        }
    }

    private fun removeBenchmarkLayer(context: MainApplication, layer: VectorLayer) {
        context.map.removeLayer(layer)
        context.map.save()
        layer.delete(true)
    }

    private fun cleanupStaleBenchmarkLayers(context: MainApplication) {
        val staleLayers = context.map.layers
            .filterIsInstance<VectorLayer>()
            .filter { it.name.startsWith(BENCHMARK_LAYER_PREFIX) }
        staleLayers.forEach { layer ->
            context.map.removeLayer(layer)
            layer.delete(true)
        }
        if (staleLayers.isNotEmpty()) context.map.save()
    }

    private fun encrypt(
        plainText: ByteArray,
        identity: com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpKeyMaterial,
        passphrase: CharArray
    ): ByteArray {
        val encrypted = ByteArrayOutputStream()
        OpenPgpEngine.encrypt(
            input = ByteArrayInputStream(plainText),
            output = encrypted,
            originalFileName = "mapsafe-field-points.geojson",
            recipients = listOf(identity.publicKeyRing),
            signingKeyRing = identity.secretKeyRing,
            signingPassphrase = passphrase
        )
        return encrypted.toByteArray()
    }

    private fun decrypt(
        encrypted: ByteArray,
        identity: com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpKeyMaterial,
        passphrase: CharArray
    ): DecryptOutcome {
        val plainText = ByteArrayOutputStream()
        val result = OpenPgpEngine.decrypt(
            input = ByteArrayInputStream(encrypted),
            output = plainText,
            secretKeyRings = listOf(identity.secretKeyRing),
            passphrase = passphrase,
            verificationKeyRings = listOf(identity.publicKeyRing)
        )
        return DecryptOutcome(plainText.toByteArray(), result.signatureStatus)
    }

    /**
     * Creates deterministic WGS84 GeoJSON resembling the artifact produced by
     * MapSafeGeoJsonWorkflow after a NextGIS point layer is selected.
     *
     * Both profiles contain the same geometry and typed field schema. The rich
     * profile adds longer, non-repeating text values so encryption and decryption
     * can be compared at the same point count but a materially different file size.
     * Attachments are deliberately absent because the current MapSafe exporter does
     * not include NextGIS photos or attachment files in selected-layer packages.
     */
    private fun createDataset(pointCount: Int, profile: AttributeProfile): Dataset {
        val coordinates = ArrayList<SpruillMeasure.Coordinate>(pointCount)
        val estimatedCharacters = pointCount * (760 + profile.notesCharacters + profile.stewardshipCharacters)
        val json = StringBuilder(estimatedCharacters)
        json.append("{\"type\":\"FeatureCollection\",\"name\":\"mapsafe-")
            .append(profile.csvName).append('-').append(pointCount)
            .append("\",\"features\":[")
        repeat(pointCount) { index ->
            val column = index % 40
            val row = index / 40
            val longitude = 178.410000 + column * 0.000850 + (row % 3) * 0.000110
            val latitude = -18.165000 + row * 0.000780 + (column % 5) * 0.000070
            coordinates += SpruillMeasure.Coordinate(longitude, latitude)
            if (index > 0) json.append(',')
            json.append("{\"type\":\"Feature\",\"properties\":{")
                .append("\"site_id\":").append(index + 1).append(',')
                .append("\"site_name\":\"Field observation ").append(index + 1).append("\",")
                .append("\"category\":\"").append(CATEGORIES[index % CATEGORIES.size]).append("\",")
                .append("\"sensitivity\":\"").append(SENSITIVITIES[index % SENSITIVITIES.size]).append("\",")
                .append("\"observer_id\":\"observer-").append(1 + index % 8).append("\",")
                .append("\"sample_code\":\"FJ-").append(20_260_000 + index).append("\",")
                .append("\"status\":\"").append(STATUSES[index % STATUSES.size]).append("\",")
                .append("\"observed_at\":").append(BASE_OBSERVED_AT_MILLIS + index * 60_000L).append(',')
                .append("\"horizontal_accuracy_m\":")
                .append(String.format(Locale.US, "%.2f", 1.5 + (index % 20) * 0.35)).append(',')
                .append("\"measurement_value\":")
                .append(String.format(Locale.US, "%.3f", 4.25 + (index * 13 % 600) / 17.0)).append(',')
                .append("\"measurement_unit\":\"").append(UNITS[index % UNITS.size]).append("\",")
                .append("\"households\":").append(1 + (index * 7) % 80).append(',')
                .append("\"access_method\":\"").append(ACCESS_METHODS[index % ACCESS_METHODS.size]).append("\",")
                .append("\"consent_code\":").append(index % 3).append(',')
                .append("\"collection_round\":").append(1 + index / 250).append(',')
                .append("\"notes\":\"")
                .append(deterministicText(index, profile.notesCharacters, 0x4D_53_4E_01)).append("\",")
                .append("\"stewardship_notes\":\"")
                .append(deterministicText(index, profile.stewardshipCharacters, 0x4D_53_4E_02)).append("\",")
                .append("\"review_comment\":")
                .append(
                    if (index % 7 == 0) "null"
                    else "\"reviewed-${1 + index % 5}\""
                )
                .append("},\"geometry\":{\"type\":\"Point\",\"coordinates\":[")
                .append(String.format(Locale.US, "%.6f", longitude)).append(',')
                .append(String.format(Locale.US, "%.6f", latitude))
                .append("]}}")
        }
        json.append("]}")
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        JSONObject(bytes.toString(Charsets.UTF_8)).getJSONArray("features").let { features ->
            assertEquals(pointCount, features.length())
        }
        return Dataset(
            id = "field-${pointCount}-${profile.csvName}",
            profile = profile,
            pointCount = pointCount,
            coordinates = coordinates,
            geoJson = bytes
        )
    }

    /**
     * Uses exact GeoJSON files staged by the physical-phone runner when requested.
     * The normal benchmark remains self-contained and deterministic when no staging
     * directory is supplied.
     */
    private fun loadDatasets(): List<Dataset> {
        val inputDirectory = InstrumentationRegistry.getArguments()
            .getString(ARG_INPUT_DIRECTORY)
            ?.trim()
            ?.takeIf(String::isNotBlank)
        if (inputDirectory == null) {
            datasetSource = DATASET_SOURCE_GENERATED
            return POINT_COUNTS.flatMap { pointCount ->
                AttributeProfile.entries.map { profile -> createDataset(pointCount, profile) }
            }
        }

        val directory = File(inputDirectory)
        require(directory.isDirectory) {
            "The staged benchmark input directory is unavailable: ${directory.absolutePath}"
        }
        datasetSource = DATASET_SOURCE_STAGED_PHONE
        return POINT_COUNTS.flatMap { pointCount ->
            AttributeProfile.entries.map { profile ->
                loadDataset(
                    file = File(directory, "field-${pointCount}-${profile.csvName}.geojson"),
                    expectedPointCount = pointCount,
                    profile = profile
                )
            }
        }
    }

    private fun loadDataset(
        file: File,
        expectedPointCount: Int,
        profile: AttributeProfile
    ): Dataset {
        require(file.isFile && file.length() > 0L) {
            "The staged benchmark dataset is missing or empty: ${file.absolutePath}"
        }
        val bytes = file.readBytes()
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        require(root.optString("type") == "FeatureCollection") {
            "${file.name} is not a GeoJSON FeatureCollection."
        }
        val features = root.getJSONArray("features")
        require(features.length() == expectedPointCount) {
            "${file.name} contains ${features.length()} points; expected $expectedPointCount."
        }
        val coordinates = ArrayList<SpruillMeasure.Coordinate>(features.length())
        repeat(features.length()) { index ->
            val geometry = features.getJSONObject(index).getJSONObject("geometry")
            require(geometry.optString("type") == "Point") {
                "${file.name} feature ${index + 1} is not a point."
            }
            val coordinate = geometry.getJSONArray("coordinates")
            require(coordinate.length() >= 2) {
                "${file.name} feature ${index + 1} has no longitude/latitude pair."
            }
            coordinates += SpruillMeasure.Coordinate(
                longitude = coordinate.getDouble(0),
                latitude = coordinate.getDouble(1)
            )
        }
        return Dataset(
            id = "field-${expectedPointCount}-${profile.csvName}",
            profile = profile,
            pointCount = expectedPointCount,
            coordinates = coordinates,
            geoJson = bytes
        )
    }

    /** Stable high-entropy ASCII keeps the rich fixture from collapsing unrealistically under ZIP. */
    private fun deterministicText(featureIndex: Int, length: Int, salt: Int): String {
        var state = (CELL_ORDER_SEED xor salt xor featureIndex * 0x45D9F3B).toUInt()
        return buildString(length) {
            repeat(length) {
                state = state * 1_664_525u + 1_013_904_223u
                append(TEXT_ALPHABET[((state shr 16).toInt() and Int.MAX_VALUE) % TEXT_ALPHABET.length])
            }
        }
    }

    private fun appendMeasurements(
        rawRows: MutableList<RawRow>,
        summaries: MutableList<SummaryRow>,
        dataset: Dataset,
        encryptedBytes: Int,
        operation: Operation,
        durationsNanos: LongArray
    ) {
        durationsNanos.forEachIndexed { index, elapsedNanos ->
            rawRows += RawRow(
                datasetId = dataset.id,
                attributeProfile = dataset.profile.csvName,
                pointCount = dataset.pointCount,
                plainTextBytes = dataset.geoJson.size,
                encryptedBytes = encryptedBytes,
                operation = operation,
                iteration = index + 1,
                elapsedNanos = elapsedNanos
            )
        }
        summaries += summarize(dataset, encryptedBytes, operation, durationsNanos)
    }

    private fun summarize(
        dataset: Dataset,
        encryptedBytes: Int,
        operation: Operation,
        durationsNanos: LongArray
    ): SummaryRow {
        val seconds = durationsNanos.map { it / NANOS_PER_SECOND }
        val median = percentile(seconds, 0.50)
        return SummaryRow(
            datasetId = dataset.id,
            attributeProfile = dataset.profile.csvName,
            pointCount = dataset.pointCount,
            plainTextBytes = dataset.geoJson.size,
            encryptedBytes = encryptedBytes,
            operation = operation,
            warmUpRuns = warmUpRuns,
            measuredRuns = measuredRuns,
            medianSeconds = median,
            q1Seconds = percentile(seconds, 0.25),
            q3Seconds = percentile(seconds, 0.75),
            meanSeconds = seconds.average(),
            minSeconds = seconds.minOrNull() ?: error("No benchmark samples."),
            maxSeconds = seconds.maxOrNull() ?: error("No benchmark samples."),
            throughputMibPerSecond = when (operation) {
                Operation.ENCRYPT, Operation.DECRYPT ->
                    (dataset.geoJson.size / BYTES_PER_MIB) / median
                else -> null
            }
        )
    }

    private fun percentile(values: List<Double>, fraction: Double): Double {
        require(values.isNotEmpty())
        val sorted = values.sorted()
        val position = (sorted.size - 1) * fraction
        val lower = floor(position).toInt()
        val upper = ceil(position).toInt()
        if (lower == upper) return sorted[lower]
        val weight = position - lower
        return sorted[lower] * (1.0 - weight) + sorted[upper] * weight
    }

    private fun writeDatasets(outputDirectory: File, datasets: List<Dataset>) {
        check(outputDirectory.mkdirs() || outputDirectory.isDirectory) {
            "Could not create benchmark dataset directory ${outputDirectory.absolutePath}."
        }
        datasets.forEach { dataset ->
            File(outputDirectory, "${dataset.id}.geojson").writeBytes(dataset.geoJson)
        }
    }

    private fun writeDatasetManifest(
        output: File,
        datasets: List<Dataset>,
        encryptedBaselines: Map<String, ByteArray>
    ) {
        output.bufferedWriter().use { writer ->
            writer.appendLine(
                "dataset_id,attribute_profile,points,attribute_fields,plaintext_bytes," +
                    "plaintext_kib,encrypted_bytes,sha256,attachments_included"
            )
            datasets.sortedWith(compareBy<Dataset> { it.pointCount }.thenBy { it.profile.tableOrder })
                .forEach { dataset ->
                    writer.appendLine(
                        listOf(
                            dataset.id,
                            dataset.profile.csvName,
                            dataset.pointCount,
                            ATTRIBUTE_FIELD_COUNT,
                            dataset.geoJson.size,
                            String.format(Locale.US, "%.3f", dataset.geoJson.size / 1024.0),
                            requireNotNull(encryptedBaselines[dataset.id]).size,
                            sha256(dataset.geoJson),
                            false
                        ).joinToString(",")
                    )
                }
        }
    }

    private fun writeRawCsv(output: File, rows: List<RawRow>) {
        output.bufferedWriter().use { writer ->
            writer.appendLine(
                "dataset_id,attribute_profile,dataset_points,plaintext_bytes,encrypted_bytes,operation,iteration," +
                    "elapsed_nanoseconds,elapsed_seconds"
            )
            rows.sortedWith(
                compareBy<RawRow> { it.pointCount }
                    .thenBy { it.attributeProfile }
                    .thenBy { it.operation.tableOrder }
                    .thenBy(RawRow::iteration)
            ).forEach { writer.appendLine(it.toCsv()) }
        }
    }

    private fun writeMaskingPhaseCsv(output: File, rows: List<MaskingPhaseRow>) {
        output.bufferedWriter().use { writer ->
            writer.appendLine(MaskingPhaseRow.CSV_HEADER)
            rows.sortedWith(
                compareBy<MaskingPhaseRow> { it.pointCount }
                    .thenBy { it.attributeProfile }
                    .thenBy(MaskingPhaseRow::iteration)
            ).forEach { writer.appendLine(it.toCsv()) }
        }
    }

    private fun writeProgress(
        output: File,
        completedCells: Int,
        totalCells: Int,
        complete: Boolean
    ) {
        output.writeText(
            JSONObject()
                .put("completed_cells", completedCells)
                .put("total_cells", totalCells)
                .put("complete", complete)
                .put("updated_utc", utcTimestamp())
                .toString(2)
        )
    }

    private fun writeSummaryCsv(output: File, rows: List<SummaryRow>) {
        output.bufferedWriter().use { writer ->
            writer.appendLine(SummaryRow.CSV_HEADER)
            rows.sortedWith(
                compareBy<SummaryRow> { it.pointCount }
                    .thenBy { it.attributeProfile }
                    .thenBy { it.operation.tableOrder }
            ).forEach { writer.appendLine(it.toCsv()) }
        }
    }

    private fun writeLatexTable(
        output: File,
        datasets: List<Dataset>,
        summaries: List<SummaryRow>
    ) {
        val byDataset = summaries.groupBy(SummaryRow::datasetId)
        val deviceDescription = if (runningOnEmulator) {
            "a preliminary Android emulator run on"
        } else {
            "a physical"
        }
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { character ->
            if (character.isLowerCase()) character.titlecase(Locale.US) else character.toString()
        }
        val warmUpRunLabel = if (warmUpRuns == 1) "warm-up run" else "warm-up runs"
        output.bufferedWriter().use { writer ->
            writer.appendLine("\\begin{table}[ht!]")
            writer.appendLine("\\centering")
            writer.appendLine(
                "\\caption{Median halo-masking core, core plus Spruill assessment, complete " +
                    "production masking workflow, signed OpenPGP encryption, and verified " +
                    "decryption times in seconds from $deviceDescription " +
                    "${escapeLatex(manufacturer)} ${escapeLatex(Build.MODEL)} Android device. " +
                    "Each value represents $measuredRuns measured runs following " +
                    "$warmUpRuns $warmUpRunLabel. The complete workflow starts with reading an " +
                    "already selected NextGIS source layer and ends after output-layer insertion " +
                    "and map saving. Initial dataset import, UI rendering, and attachments are not included.}"
            )
            writer.appendLine("\\label{tab:mobile-mask-encryption-times}")
            writer.appendLine("\\begin{tabular}{llrrrrrrr}")
            writer.appendLine("\\hline")
            writer.appendLine(
                "Dataset & Attributes & Size (KiB) & Points & Masking core & Core + SM & " +
                    "Total workflow & Encryption & Decryption \\\\"
            )
            writer.appendLine("\\hline")
            datasets.sortedWith(compareBy<Dataset> { it.pointCount }.thenBy { it.profile.tableOrder })
                .forEach { dataset ->
                val operations = requireNotNull(byDataset[dataset.id]).associateBy(SummaryRow::operation)
                writer.appendLine(
                    "Synthetic field-${dataset.pointCount} & ${dataset.profile.latexName} & " +
                        String.format(Locale.US, "%.1f", dataset.geoJson.size / 1024.0) + " & " +
                        String.format(Locale.US, "%,d", dataset.pointCount) + " & " +
                        tableSeconds(requireNotNull(operations[Operation.MASK_CORE]).medianSeconds) + " & " +
                        tableSeconds(requireNotNull(operations[Operation.MASK_WITH_SPRUILL]).medianSeconds) + " & " +
                        tableSeconds(requireNotNull(operations[Operation.MASK_WORKFLOW_TOTAL]).medianSeconds) + " & " +
                        tableSeconds(requireNotNull(operations[Operation.ENCRYPT]).medianSeconds) + " & " +
                        tableSeconds(requireNotNull(operations[Operation.DECRYPT]).medianSeconds) + " \\\\"
                )
                }
            writer.appendLine("\\hline")
            writer.appendLine("\\end{tabular}")
            writer.appendLine("\\end{table}")
        }
    }

    private fun writeMetadata(
        output: File,
        context: Context,
        runId: String,
        datasets: List<Dataset>,
        summaries: List<SummaryRow>,
        environmentAtStart: EnvironmentSnapshot
    ) {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val metadata = JSONObject()
            .put("protocol", "mapsafe-mobile-performance-v3")
            .put("protocol_mode", protocolName)
            .put("dataset_source", datasetSource)
            .put("run_id_utc", runId)
            .put("physical_device_required", true)
            .put("running_on_emulator", runningOnEmulator)
            .put("publication_eligible", !runningOnEmulator && protocolName == PROTOCOL_PAPER)
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("device", Build.DEVICE)
            .put("product", Build.PRODUCT)
            .put("soc_model", if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else JSONObject.NULL)
            .put("android_release", Build.VERSION.RELEASE)
            .put("android_sdk", Build.VERSION.SDK_INT)
            .put("available_processors", Runtime.getRuntime().availableProcessors())
            .put("total_memory_bytes", memory.totalMem)
            .put("app_version", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
            .put("app_build_type", BuildConfig.BUILD_TYPE)
            .put("warm_up_runs_per_cell", warmUpRuns)
            .put("measured_runs_per_cell", measuredRuns)
            .put("cell_order_seed", CELL_ORDER_SEED)
            .put("mask_min_metres", MASK_MIN_METRES)
            .put("mask_max_metres", MASK_MAX_METRES)
            .put(
                "masking_scope",
                JSONObject()
                    .put("mask_core", "source CRS normalisation, secure random displacement, and spherical destination calculation")
                    .put("mask_with_spruill", "mask core plus Spruill nearest-neighbour assessment on the same candidate")
                    .put("mask_workflow_total", "selected source-layer read through output-layer insertion and map save")
                    .put("excluded", "initial dataset import, UI interaction/rendering, attachments, and performance-result writing")
            )
            .put("attribute_field_count", ATTRIBUTE_FIELD_COUNT)
            .put("attachments_included", false)
            .put(
                "attachment_note",
                "NextGIS attachments are stored separately and are not included by the current MapSafe GeoJSON exporter."
            )
            .put("openpgp_rsa_bits", OpenPgpKeyGenerator.DEFAULT_RSA_BITS)
            .put("openpgp_recipients", 1)
            .put("openpgp_signed", true)
            .put("environment_at_start", environmentAtStart.toJson())
            .put("environment_at_end", captureEnvironment(context).toJson())
            .put("dataset_point_counts", org.json.JSONArray(POINT_COUNTS))
            .put("attribute_profiles", org.json.JSONArray(AttributeProfile.entries.map(AttributeProfile::csvName)))
            .put("datasets", org.json.JSONArray().apply {
                datasets.forEach { dataset ->
                    put(
                        JSONObject()
                            .put("dataset_id", dataset.id)
                            .put("attribute_profile", dataset.profile.csvName)
                            .put("points", dataset.pointCount)
                            .put("plaintext_bytes", dataset.geoJson.size)
                            .put("sha256", sha256(dataset.geoJson))
                    )
                }
            })
            .put("summary_rows", summaries.size)
        output.writeText(metadata.toString(2))
    }

    private fun configureProtocol() {
        protocolName = InstrumentationRegistry.getArguments()
            .getString(ARG_PROTOCOL)
            ?.trim()
            ?.lowercase(Locale.US)
            ?.takeIf { it == PROTOCOL_QUICK || it == PROTOCOL_PAPER }
            ?: PROTOCOL_PAPER
        if (protocolName == PROTOCOL_QUICK) {
            warmUpRuns = QUICK_WARM_UP_RUNS
            measuredRuns = QUICK_MEASURED_RUNS
        } else {
            warmUpRuns = PAPER_WARM_UP_RUNS
            measuredRuns = PAPER_MEASURED_RUNS
        }
    }

    private fun captureEnvironment(context: Context): EnvironmentSnapshot {
        val batteryIntent = context.registerReceiver(
            null,
            android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return EnvironmentSnapshot(
            batteryPercent = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
            batteryTemperatureCelsius = batteryIntent
                ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
                ?.takeIf { it >= 0 }
                ?.div(10.0),
            batteryStatus = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1),
            pluggedState = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1),
            thermalStatus = if (Build.VERSION.SDK_INT >= 29) powerManager.currentThermalStatus else null
        )
    }

    private fun sha256(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun escapeLatex(value: String): String {
        return value
            .replace("\\", "\\textbackslash{}")
            .replace("&", "\\&")
            .replace("%", "\\%")
            .replace("_", "\\_")
            .replace("#", "\\#")
    }

    private fun requireAcceptableThermalState(context: Context) {
        if (Build.VERSION.SDK_INT < 29) return
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        check(powerManager.currentThermalStatus < PowerManager.THERMAL_STATUS_SEVERE) {
            "The device reached a severe thermal state; discard this run and repeat after cooling."
        }
    }

    private fun isProbablyEmulator(): Boolean {
        return Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
            Build.MODEL.contains("Emulator", ignoreCase = true) ||
            Build.MODEL.contains("Android SDK built for", ignoreCase = true) ||
            Build.HARDWARE.contains("goldfish", ignoreCase = true) ||
            Build.HARDWARE.contains("ranchu", ignoreCase = true) ||
            Build.PRODUCT.contains("sdk", ignoreCase = true)
    }

    private fun utcTimestamp(): String {
        return SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
    }

    private enum class AttributeProfile(
        val csvName: String,
        val latexName: String,
        val notesCharacters: Int,
        val stewardshipCharacters: Int,
        val tableOrder: Int
    ) {
        TYPICAL("typical", "Typical", 192, 96, 0),
        RICH("rich", "Rich text", 2_048, 768, 1)
    }

    private data class Dataset(
        val id: String,
        val profile: AttributeProfile,
        val pointCount: Int,
        val coordinates: List<SpruillMeasure.Coordinate>,
        val geoJson: ByteArray
    )

    private data class Cell(
        val dataset: Dataset,
        val operation: CellOperation
    )

    private data class MaskingRun(
        val datasetId: String,
        val result: DonutMaskingWorkflow.WorkflowResult,
        val outerElapsedNanos: Long
    )

    private data class DecryptOutcome(
        val plainText: ByteArray,
        val signatureStatus: OpenPgpSignatureStatus
    )

    private data class EnvironmentSnapshot(
        val batteryPercent: Int,
        val batteryTemperatureCelsius: Double?,
        val batteryStatus: Int?,
        val pluggedState: Int?,
        val thermalStatus: Int?
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("battery_percent", batteryPercent)
            .put("battery_temperature_celsius", batteryTemperatureCelsius ?: JSONObject.NULL)
            .put("battery_status", batteryStatus ?: JSONObject.NULL)
            .put("plugged_state", pluggedState ?: JSONObject.NULL)
            .put("thermal_status", thermalStatus ?: JSONObject.NULL)
    }

    private enum class CellOperation(val csvName: String) {
        MASK_WORKFLOW("mask_production_workflow"),
        ENCRYPT("openpgp_encrypt_signed_one_recipient"),
        DECRYPT("openpgp_decrypt_verify")
    }

    private enum class Operation(val csvName: String, val tableOrder: Int) {
        MASK_CORE("mask_core", 0),
        MASK_WITH_SPRUILL("mask_core_plus_spruill", 1),
        MASK_WORKFLOW_TOTAL("mask_workflow_total", 2),
        ENCRYPT("openpgp_encrypt_signed_one_recipient", 3),
        DECRYPT("openpgp_decrypt_verify", 4)
    }

    private data class MaskingPhaseRow(
        val datasetId: String,
        val attributeProfile: String,
        val pointCount: Int,
        val iteration: Int,
        val sourceReadNanos: Long,
        val maskCoreNanos: Long,
        val spruillNanos: Long,
        val featureConstructionNanos: Long,
        val outputWriteNanos: Long,
        val workflowTotalNanos: Long,
        val outerElapsedNanos: Long
    ) {
        private val corePlusSpruillNanos: Long get() = maskCoreNanos + spruillNanos
        private val accountedNanos: Long get() =
            sourceReadNanos + maskCoreNanos + spruillNanos +
                featureConstructionNanos + outputWriteNanos
        private val unaccountedNanos: Long get() = workflowTotalNanos - accountedNanos

        fun toCsv(): String = listOf(
            datasetId,
            attributeProfile,
            pointCount,
            iteration,
            sourceReadNanos.toSecondsText(),
            maskCoreNanos.toSecondsText(),
            spruillNanos.toSecondsText(),
            featureConstructionNanos.toSecondsText(),
            outputWriteNanos.toSecondsText(),
            corePlusSpruillNanos.toSecondsText(),
            workflowTotalNanos.toSecondsText(),
            outerElapsedNanos.toSecondsText(),
            unaccountedNanos.toSecondsText()
        ).joinToString(",")

        companion object {
            const val CSV_HEADER =
                "dataset_id,attribute_profile,dataset_points,iteration," +
                    "source_read_seconds,mask_core_seconds,spruill_only_seconds," +
                    "feature_construction_seconds,output_write_seconds," +
                    "mask_core_plus_spruill_seconds,workflow_total_seconds," +
                    "outer_elapsed_seconds,unaccounted_seconds"

            fun from(dataset: Dataset, iteration: Int, run: MaskingRun): MaskingPhaseRow {
                val result = run.result
                return MaskingPhaseRow(
                    datasetId = dataset.id,
                    attributeProfile = dataset.profile.csvName,
                    pointCount = dataset.pointCount,
                    iteration = iteration,
                    sourceReadNanos = result.sourceReadDurationNanos,
                    maskCoreNanos = result.maskingDurationNanos,
                    spruillNanos = result.spruillDurationNanos,
                    featureConstructionNanos = result.featureConstructionDurationNanos,
                    outputWriteNanos = result.outputWriteDurationNanos,
                    workflowTotalNanos = result.workflowDurationNanos,
                    outerElapsedNanos = run.outerElapsedNanos
                )
            }
        }
    }

    private data class RawRow(
        val datasetId: String,
        val attributeProfile: String,
        val pointCount: Int,
        val plainTextBytes: Int,
        val encryptedBytes: Int,
        val operation: Operation,
        val iteration: Int,
        val elapsedNanos: Long
    ) {
        fun toCsv(): String = listOf(
            datasetId,
            attributeProfile,
            pointCount,
            plainTextBytes,
            encryptedBytes,
            operation.csvName,
            iteration,
            elapsedNanos,
            formatSeconds(elapsedNanos / NANOS_PER_SECOND)
        ).joinToString(",")
    }

    private data class SummaryRow(
        val datasetId: String,
        val attributeProfile: String,
        val pointCount: Int,
        val plainTextBytes: Int,
        val encryptedBytes: Int,
        val operation: Operation,
        val warmUpRuns: Int,
        val measuredRuns: Int,
        val medianSeconds: Double,
        val q1Seconds: Double,
        val q3Seconds: Double,
        val meanSeconds: Double,
        val minSeconds: Double,
        val maxSeconds: Double,
        val throughputMibPerSecond: Double?
    ) {
        fun toCsv(): String = listOf(
            datasetId,
            attributeProfile,
            pointCount,
            plainTextBytes,
            encryptedBytes,
            operation.csvName,
            warmUpRuns,
            measuredRuns,
            formatSeconds(medianSeconds),
            formatSeconds(q1Seconds),
            formatSeconds(q3Seconds),
            formatSeconds(meanSeconds),
            formatSeconds(minSeconds),
            formatSeconds(maxSeconds),
            throughputMibPerSecond?.let(::formatThroughput).orEmpty()
        ).joinToString(",")

        companion object {
            const val CSV_HEADER =
                "dataset_id,attribute_profile,dataset_points,plaintext_bytes,encrypted_bytes," +
                    "operation,warmup_runs,measured_runs," +
                    "median_seconds,q1_seconds,q3_seconds,mean_seconds,min_seconds,max_seconds," +
                    "throughput_mib_per_second"
        }
    }

    companion object {
        private const val LOG_TAG = "MapSafeBenchmark"
        private val POINT_COUNTS = listOf(50, 250, 500, 1_000, 2_000)
        private val CATEGORIES = listOf("water", "housing", "heritage", "health", "environment")
        private val SENSITIVITIES = listOf("community", "restricted", "sensitive")
        private val STATUSES = listOf("observed", "reviewed", "follow_up")
        private val UNITS = listOf("count", "metres", "litres", "index")
        private val ACCESS_METHODS = listOf("walking", "boat", "road", "community_guide")
        private const val TEXT_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789 -_.,;:"
        private const val BASE_OBSERVED_AT_MILLIS = 1_786_651_200_000L
        private const val ATTRIBUTE_FIELD_COUNT = 18
        private const val MASK_MIN_METRES = 100.0
        private const val MASK_MAX_METRES = 2_000.0
        private const val MASK_METADATA_FIELD_COUNT = 4
        private const val BENCHMARK_LAYER_PREFIX = "MapSafe benchmark "
        private const val PROTOCOL_QUICK = "quick"
        private const val PROTOCOL_PAPER = "paper"
        private const val ARG_PROTOCOL = "mapsafe.protocol"
        private const val ARG_ALLOW_EMULATOR = "mapsafe.allowEmulator"
        private const val ARG_INPUT_DIRECTORY = "mapsafe.inputDirectory"
        private const val DATASET_SOURCE_GENERATED = "generated_by_instrumentation"
        private const val DATASET_SOURCE_STAGED_PHONE = "staged_from_downloads_mapsafe"
        private const val QUICK_WARM_UP_RUNS = 1
        private const val QUICK_MEASURED_RUNS = 5
        private const val PAPER_WARM_UP_RUNS = 5
        private const val PAPER_MEASURED_RUNS = 30
        private const val CELL_ORDER_SEED = 20_260_811
        private const val NANOS_PER_SECOND = 1_000_000_000.0
        private const val BYTES_PER_MIB = 1_048_576.0

        private fun formatSeconds(value: Double): String = String.format(Locale.US, "%.9f", value)
        private fun formatThroughput(value: Double): String = String.format(Locale.US, "%.6f", value)
        private fun tableSeconds(value: Double): String = String.format(Locale.US, "%.5f", value)
        private fun Long.toSecondsText(): String = formatSeconds(this / NANOS_PER_SECOND)
    }
}
