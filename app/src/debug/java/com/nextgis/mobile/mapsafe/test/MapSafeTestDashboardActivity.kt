package com.nextgis.mobile.mapsafe.test

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Debug-only visible surface used while Tier 1 device workflows execute. */
class MapSafeTestDashboardActivity : AppCompatActivity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "MapSafe Tier 1 Test"

        status = TextView(this).apply {
            text = "[READY] Waiting for the device workflow..."
            textSize = 16f
            setTextColor(Color.rgb(25, 85, 45))
            setPadding(dp(20), dp(16), dp(20), dp(24))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(TextView(this@MapSafeTestDashboardActivity).apply {
                text = "MapSafe Tier 1 Device Workflow"
                textSize = 24f
                setTextColor(Color.rgb(20, 70, 130))
                setPadding(dp(20), dp(24), dp(20), dp(8))
            })
            addView(TextView(this@MapSafeTestDashboardActivity).apply {
                text = "Production stages are marked PASS. Controlled external-service substitutes are marked SIMULATED."
                textSize = 14f
                setPadding(dp(20), 0, dp(20), dp(8))
            })
            addView(status)
        }
        setContentView(ScrollView(this).apply { addView(content) })
    }

    fun appendStatus(line: String) {
        status.append("\n$line")
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
