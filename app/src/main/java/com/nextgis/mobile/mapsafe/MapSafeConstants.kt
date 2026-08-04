package com.nextgis.mobile.mapsafe

/**
 * Shared constants for the MapSafe mobile module.
 */
object MapSafeConstants {
    const val METHOD_DONUT_MASKING = "donut_masking"
    const val METHOD_HEXABINNING = "hexabinning"
    const val METHOD_ENCRYPT = "encrypt"
    const val METHOD_INTEGRITY_RECORD = "integrity_record"

    const val DEFAULT_H3_RESOLUTION = 8
    const val MASKED_LAYER_SUFFIX = "_masked"
    const val HEXBIN_LAYER_SUFFIX = "_hexbin"

    const val FIELD_SOURCE_FEATURE_ID = "mapsafe_source_id"
    const val FIELD_MASK_MIN_METRES = "mapsafe_min_m"
    const val FIELD_MASK_MAX_METRES = "mapsafe_max_m"
    const val FIELD_MASK_DISTANCE_METRES = "mapsafe_distance_m"
}
