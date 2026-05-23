package com.nextgis.mobile.mapsafe.service

import com.nextgis.mobile.mapsafe.model.MapSafeRecord
import java.io.File
import java.util.UUID

/**
 * Creates local integrity records for protected spatial artifacts.
 */
object IntegrityRecordService {

    fun buildRecord(
        sourceFile: File,
        protectedName: String,
        method: String,
        notes: String? = null
    ): MapSafeRecord {
        return MapSafeRecord(
            id = UUID.randomUUID().toString(),
            originalName = sourceFile.name,
            protectedName = protectedName,
            method = method,
            createdAtMillis = System.currentTimeMillis(),
            contentHash = HashUtils.sha256(sourceFile),
            notes = notes
        )
    }
}
