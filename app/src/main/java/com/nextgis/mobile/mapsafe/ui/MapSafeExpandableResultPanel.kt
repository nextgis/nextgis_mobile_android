package com.nextgis.mobile.mapsafe.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout

/** A top-anchored result panel that can retract to expose almost the entire map. */
internal class MapSafeExpandableResultPanel(
    context: Context,
    private val title: String,
    onBack: () -> Unit,
    private val expandedHeight: Int,
    initiallyExpanded: Boolean
) {
    val body: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(MapSafeUi.PAGE)
    }

    val view: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(MapSafeUi.PAGE)
    }

    var isExpanded: Boolean = initiallyExpanded
        private set

    private val collapsedHeight = MapSafeUi.dp(context, COLLAPSED_HEIGHT_DP)
    private var heightAnimator: ValueAnimator? = null
    private val toggleButton = Button(context).apply {
        isAllCaps = false
        textSize = 14f
        setTextColor(MapSafeUi.GREEN_TEXT)
        background = MapSafeUi.rounded(context, Color.WHITE, MapSafeUi.BORDER, 6)
        gravity = Gravity.CENTER
        minHeight = MapSafeUi.dp(context, 44)
        setPadding(
            MapSafeUi.dp(context, 10),
            MapSafeUi.dp(context, 4),
            MapSafeUi.dp(context, 10),
            MapSafeUi.dp(context, 4)
        )
        setOnClickListener { setExpanded(!isExpanded, animate = true) }
    }

    init {
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                MapSafeUi.dp(context, 16),
                MapSafeUi.dp(context, 5),
                MapSafeUi.dp(context, 16),
                MapSafeUi.dp(context, 5)
            )
            setBackgroundColor(MapSafeUi.PAGE)
            addView(
                MapSafeUi.text(context, title, 17f, MapSafeUi.TEXT, bold = true),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(0, 0, MapSafeUi.dp(context, 8), 0)
                }
            )
            addView(
                toggleButton,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            setOnClickListener { setExpanded(!isExpanded, animate = true) }
        }

        view.addView(MapSafeUi.appBar(context, onBack))
        view.addView(header)
        view.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        view.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            if (initiallyExpanded) expandedHeight else collapsedHeight
        )
        body.visibility = if (initiallyExpanded) View.VISIBLE else View.GONE
        updateToggle()
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit

            override fun onViewDetachedFromWindow(view: View) {
                heightAnimator?.cancel()
                heightAnimator = null
            }
        })
    }

    fun setExpanded(expanded: Boolean, animate: Boolean) {
        if (expanded == isExpanded && !animate) {
            applyImmediately()
            return
        }
        if (expanded == isExpanded) return

        isExpanded = expanded
        updateToggle()
        heightAnimator?.cancel()
        heightAnimator = null

        if (!animate || view.height == 0) {
            applyImmediately()
            return
        }

        if (expanded) body.visibility = View.VISIBLE
        val startHeight = view.height
        val targetHeight = if (expanded) expandedHeight else collapsedHeight
        heightAnimator = ValueAnimator.ofInt(startHeight, targetHeight).apply {
            duration = ANIMATION_DURATION_MS
            addUpdateListener { animator ->
                view.layoutParams = view.layoutParams.apply {
                    height = animator.animatedValue as Int
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!isExpanded) body.visibility = View.GONE
                    heightAnimator = null
                }
            })
            start()
        }
    }

    private fun applyImmediately() {
        body.visibility = if (isExpanded) View.VISIBLE else View.GONE
        view.layoutParams = view.layoutParams.apply {
            height = if (isExpanded) expandedHeight else collapsedHeight
        }
        view.requestLayout()
    }

    private fun updateToggle() {
        toggleButton.text = if (isExpanded) "Collapse results  ▲" else "Expand results  ▼"
        toggleButton.contentDescription =
            if (isExpanded) "Collapse $title results" else "Expand $title results"
    }

    private companion object {
        const val COLLAPSED_HEIGHT_DP = 118
        const val ANIMATION_DURATION_MS = 220L
    }
}
