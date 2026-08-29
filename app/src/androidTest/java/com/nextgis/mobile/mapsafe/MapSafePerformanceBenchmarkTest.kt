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
import com.nextgis.mobile.BuildConfig
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpEngine
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpKeyGenerator
import com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpSignatureStatus
import com.nextgis.mobile.mapsafe.safeguard.anonymise.DonutMasking
import com.nextgis.mobile.mapsafe.safeguard.anonymise.SpruillMeasure
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
 * Physical-device performance protocol for the MapSafe paper.
 *
 * The benchmark deliberately excludes UI interaction, dataset loading, key generation,
 * recipient discovery, and network access. It measures the four operations reported in
 * the paper table:
 *
 * 1. donut masking only;
 * 2. donut masking followed by Spruill assessment;
 * 3. signed AES-256-GCM OpenPGP encryption for one RSA-3072 recipient; and
 * 4. OpenPGP private-key unlock, decryption, integrity checking, and signature verification.
 *
 * Results are written as raw and summary CSV files under the app's external files
 * directory so scripts/run-mapsafe-performance-benchmark.ps1 can pull them unchanged.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class MapSafePerformanceBenchmarkTest {

    @Volatile
    private var benchmarkSink = 0L

    @Test
    fun recordMaskingEncryptionAndDecryptionForRealisticFieldDatasets() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertFalse(
            "Paper performance measurements must run on a physical Android device, not an emulator.",
            isProbablyEmulator()
        )
        requireAcceptableThermalState(context)
        val environmentAtStart = captureEnvironment(context)

        val datasets = POINT_COUNTS.map(::createDataset)
        assertEquals(POINT_COUNTS, datasets.map(Dataset::pointCount))

        val passphrase = "MapSafe physical benchmark recovery passphrase".toCharArray()
        val identity = try {
            OpenPgpKeyGenerator.generate(
                userId = "MapSafe Physical Benchmark <benchmark@example.test>",
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
                dataset.pointCount to encrypted
            }

            val runId = utcTimestamp()
            val outputDirectory = File(
                context.getExternalFilesDir(null) ?: context.filesDir,
                "mapsafe-benchmark/$runId"
            )
            check(outputDirectory.mkdirs() || outputDirectory.isDirectory) {
                "Could not create benchmark output directory ${outputDirectory.absolutePath}."
            }

            val rawRows = mutableListOf<RawRow>()
            val summaries = mutableListOf<SummaryRow>()
            val cells = datasets.flatMap { dataset ->
                Operation.entries.map { operation -> Cell(dataset, operation) }
            }.shuffled(Random(CELL_ORDER_SEED))

            cells.forEachIndexed { cellIndex, cell ->
                requireAcceptableThermalState(context)
                Log.i(
                    LOG_TAG,
                    "CELL ${cellIndex + 1}/${cells.size}: ${cell.dataset.pointCount} points, " +
                        cell.operation.csvName
                )
                val durations = measureCell(
                    cell = cell,
                    identity = identity,
                    passphrase = passphrase,
                    encryptedBaseline = requireNotNull(encryptedBaselines[cell.dataset.pointCount])
                )
                durations.forEachIndexed { index, elapsedNanos ->
                    rawRows += RawRow(
                        pointCount = cell.dataset.pointCount,
                        plainTextBytes = cell.dataset.geoJson.size,
                        encryptedBytes = requireNotNull(encryptedBaselines[cell.dataset.pointCount]).size,
                        operation = cell.operation,
                        iteration = index + 1,
                        elapsedNanos = elapsedNanos
                    )
                }
                summaries += summarize(
                    cell = cell,
                    encryptedBytes = requireNotNull(encryptedBaselines[cell.dataset.pointCount]).size,
                    durationsNanos = durations
                )
            }

            requireAcceptableThermalState(context)
            writeRawCsv(File(outputDirectory, "raw-measurements.csv"), rawRows)
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

            summaries.sortedWith(
                compareBy<SummaryRow> { it.pointCount }.thenBy { it.operation.tableOrder }
            ).forEach { summary ->
                Log.i(LOG_TAG, "RESULT,${summary.toCsv()}")
            }
            Log.i(LOG_TAG, "OUTPUT,${outputDirectory.absolutePath}")
            assertTrue("The benchmark sink was not updated.", benchmarkSink != 0L)
        } finally {
            passphrase.fill('\u0000')
        }
    }

    private fun measureCell(
        cell: Cell,
        identity: com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpKeyMaterial,
        passphrase: CharArray,
        encryptedBaseline: ByteArray
    ): LongArray {
        repeat(WARM_UP_RUNS) {
            consume(
                cell,
                execute(cell, identity, passphrase, encryptedBaseline)
            )
        }

        return LongArray(MEASURED_RUNS) {
            val startedAt = SystemClock.elapsedRealtimeNanos()
            val outcome = execute(cell, identity, passphrase, encryptedBaseline)
            val elapsed = SystemClock.elapsedRealtimeNanos() - startedAt
            consume(cell, outcome)
            elapsed
        }
    }

    private fun execute(
        cell: Cell,
        identity: com.nextgis.mobile.mapsafe.crypto.openpgp.OpenPgpKeyMaterial,
        passphrase: CharArray,
        encryptedBaseline: ByteArray
    ): Any = when (cell.operation) {
        Operation.MASK_ONLY -> maskCoordinates(cell.dataset.coordinates)
        Operation.MASK_WITH_SPRUILL -> {
            val masked = maskCoordinates(cell.dataset.coordinates)
            MaskWithSpruillOutcome(
                masked = masked,
                assessment = SpruillMeasure.calculate(cell.dataset.coordinates, masked)
            )
        }
        Operation.ENCRYPT -> encrypt(cell.dataset.geoJson, identity, passphrase)
        Operation.DECRYPT -> decrypt(encryptedBaseline, identity, passphrase)
    }

    @Suppress("UNCHECKED_CAST")
    private fun consume(cell: Cell, outcome: Any) {
        benchmarkSink = benchmarkSink xor when (cell.operation) {
            Operation.MASK_ONLY -> {
                val masked = outcome as List<SpruillMeasure.Coordinate>
                assertEquals(cell.dataset.pointCount, masked.size)
                masked.size.toLong() xor masked.last().longitude.toBits()
            }
            Operation.MASK_WITH_SPRUILL -> {
                val combined = outcome as MaskWithSpruillOutcome
                assertEquals(cell.dataset.pointCount, combined.masked.size)
                assertEquals(cell.dataset.pointCount, combined.assessment.evaluatedPoints)
                assertTrue(combined.assessment.privacyRatingPercent in 0.0..100.0)
                combined.assessment.parentNearestCount.toLong() xor
                    combined.assessment.privacyRatingPercent.toBits()
            }
            Operation.ENCRYPT -> {
                val encrypted = outcome as ByteArray
                assertTrue(encrypted.isNotEmpty())
                encrypted.size.toLong() xor encrypted.last().toLong()
            }
            Operation.DECRYPT -> {
                val decrypted = outcome as DecryptOutcome
                assertArrayEquals(cell.dataset.geoJson, decrypted.plainText)
                assertEquals(OpenPgpSignatureStatus.VALID, decrypted.signatureStatus)
                decrypted.plainText.size.toLong() xor decrypted.signatureStatus.ordinal.toLong()
            }
        }
    }

    private fun maskCoordinates(
        originals: List<SpruillMeasure.Coordinate>
    ): List<SpruillMeasure.Coordinate> {
        return ArrayList<SpruillMeasure.Coordinate>(originals.size).apply {
            originals.forEach { original ->
                val masked = DonutMasking.maskPoint(
                    longitude = original.longitude,
                    latitude = original.latitude,
                    minDistanceMetres = MASK_MIN_METRES,
                    maxDistanceMetres = MASK_MAX_METRES
                )
                add(SpruillMeasure.Coordinate(masked.longitude, masked.latitude))
            }
        }
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

    private fun createDataset(pointCount: Int): Dataset {
        val coordinates = ArrayList<SpruillMeasure.Coordinate>(pointCount)
        val json = StringBuilder(pointCount * 210)
        json.append("{\"type\":\"FeatureCollection\",\"features\":[")
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
                .append("\"households\":").append(1 + (index * 7) % 80)
                .append("},\"geometry\":{\"type\":\"Point\",\"coordinates\":[")
                .append(String.format(Locale.US, "%.6f", longitude)).append(',')
                .append(String.format(Locale.US, "%.6f", latitude))
                .append("]}}")
        }
        json.append("]}")
        return Dataset(pointCount, coordinates, json.toString().toByteArray(Charsets.UTF_8))
    }

    private fun summarize(
        cell: Cell,
        encryptedBytes: Int,
        durationsNanos: LongArray
    ): SummaryRow {
        val seconds = durationsNanos.map { it / NANOS_PER_SECOND }
        val median = percentile(seconds, 0.50)
        return SummaryRow(
            pointCount = cell.dataset.pointCount,
            plainTextBytes = cell.dataset.geoJson.size,
            encryptedBytes = encryptedBytes,
            operation = cell.operation,
            warmUpRuns = WARM_UP_RUNS,
            measuredRuns = MEASURED_RUNS,
            medianSeconds = median,
            q1Seconds = percentile(seconds, 0.25),
            q3Seconds = percentile(seconds, 0.75),
            meanSeconds = seconds.average(),
            minSeconds = seconds.minOrNull() ?: error("No benchmark samples."),
            maxSeconds = seconds.maxOrNull() ?: error("No benchmark samples."),
            throughputMibPerSecond = when (cell.operation) {
                Operation.ENCRYPT, Operation.DECRYPT ->
                    (cell.dataset.geoJson.size / BYTES_PER_MIB) / median
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

    private fun writeRawCsv(output: File, rows: List<RawRow>) {
        output.bufferedWriter().use { writer ->
            writer.appendLine(
                "dataset_points,plaintext_bytes,encrypted_bytes,operation,iteration," +
                    "elapsed_nanoseconds,elapsed_seconds"
            )
            rows.sortedWith(
                compareBy<RawRow> { it.pointCount }
                    .thenBy { it.operation.tableOrder }
                    .thenBy(RawRow::iteration)
            ).forEach { writer.appendLine(it.toCsv()) }
        }
    }

    private fun writeSummaryCsv(output: File, rows: List<SummaryRow>) {
        output.bufferedWriter().use { writer ->
            writer.appendLine(SummaryRow.CSV_HEADER)
            rows.sortedWith(
                compareBy<SummaryRow> { it.pointCount }.thenBy { it.operation.tableOrder }
            ).forEach { writer.appendLine(it.toCsv()) }
        }
    }

    private fun writeLatexTable(
        output: File,
        datasets: List<Dataset>,
        summaries: List<SummaryRow>
    ) {
        val byDataset = summaries.groupBy(SummaryRow::pointCount)
        output.bufferedWriter().use { writer ->
            writer.appendLine("\\begin{table}[ht!]")
            writer.appendLine("\\centering")
            writer.appendLine(
                "\\caption{Median masking, Spruill assessment, signed OpenPGP encryption, " +
                    "and verified decryption times in seconds on a physical " +
                    "${escapeLatex(Build.MANUFACTURER)} ${escapeLatex(Build.MODEL)} Android device. " +
                    "Each value represents $MEASURED_RUNS measured runs following " +
                    "$WARM_UP_RUNS warm-up runs.}"
            )
            writer.appendLine("\\label{tab:mobile-mask-encryption-times}")
            writer.appendLine("\\begin{tabular}{lrrrrrr}")
            writer.appendLine("\\hline")
            writer.appendLine(
                "Dataset & Size (KiB) & Points & Masking w/o SM & Masking with SM & " +
                    "Encryption & Decryption \\\\"
            )
            writer.appendLine("\\hline")
            datasets.sortedBy(Dataset::pointCount).forEach { dataset ->
                val operations = requireNotNull(byDataset[dataset.pointCount]).associateBy(SummaryRow::operation)
                writer.appendLine(
                    "Synthetic field-${dataset.pointCount} & " +
                        String.format(Locale.US, "%.1f", dataset.geoJson.size / 1024.0) + " & " +
                        String.format(Locale.US, "%,d", dataset.pointCount) + " & " +
                        tableSeconds(requireNotNull(operations[Operation.MASK_ONLY]).medianSeconds) + " & " +
                        tableSeconds(requireNotNull(operations[Operation.MASK_WITH_SPRUILL]).medianSeconds) + " & " +
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
            .put("protocol", "mapsafe-mobile-performance-v1")
            .put("run_id_utc", runId)
            .put("physical_device_required", true)
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
            .put("warm_up_runs_per_cell", WARM_UP_RUNS)
            .put("measured_runs_per_cell", MEASURED_RUNS)
            .put("cell_order_seed", CELL_ORDER_SEED)
            .put("mask_min_metres", MASK_MIN_METRES)
            .put("mask_max_metres", MASK_MAX_METRES)
            .put("openpgp_rsa_bits", OpenPgpKeyGenerator.DEFAULT_RSA_BITS)
            .put("openpgp_recipients", 1)
            .put("openpgp_signed", true)
            .put("environment_at_start", environmentAtStart.toJson())
            .put("environment_at_end", captureEnvironment(context).toJson())
            .put("dataset_point_counts", org.json.JSONArray(datasets.map(Dataset::pointCount)))
            .put("dataset_plaintext_bytes", JSONObject().apply {
                datasets.forEach { put(it.pointCount.toString(), it.geoJson.size) }
            })
            .put("dataset_sha256", JSONObject().apply {
                datasets.forEach { put(it.pointCount.toString(), sha256(it.geoJson)) }
            })
            .put("summary_rows", summaries.size)
        output.writeText(metadata.toString(2))
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

    private data class Dataset(
        val pointCount: Int,
        val coordinates: List<SpruillMeasure.Coordinate>,
        val geoJson: ByteArray
    )

    private data class Cell(
        val dataset: Dataset,
        val operation: Operation
    )

    private data class MaskWithSpruillOutcome(
        val masked: List<SpruillMeasure.Coordinate>,
        val assessment: SpruillMeasure.Result
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

    private enum class Operation(val csvName: String, val tableOrder: Int) {
        MASK_ONLY("mask_without_spruill", 0),
        MASK_WITH_SPRUILL("mask_with_spruill", 1),
        ENCRYPT("openpgp_encrypt_signed_one_recipient", 2),
        DECRYPT("openpgp_decrypt_verify", 3)
    }

    private data class RawRow(
        val pointCount: Int,
        val plainTextBytes: Int,
        val encryptedBytes: Int,
        val operation: Operation,
        val iteration: Int,
        val elapsedNanos: Long
    ) {
        fun toCsv(): String = listOf(
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
                "dataset_points,plaintext_bytes,encrypted_bytes,operation,warmup_runs,measured_runs," +
                    "median_seconds,q1_seconds,q3_seconds,mean_seconds,min_seconds,max_seconds," +
                    "throughput_mib_per_second"
        }
    }

    companion object {
        private const val LOG_TAG = "MapSafeBenchmark"
        private val POINT_COUNTS = listOf(50, 250, 500, 1_000)
        private val CATEGORIES = listOf("water", "housing", "heritage", "health", "environment")
        private val SENSITIVITIES = listOf("community", "restricted", "sensitive")
        private const val MASK_MIN_METRES = 100.0
        private const val MASK_MAX_METRES = 2_000.0
        private const val WARM_UP_RUNS = 5
        private const val MEASURED_RUNS = 30
        private const val CELL_ORDER_SEED = 20_260_811
        private const val NANOS_PER_SECOND = 1_000_000_000.0
        private const val BYTES_PER_MIB = 1_048_576.0

        private fun formatSeconds(value: Double): String = String.format(Locale.US, "%.9f", value)
        private fun formatThroughput(value: Double): String = String.format(Locale.US, "%.6f", value)
        private fun tableSeconds(value: Double): String = String.format(Locale.US, "%.5f", value)
    }
}
