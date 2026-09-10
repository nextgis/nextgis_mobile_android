package com.nextgis.mobile.mapsafe

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nextgis.mobile.MainApplication
import com.nextgis.mobile.mapsafe.service.MapSafePerformanceLogRepository
import com.nextgis.mobile.mapsafe.service.MapSafeSampleDataWorkflow
import com.nextgis.mobile.mapsafe.service.MapSafeSaveFolderRepository
import com.nextgis.mobile.mapsafe.service.MapSafeWorkflowRunner
import com.nextgis.mobile.mapsafe.test.MapSafeTestDocumentProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MapSafePerformanceLogDeviceTest {

    @Test
    fun appendsOneHeaderAndPreservesEveryTimingRow() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val output = MapSafeTestDocumentProvider.file(
            context,
            MapSafePerformanceLogRepository.FILE_NAME
        )
        output.delete()
        MapSafeSaveFolderRepository.configureDebugFolder(
            context,
            MapSafeTestDocumentProvider.uri(context, "performance-log-root"),
            "MapSafe Test Save Folder"
        )

        try {
            MapSafePerformanceLogRepository.append(
                context,
                listOf(
                    MapSafePerformanceLogRepository.Record(
                        operation = MapSafePerformanceLogRepository.Operation.MASK_WITHOUT_SPRUILL,
                        datasetName = "field, 50.geojson",
                        pointCount = 50,
                        durationNanos = 1_250_000L,
                        minDistanceMetres = 100.0,
                        maxDistanceMetres = 2_000.0,
                        completedAtMillis = 0L
                    )
                )
            )
            MapSafePerformanceLogRepository.append(
                context,
                listOf(
                    MapSafePerformanceLogRepository.Record(
                        operation = MapSafePerformanceLogRepository.Operation.OPENPGP_ENCRYPT,
                        datasetName = "field, 50.geojson",
                        pointCount = 50,
                        inputBytes = 40_718L,
                        outputBytes = 15_481L,
                        durationNanos = 293_160_000L,
                        recipientCount = 2,
                        signed = true,
                        completedAtMillis = 1_000L
                    )
                )
            )

            val lines = output.readLines()
            assertEquals(3, lines.size)
            assertEquals(1, lines.count { it.startsWith("record_id,timestamp_utc,operation") })
            assertTrue(lines[1].contains("mask_without_spruill"))
            assertTrue(lines[2].contains("openpgp_encrypt_signed"))
            assertTrue(lines[1].contains("\"field, 50.geojson\""))
            assertTrue(lines[0].contains(",duration_seconds,"))
            assertTrue(!lines[0].contains("duration_nanos"))
            assertTrue(!lines[0].contains("duration_ms"))
            assertTrue(lines[2].contains(",40718,15481,0.293160,"))
        } finally {
            output.delete()
            MapSafeSaveFolderRepository.clear(context)
        }
    }

    @Test
    fun migratesLegacyDurationsIntoSecondsBeforeAppending() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val output = MapSafeTestDocumentProvider.file(
            context,
            MapSafePerformanceLogRepository.FILE_NAME
        )
        output.parentFile?.mkdirs()
        output.writeText(
            "record_id,timestamp_utc,operation,dataset_name,point_count,input_bytes,output_bytes," +
                "duration_nanos,duration_seconds,min_distance_metres,max_distance_metres," +
                "recipient_count,signed,device_manufacturer,device_model,android_release,app_version\n" +
                "legacy-id,1970-01-01T00:00:00.000Z,mask_without_spruill,field.geojson,50,,," +
                "1250000,0.001250000,100.000,2000.000,,,Google,Pixel,16,3.2.1-DEBUG\n"
        )
        MapSafeSaveFolderRepository.configureDebugFolder(
            context,
            MapSafeTestDocumentProvider.uri(context, "performance-log-migration-root"),
            "MapSafe Test Save Folder"
        )

        try {
            MapSafePerformanceLogRepository.append(
                context,
                listOf(
                    MapSafePerformanceLogRepository.Record(
                        operation = MapSafePerformanceLogRepository.Operation.OPENPGP_DECRYPT_VERIFY,
                        datasetName = "field.geojson",
                        durationNanos = 2_500_000L,
                        completedAtMillis = 1_000L
                    )
                )
            )

            val lines = output.readLines()
            assertEquals(3, lines.size)
            assertTrue(lines[0].contains(",duration_seconds,"))
            assertTrue(lines[1].contains(",0.001250,100.000,2000.000,"))
            assertTrue(lines[2].contains(",0.002500,"))
        } finally {
            output.delete()
            MapSafeSaveFolderRepository.clear(context)
        }
    }

    @Test
    fun migratesMillisecondDurationsIntoSecondsBeforeAppending() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val output = MapSafeTestDocumentProvider.file(
            context,
            MapSafePerformanceLogRepository.FILE_NAME
        )
        output.parentFile?.mkdirs()
        output.writeText(
            "record_id,timestamp_utc,operation,dataset_name,point_count,input_bytes,output_bytes," +
                "duration_ms,min_distance_metres,max_distance_metres,recipient_count,signed," +
                "device_manufacturer,device_model,android_release,app_version\n" +
                "millisecond-id,1970-01-01T00:00:00.000Z,mask_with_spruill," +
                "field.geojson,50,,,1250.500,100.000,2000.000,,,Google,Pixel,16," +
                "3.2.1-DEBUG\n"
        )
        MapSafeSaveFolderRepository.configureDebugFolder(
            context,
            MapSafeTestDocumentProvider.uri(context, "performance-log-ms-migration-root"),
            "MapSafe Test Save Folder"
        )

        try {
            MapSafePerformanceLogRepository.append(
                context,
                listOf(
                    MapSafePerformanceLogRepository.Record(
                        operation = MapSafePerformanceLogRepository.Operation.OPENPGP_DECRYPT_VERIFY,
                        datasetName = "field.geojson",
                        durationNanos = 2_500_000L,
                        completedAtMillis = 1_000L
                    )
                )
            )

            val lines = output.readLines()
            assertEquals(3, lines.size)
            assertTrue(lines[0].contains(",duration_seconds,"))
            assertTrue(lines[1].contains(",1.250500,100.000,2000.000,"))
            assertTrue(lines[2].contains(",0.002500,"))
        } finally {
            output.delete()
            MapSafeSaveFolderRepository.clear(context)
        }
    }

    @Test
    fun productionMaskingAddsCoreAndEndToEndRowsFromOneCandidate() {
        val context = ApplicationProvider.getApplicationContext<MainApplication>()
        val output = MapSafeTestDocumentProvider.file(
            context,
            MapSafePerformanceLogRepository.FILE_NAME
        )
        output.delete()
        MapSafeSaveFolderRepository.configureDebugFolder(
            context,
            MapSafeTestDocumentProvider.uri(context, "masking-performance-log-root"),
            "MapSafe Test Save Folder"
        )

        try {
            val sample = MapSafeSampleDataWorkflow.createSampleLayer(context, context)
            val result = MapSafeWorkflowRunner.runDonutMasking(
                context = context,
                app = context,
                selectedLayer = sample.layer,
                minDistanceMetres = 100.0,
                maxDistanceMetres = 2_000.0
            )
            assertTrue(result is MapSafeWorkflowRunner.WorkflowMessage.Success)
            MapSafeDeviceTestSupport.waitUntil("three production masking timing rows", 10_000L) {
                output.takeIf(java.io.File::isFile)?.readText()?.let { csv ->
                    csv.contains("mask_without_spruill") &&
                        csv.contains("mask_with_spruill") &&
                        csv.contains("mask_workflow_total")
                } == true
            }

            val rows = output.readLines()
            assertEquals(4, rows.size)
            assertTrue(rows[1].contains(sample.layerName))
            assertTrue(rows[2].contains(sample.layerName))
            assertTrue(rows[3].contains(sample.layerName))
            assertTrue(rows[1].contains(",30,,,"))
            assertTrue(rows[1].contains(",100.000,2000.000,"))
            assertTrue(rows[2].contains(",100.000,2000.000,"))
            assertTrue(rows[3].contains(",100.000,2000.000,"))
        } finally {
            output.delete()
            MapSafeSaveFolderRepository.clear(context)
        }
    }
}
