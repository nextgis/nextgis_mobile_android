package com.nextgis.mobile.mapsafe.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.roundToInt

/** Compact two-thumb integer range control used by the halo-masking overlay. */
internal class MapSafeRangeSlider(
    context: Context,
    private val valueFrom: Int,
    private val valueTo: Int,
    private val stepSize: Int
) : View(context) {

    private val density = resources.displayMetrics.density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MapSafeUi.BORDER
        strokeWidth = 4f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val selectedTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MapSafeUi.GREEN
        strokeWidth = 5f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MapSafeUi.GREEN
        style = Paint.Style.FILL
    }
    private val thumbOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xffffffff.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }

    private val thumbRadius = 12f * density
    private var activeThumb = NO_THUMB
    private var valuesChanged: ((Int, Int) -> Unit)? = null

    var lowerValue: Int = valueFrom
        private set
    var upperValue: Int = valueTo
        private set

    init {
        require(valueTo > valueFrom)
        require(stepSize > 0)
        minimumHeight = (52f * density).roundToInt()
        isFocusable = true
        isClickable = true
    }

    fun setValues(lower: Int, upper: Int) {
        lowerValue = snap(lower).coerceAtMost(snap(upper))
        upperValue = snap(upper).coerceAtLeast(lowerValue)
        updateAccessibilityDescription()
        invalidate()
    }

    fun setOnValuesChangedListener(listener: (Int, Int) -> Unit) {
        valuesChanged = listener
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = (52f * density).roundToInt() + paddingTop + paddingBottom
        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val start = trackStart()
        val end = trackEnd()
        val centreY = height / 2f
        val lowerX = xForValue(lowerValue)
        val upperX = xForValue(upperValue)

        canvas.drawLine(start, centreY, end, centreY, trackPaint)
        canvas.drawLine(lowerX, centreY, upperX, centreY, selectedTrackPaint)
        drawThumb(canvas, lowerX, centreY)
        drawThumb(canvas, upperX, centreY)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val lowerDistance = abs(event.x - xForValue(lowerValue))
                val upperDistance = abs(event.x - xForValue(upperValue))
                activeThumb = when {
                    lowerDistance < upperDistance -> LOWER_THUMB
                    upperDistance < lowerDistance -> UPPER_THUMB
                    event.x <= xForValue(lowerValue) -> LOWER_THUMB
                    else -> UPPER_THUMB
                }
                updateFromTouch(event.x)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                updateFromTouch(event.x)
                return true
            }
            MotionEvent.ACTION_UP -> {
                updateFromTouch(event.x)
                activeThumb = NO_THUMB
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                activeThumb = NO_THUMB
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateFromTouch(x: Float) {
        if (activeThumb == NO_THUMB) return
        val value = valueForX(x)
        when (activeThumb) {
            LOWER_THUMB -> lowerValue = value.coerceAtMost(upperValue)
            UPPER_THUMB -> upperValue = value.coerceAtLeast(lowerValue)
        }
        updateAccessibilityDescription()
        valuesChanged?.invoke(lowerValue, upperValue)
        invalidate()
    }

    private fun drawThumb(canvas: Canvas, x: Float, y: Float) {
        canvas.drawCircle(x, y, thumbRadius, thumbPaint)
        canvas.drawCircle(x, y, thumbRadius - density, thumbOutlinePaint)
    }

    private fun valueForX(x: Float): Int {
        val fraction = ((x - trackStart()) / (trackEnd() - trackStart())).coerceIn(0f, 1f)
        return snap(valueFrom + ((valueTo - valueFrom) * fraction).roundToInt())
    }

    private fun xForValue(value: Int): Float {
        val fraction = (value - valueFrom).toFloat() / (valueTo - valueFrom).toFloat()
        return trackStart() + (trackEnd() - trackStart()) * fraction
    }

    private fun trackStart(): Float = paddingLeft + thumbRadius
    private fun trackEnd(): Float = width - paddingRight - thumbRadius

    private fun snap(value: Int): Int {
        val steps = ((value.coerceIn(valueFrom, valueTo) - valueFrom).toFloat() / stepSize)
            .roundToInt()
        return (valueFrom + steps * stepSize).coerceIn(valueFrom, valueTo)
    }

    private fun updateAccessibilityDescription() {
        contentDescription =
            "Masking distance range. Minimum $lowerValue metres. Maximum $upperValue metres."
    }

    private companion object {
        const val NO_THUMB = 0
        const val LOWER_THUMB = 1
        const val UPPER_THUMB = 2
    }
}
