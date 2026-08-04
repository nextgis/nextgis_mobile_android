package com.nextgis.mobile.mapsafe.service

import android.graphics.Color
import com.nextgis.maplib.display.SimpleFeatureRenderer
import com.nextgis.maplib.display.SimpleMarkerStyle
import com.nextgis.maplib.display.SimplePolygonStyle
import com.nextgis.maplib.map.VectorLayer

/** Consistent blue styling for generated MapSafe layers. */
object MapSafeLayerStyle {
    private val blue = Color.rgb(33, 150, 243)
    private val darkBlue = Color.rgb(13, 71, 161)

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
}
