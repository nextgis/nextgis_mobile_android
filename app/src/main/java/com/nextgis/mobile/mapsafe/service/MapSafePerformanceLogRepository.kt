package com.nextgis.mobile.mapsafe.service

import android.content.Context
import android.os.Build
import android.util.Log
import com.nextgis.mobile.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.Executors

/** Appends successful production-workflow timings to Downloads/MapSafe. */
object MapSafePerformanceLogRepository {

    enum class Operation(val csvName: String) {
        MASK_WITHOUT_SPRUILL("mask_without_spruill"),
        MASK_WITH_SPRUILL("mask_with_spruill"),
        MASK_WORKFLOW_TOTAL("mask_workflow_total"),
        OPENPGP_ENCRYPT("openpgp_encrypt_signed"),
        OPENPGP_DECRYPT_VERIFY("openpgp_decrypt_verify")
    }

    data class Record(
        val operation: Operation,
        val datasetName: String,
        val pointCount: Int? = null,
        val inputBytes: Long? = null,
        val outputBytes: Long? = null,
        val durationNanos: Long,
        val minDistanceMetres: Double? = null,
        val maxDistanceMetres: Double? = null,
        val recipientCount: Int? = null,
        val signed: Boolean? = null,
        val completedAtMillis: Long = System.currentTimeMillis()
    ) {
        init {
            require(durationNanos >= 0L) { "Duration must not be negative." }
            require(pointCount == null || pointCount >= 0) { "Point count must not be negative." }
            require(inputBytes == null || inputBytes >= 0L) { "Input bytes must not be negative." }
            require(outputBytes == null || outputBytes >= 0L) { "Output bytes must not be negative." }
        }
    }

    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mapsafe-performance-log").apply { isDaemon = true }
    }

    /** Queues records after the timed work; logging failure never invalidates a successful workflow. */
    fun recordAsync(context: Context, records: Collection<Record>) {
        if (records.isEmpty()) return
        val appContext = context.applicationContext
        writer.execute {
            runCatching { append(appContext, records) }
                .onFailure { Log.w(LOG_TAG, "Could not append the MapSafe performance log.", it) }
        }
    }

    fun recordAsync(context: Context, record: Record) = recordAsync(context, listOf(record))

    /** Synchronous entry point used by instrumentation tests and controlled callers. */
    fun append(context: Context, records: Collection<Record>): MapSafeSaveFolderRepository.SavedFile<Unit> {
        require(records.isNotEmpty()) { "At least one performance record is required." }
        return MapSafeSaveFolderRepository.appendText(
            context = context,
            mimeType = MIME_CSV,
            requestedFileName = FILE_NAME,
            header = HEADER,
            rows = records.map(::csvRow),
            migrateExistingText = ::migrateCsv
        )
    }

    private fun csvRow(record: Record): String = listOf(
        UUID.randomUUID().toString(),
        utcTimestamp(record.completedAtMillis),
        record.operation.csvName,
        safeDatasetName(record.datasetName),
        record.pointCount?.toString().orEmpty(),
        record.inputBytes?.toString().orEmpty(),
        record.outputBytes?.toString().orEmpty(),
        String.format(Locale.US, "%.6f", record.durationNanos / 1_000_000_000.0),
        record.minDistanceMetres?.let { String.format(Locale.US, "%.3f", it) }.orEmpty(),
        record.maxDistanceMetres?.let { String.format(Locale.US, "%.3f", it) }.orEmpty(),
        record.recipientCount?.toString().orEmpty(),
        record.signed?.toString().orEmpty(),
        Build.MANUFACTURER,
        Build.MODEL,
        Build.VERSION.RELEASE,
        BuildConfig.VERSION_NAME
    ).joinToString(",", transform = ::csvEscape)

    private fun utcTimestamp(timeMillis: Long): String = SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        Locale.US
    ).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(timeMillis))

    private fun safeDatasetName(value: String): String = File(
        value.substringAfterLast('/').substringAfterLast('\\')
    ).name.take(160).ifBlank { "unnamed-dataset" }

    private fun csvEscape(value: String): String {
        if (value.none { it == ',' || it == '"' || it == '\r' || it == '\n' }) return value
        return "\"${value.replace("\"", "\"\"")}\""
    }

    /** Converts earlier nanosecond/second and millisecond schemas to seconds in place. */
    private fun migrateCsv(existing: String): String {
        if (existing.isBlank() || existing.lineSequence().firstOrNull() == HEADER) return existing
        val records = existing.trimEnd('\r', '\n').lineSequence().toList()
        val sourceHeader = records.firstOrNull()
        require(sourceHeader == MILLISECONDS_HEADER || sourceHeader == LEGACY_HEADER) {
            "The existing performance CSV has an unsupported header and was not modified."
        }
        val migratedRows = records.drop(1).filter(String::isNotBlank).map { row ->
            val fields = parseCsvRow(row)
            when (sourceHeader) {
                MILLISECONDS_HEADER -> migrateMillisecondsRow(fields)
                LEGACY_HEADER -> migrateLegacyRow(fields)
                else -> error("Unsupported performance CSV header.")
            }
        }
        return (listOf(HEADER) + migratedRows).joinToString("\n", postfix = "\n")
    }

    private fun migrateMillisecondsRow(fields: List<String>): String {
        require(fields.size == COLUMN_COUNT) {
            "The existing performance CSV contains a malformed millisecond row."
        }
        val seconds = fields[7].toDoubleOrNull()?.div(1_000.0)
            ?: error("The existing performance CSV contains an invalid millisecond duration.")
        return (fields.take(7) + formatSeconds(seconds) + fields.drop(8))
            .joinToString(",", transform = ::csvEscape)
    }

    private fun migrateLegacyRow(fields: List<String>): String {
        require(fields.size == LEGACY_COLUMN_COUNT) {
            "The existing performance CSV contains a malformed legacy row."
        }
        val seconds = fields[8].toDoubleOrNull()
            ?: fields[7].toLongOrNull()?.div(1_000_000_000.0)
            ?: error("The existing performance CSV contains an invalid duration.")
        return (fields.take(7) + formatSeconds(seconds) + fields.drop(9))
            .joinToString(",", transform = ::csvEscape)
    }

    private fun formatSeconds(seconds: Double): String =
        String.format(Locale.US, "%.6f", seconds)

    private fun parseCsvRow(row: String): List<String> {
        val fields = mutableListOf<String>()
        val value = StringBuilder()
        var quoted = false
        var index = 0
        while (index < row.length) {
            val character = row[index]
            when {
                character == '"' && quoted && index + 1 < row.length && row[index + 1] == '"' -> {
                    value.append('"')
                    index += 1
                }
                character == '"' -> quoted = !quoted
                character == ',' && !quoted -> {
                    fields += value.toString()
                    value.setLength(0)
                }
                else -> value.append(character)
            }
            index += 1
        }
        require(!quoted) { "The existing performance CSV contains an unterminated quoted value." }
        fields += value.toString()
        return fields
    }

    const val FILE_NAME = "mapsafe-performance-log.csv"
    private const val MIME_CSV = "text/csv"
    private const val LOG_TAG = "MapSafePerformance"
    private const val HEADER =
        "record_id,timestamp_utc,operation,dataset_name,point_count,input_bytes,output_bytes," +
            "duration_seconds,min_distance_metres,max_distance_metres," +
            "recipient_count,signed,device_manufacturer,device_model,android_release,app_version"
    private const val MILLISECONDS_HEADER =
        "record_id,timestamp_utc,operation,dataset_name,point_count,input_bytes,output_bytes," +
            "duration_ms,min_distance_metres,max_distance_metres," +
            "recipient_count,signed,device_manufacturer,device_model,android_release,app_version"
    private const val LEGACY_HEADER =
        "record_id,timestamp_utc,operation,dataset_name,point_count,input_bytes,output_bytes," +
            "duration_nanos,duration_seconds,min_distance_metres,max_distance_metres," +
            "recipient_count,signed,device_manufacturer,device_model,android_release,app_version"
    private const val COLUMN_COUNT = 16
    private const val LEGACY_COLUMN_COUNT = 17
}
