package com.nextgis.mobile.mapsafe.service

import android.graphics.Color
import com.nextgis.maplib.display.FieldStyleRule
import com.nextgis.maplib.display.RuleFeatureRenderer
import com.nextgis.maplib.display.SimpleFeatureRenderer
import com.nextgis.maplib.display.SimpleMarkerStyle
import com.nextgis.maplib.display.SimplePolygonStyle
import com.nextgis.maplib.map.VectorLayer

/** Consistent blue styling for generated MapSafe layers. */
object MapSafeLayerStyle {
    private val blue = Color.rgb(33, 150, 243)
    private val darkBlue = Color.rgb(13, 71, 161)
    private val hexbinDensityColors = intArrayOf(
        Color.rgb(187, 222, 251), // very low density
        Color.rgb(100, 181, 246),
        Color.rgb(33, 150, 243),
        Color.rgb(25, 118, 210),
        Color.rgb(13, 71, 161) // very high density
    )

    fun applyBluePointStyle(layer: VectorLayer) {
        val style = SimpleMarkerStyle(
            blue,
            darkBlue,
            8f,
            SimpleMarkerStyle.MarkerStyleCircle
        ).apply {
            width = 2f
        }
        layer.renderer = SimpleFeatureRenderer(layer, style)
    }

    fun applyBluePolygonStyle(layer: VectorLayer) {
        val style = SimplePolygonStyle(blue, darkBlue).apply {
            setAlpha(100)
            setOutAlpha(230)
            setWidth(2f)
            setFill(true)
        }
        layer.renderer = SimpleFeatureRenderer(layer, style)
    }

    /**
     * Applies a persisted graduated style driven by HexbinDensity.FIELD_NAME.
     * The workflow writes that class alongside point_count for each feature.
     */
    fun applyHexbinDensityStyle(layer: VectorLayer) {
        fun polygonStyle(color: Int) = SimplePolygonStyle(color, darkBlue).apply {
            setAlpha(160)
            setOutAlpha(230)
            setWidth(2f)
            setFill(true)
        }

        val baseStyle = polygonStyle(hexbinDensityColors.first())
        val rules = FieldStyleRule(layer).apply {
            setKey(HexbinDensity.FIELD_NAME)
            hexbinDensityColors.forEachIndexed { index, color ->
                setStyle(index.toString(), polygonStyle(color))
            }
        }
        layer.renderer = RuleFeatureRenderer(layer, rules, baseStyle)
    }
}
