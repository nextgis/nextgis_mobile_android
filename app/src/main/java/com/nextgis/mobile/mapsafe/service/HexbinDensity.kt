package com.nextgis.mobile.mapsafe.service

import kotlin.math.ln
import kotlin.math.floor

/**
 * Graduated density classes used to render hexagons without letting one very
 * large cell flatten all of the other colours. Counts are classified on a
 * logarithmic scale because real point distributions are usually skewed.
 */
object HexbinDensity {
    const val CLASS_COUNT = 5
    const val FIELD_NAME = "density_class"

    fun classForCount(count: Int, maximum: Int): Int {
        require(count > 0) { "A hexbin count must be positive." }
        require(maximum > 0) { "The maximum hexbin count must be positive." }
        require(count <= maximum) { "A hexbin count cannot exceed the maximum." }

        if (maximum == 1) return 0

        val normalized = ln(count.toDouble()) / ln(maximum.toDouble())
        return floor(normalized * CLASS_COUNT)
            .toInt()
            .coerceIn(0, CLASS_COUNT - 1)
    }
}
