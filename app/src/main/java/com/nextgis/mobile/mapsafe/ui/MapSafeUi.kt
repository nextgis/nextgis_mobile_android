package com.nextgis.mobile.mapsafe.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.nextgis.mobile.R

/** Shared visual language used only by MapSafe screens. */
object MapSafeUi {
    enum class SafeguardStep(val label: String) {
        ANONYMISE("Anonymise"),
        ENCRYPT("Encrypt"),
        NOTARISE("Notarise")
    }

    enum class AccessStep(val label: String) {
        VERIFY("Verify"),
        DECRYPT("Decrypt"),
        ACCESS("Access")
    }

    const val GREEN = 0xff256b2b.toInt()
    const val GREEN_DARK = 0xff174e20.toInt()
    const val GREEN_TEXT = 0xff174f25.toInt()
    const val GREEN_PALE = 0xffeef7ec.toInt()
    const val PAGE = 0xfff8faf8.toInt()
    const val BORDER = 0xffb8cab9.toInt()
    const val TEXT = 0xff202420.toInt()
    const val MUTED = 0xff5f665f.toInt()
    const val BLUE_PALE = 0xfff0f4ff.toInt()
    const val BLUE_BORDER = 0xffb7c8ef.toInt()

    fun configureActivity(activity: AppCompatActivity, subtitle: String? = null) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        activity.window.statusBarColor = GREEN_DARK
        activity.supportActionBar?.apply {
            title = "MapSafe Mobile"
            this.subtitle = subtitle
            setBackgroundDrawable(ColorDrawable(GREEN))
            setDisplayHomeAsUpEnabled(true)
            hide()
        }
    }

    fun activityFrame(
        context: Context,
        content: View,
        onBack: () -> Unit
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(GREEN_DARK)
        addView(appBar(context, onBack))
        addView(content, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, systemBars.top, 0, systemBars.bottom)
            insets
        }
    }

    fun appBar(context: Context, onBack: () -> Unit): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(context, 62)
            setPadding(dp(context, 4), dp(context, 5), dp(context, 10), dp(context, 5))
            setBackgroundColor(GREEN)
            addView(Button(context).apply {
                text = "← Back"
                contentDescription = "Back"
                isAllCaps = false
                textSize = 14f
                setTextColor(Color.WHITE)
                background = ColorDrawable(Color.TRANSPARENT)
                setOnClickListener { onBack() }
            }, LinearLayout.LayoutParams(dp(context, 90), dp(context, 52)))
            addView(text(context, "MapSafe Mobile", 19f, Color.WHITE, bold = true),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(logoIcon(context), LinearLayout.LayoutParams(dp(context, 44), dp(context, 44)))
        }

    fun logoWordmark(context: Context): ImageView = ImageView(context).apply {
        setImageResource(R.drawable.mapsafe_logo_full)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        adjustViewBounds = true
        contentDescription = "MapSafe full logo"
        setBackgroundColor(Color.WHITE)
        setPadding(dp(context, 4), dp(context, 4), dp(context, 4), dp(context, 4))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(context, 88)
        ).apply { setMargins(0, 0, 0, dp(context, 12)) }
    }

    fun logoIcon(context: Context): ImageView = ImageView(context).apply {
        setImageResource(R.drawable.mapsafe_logo)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        contentDescription = "MapSafe logo"
        setBackgroundColor(Color.WHITE)
        setPadding(dp(context, 3), dp(context, 3), dp(context, 3), dp(context, 3))
    }

    fun page(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(context, 16), dp(context, 18), dp(context, 16), dp(context, 28))
        setBackgroundColor(PAGE)
    }

    fun screenHeading(context: Context, title: String, description: String): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(context, title, 24f, TEXT, bold = true))
            addView(text(context, description, 15f, TEXT).apply {
                setPadding(0, dp(context, 4), 0, dp(context, 14))
            })
        }

    fun sectionTitle(context: Context, title: String): TextView =
        text(context, title, 14f, GREEN_TEXT, bold = true).apply {
            setPadding(0, 0, 0, dp(context, 6))
        }

    fun text(
        context: Context,
        value: String,
        sizeSp: Float = 14f,
        color: Int = TEXT,
        bold: Boolean = false
    ): TextView = TextView(context).apply {
        text = value
        textSize = sizeSp
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(0f, 1.12f)
    }

    fun card(context: Context, vararg children: View, pale: Boolean = false): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12))
            background = rounded(
                context,
                if (pale) GREEN_PALE else Color.WHITE,
                BORDER,
                radiusDp = 7
            )
            children.forEach(::addView)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(context, 12)) }
        }

    fun infoCard(context: Context, title: String, body: String): LinearLayout =
        card(
            context,
            text(context, "ⓘ  $title", 14f, 0xff324c97.toInt(), bold = true),
            text(context, body, 14f, TEXT).apply { setPadding(0, dp(context, 5), 0, 0) }
        ).apply {
            background = rounded(context, BLUE_PALE, BLUE_BORDER, radiusDp = 7)
        }

    fun valueRow(context: Context, label: String, value: String, strongValue: Boolean = false): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(context, 3), 0, dp(context, 3))
            addView(text(context, label, 14f, TEXT), LinearLayout.LayoutParams(0, -2, 1f))
            addView(text(context, value, 14f, TEXT, bold = strongValue))
        }

    fun primaryButton(context: Context, label: String, action: () -> Unit): Button = Button(context).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.WHITE)
        backgroundTintList = ColorStateList.valueOf(GREEN)
        minHeight = dp(context, 52)
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp(context, 4), 0, dp(context, 8)) }
    }

    fun outlineButton(context: Context, label: String, action: () -> Unit): Button = Button(context).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(GREEN_TEXT)
        backgroundTintList = null
        background = rounded(context, Color.WHITE, GREEN, radiusDp = 6, strokeWidthDp = 2)
        stateListAnimator = null
        elevation = 0f
        minHeight = dp(context, 46)
        setPadding(dp(context, 12), dp(context, 6), dp(context, 12), dp(context, 6))
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp(context, 4), 0, dp(context, 8)) }
    }

    fun compactOutlineButton(context: Context, label: String, action: () -> Unit): Button =
        Button(context).apply {
            text = label
            isAllCaps = false
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(GREEN_TEXT)
            backgroundTintList = null
            background = rounded(context, Color.WHITE, GREEN, radiusDp = 6, strokeWidthDp = 2)
            stateListAnimator = null
            elevation = 0f
            minHeight = dp(context, 36)
            minimumWidth = 0
            minWidth = 0
            setPadding(dp(context, 10), dp(context, 3), dp(context, 10), dp(context, 3))
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(context, 6), 0, dp(context, 6)) }
        }

    fun savedLocationRow(
        context: Context,
        locationText: TextView,
        onOpenFolder: () -> Unit
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(context, 6), 0, 0)
        addView(locationText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(
            compactOutlineButton(context, "Open Folder", onOpenFolder),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(context, 10), 0, 0, 0) }
        )
    }

    /** Two equal-width secondary actions used where vertical map space is limited. */
    fun pairedOutlineActions(
        context: Context,
        leftLabel: String,
        onLeft: () -> Unit,
        rightLabel: String,
        onRight: () -> Unit
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            outlineButton(context, leftLabel, onLeft),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, dp(context, 4), dp(context, 6), dp(context, 8))
            }
        )
        addView(
            outlineButton(context, rightLabel, onRight),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(context, 6), dp(context, 4), 0, dp(context, 8))
            }
        )
    }

    /** Consistent optional-workflow handoff: stop safely or continue to the next stage. */
    fun nextStopActions(
        context: Context,
        nextLabel: String,
        onNext: () -> Unit,
        onStop: () -> Unit
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            outlineButton(context, "Stop", onStop),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, dp(context, 6), 0)
            }
        )
        addView(
            primaryButton(context, nextLabel, onNext),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(context, 6), 0, 0, 0)
            }
        )
    }

    fun stepStrip(context: Context, active: Int, vararg labels: String): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(context, 12), 0, dp(context, 4))
            labels.forEachIndexed { index, label ->
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    val complete = index <= active
                    addView(text(
                        context,
                        if (index < active) "✓" else (index + 1).toString(),
                        15f,
                        if (complete) Color.WHITE else MUTED,
                        bold = true
                    ).apply {
                        gravity = Gravity.CENTER
                        background = rounded(
                            context,
                            if (complete) GREEN else 0xffd6d9d6.toInt(),
                            if (complete) GREEN else 0xffd6d9d6.toInt(),
                            radiusDp = 30
                        )
                        layoutParams = LinearLayout.LayoutParams(dp(context, 30), dp(context, 30))
                    })
                    addView(text(
                        context,
                        label,
                        12f,
                        if (complete) GREEN_TEXT else MUTED,
                        bold = complete
                    ).apply {
                        gravity = Gravity.CENTER
                        setPadding(0, dp(context, 4), 0, 0)
                    })
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
        }

    fun safeguardStepStrip(context: Context, active: SafeguardStep): LinearLayout {
        val steps = SafeguardStep.values()
        return stepStrip(context, active.ordinal, *steps.map { it.label }.toTypedArray()).apply {
            contentDescription =
                "Safeguard progress: ${active.ordinal + 1} of ${steps.size}, ${active.label}"
        }
    }

    fun accessStepStrip(context: Context, active: AccessStep): LinearLayout {
        val steps = AccessStep.values()
        return stepStrip(context, active.ordinal, *steps.map { it.label }.toTypedArray()).apply {
            contentDescription =
                "Access progress: ${active.ordinal + 1} of ${steps.size}, ${active.label}"
        }
    }

    fun divider(context: Context): View = View(context).apply {
        setBackgroundColor(0xffdde3dd.toInt())
        layoutParams = LinearLayout.LayoutParams(-1, dp(context, 1)).apply {
            setMargins(0, dp(context, 8), 0, dp(context, 8))
        }
    }

    fun rounded(
        context: Context,
        fill: Int,
        stroke: Int = fill,
        radiusDp: Int = 8,
        strokeWidthDp: Int = 1
    ): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(context, radiusDp).toFloat()
        setStroke(dp(context, strokeWidthDp), stroke)
    }

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
