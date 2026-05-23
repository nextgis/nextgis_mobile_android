package com.nextgis.mobile.mapsafe.model

/**
 * Metadata container for a protected MapSafe spatial artifact.
 *
 * This class is intentionally small and serialisation-agnostic for now.
 */
data class MapSafeRecord(
    val id: String,
    val originalName: String,
    val protectedName: String,
    val method: String,
    val createdAtMillis: Long,
    val contentHash: String? = null,
    val notes: String? = null
)
