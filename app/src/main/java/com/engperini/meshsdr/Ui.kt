package com.engperini.meshsdr

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/** Dark "night sky" palette. */
object Ui {
    val BG = Color.parseColor("#070B14")
    val SURFACE = Color.parseColor("#E6111A2B")
    val SURFACE_2 = Color.parseColor("#FF16233A")
    val OUTLINE = Color.parseColor("#FF24344F")
    val ACCENT = Color.parseColor("#FF22D3EE")
    val ACCENT_DARK = Color.parseColor("#FF0E7490")
    val ON_ACCENT = Color.parseColor("#FF04121A")
    val TEXT = Color.parseColor("#FFE6EDF7")
    val MUTED = Color.parseColor("#FF8DA2BE")
    val GREEN = Color.parseColor("#FF34D399")
    val AMBER = Color.parseColor("#FFFBBF24")
    val RED = Color.parseColor("#FFF87171")
}

// View builders for the code-built UI (no XML layouts, no extra libraries).

fun Context.dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dp(v: Int): Int = dp(v.toFloat())

fun Context.rounded(fill: Int, radiusDp: Float, stroke: Int = 0, strokeDp: Float = 0f) =
    GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
        if (strokeDp > 0f) setStroke(dp(strokeDp), stroke)
    }

/** Translucent rounded panel. */
fun Context.card(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = rounded(Ui.SURFACE, 20f, Ui.OUTLINE, 1f)
    setPadding(dp(14), dp(12), dp(14), dp(12))
}

fun Context.label(text: String, sizeSp: Float = 13f, color: Int = Ui.MUTED, bold: Boolean = false) =
    TextView(this).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

/** ACTIVE: something is running (e.g. a "Connected" button). */
enum class UiStyle { PRIMARY, OUTLINED, TONAL, TEXT, ACTIVE }

/** Rounded button with ripple; [restyle] switches style later (e.g. selected chips). */
fun Context.pill(text: String, style: UiStyle = UiStyle.TONAL, onClick: () -> Unit): Button =
    Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 14f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        stateListAnimator = null
        minHeight = dp(44)
        minimumHeight = dp(44)
        setPadding(dp(12), 0, dp(12), 0)
        restyle(this, style)
        setOnClickListener { onClick() }
    }

fun Context.restyle(button: Button, style: UiStyle) {
    val (fill, stroke, textColor) = when (style) {
        UiStyle.PRIMARY -> Triple(Ui.ACCENT, 0, Ui.ON_ACCENT)
        UiStyle.OUTLINED -> Triple(Color.TRANSPARENT, Ui.OUTLINE, Ui.TEXT)
        UiStyle.TONAL -> Triple(Ui.SURFACE_2, 0, Ui.TEXT)
        UiStyle.TEXT -> Triple(Color.TRANSPARENT, 0, Ui.ACCENT)
        UiStyle.ACTIVE -> Triple(Color.argb(40, 52, 211, 153), Ui.GREEN, Ui.GREEN)
    }
    val shape = rounded(fill, 22f, stroke, if (stroke != 0) 1f else 0f)
    button.background = RippleDrawable(ColorStateList.valueOf(Color.argb(60, 255, 255, 255)), shape,
        rounded(Color.WHITE, 22f))
    button.setTextColor(textColor)
}

/** Small selectable chip used for profiles and gain steps. */
fun Context.chip(text: String, onClick: () -> Unit): Button = pill(text, UiStyle.TONAL, onClick).apply {
    textSize = 13f
    minHeight = dp(38)
    minimumHeight = dp(38)
    setPadding(dp(8), 0, dp(8), 0)
}

fun Context.setChipSelected(button: Button, selected: Boolean) {
    val shape = if (selected) rounded(Color.argb(46, 34, 211, 238), 22f, Ui.ACCENT, 1.5f)
        else rounded(Ui.SURFACE_2, 22f, Ui.OUTLINE, 1f)
    button.background = RippleDrawable(ColorStateList.valueOf(Color.argb(60, 255, 255, 255)), shape,
        rounded(Color.WHITE, 22f))
    button.setTextColor(if (selected) Ui.ACCENT else Ui.TEXT)
}

/** Coloured dot + text, e.g. "● Recebendo". */
fun Context.statusChip(): TextView = TextView(this).apply {
    textSize = 12f
    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    gravity = Gravity.CENTER
    setPadding(dp(10), dp(5), dp(12), dp(5))
}

fun Context.setStatus(chip: TextView, text: String, color: Int) {
    chip.text = "●  $text"
    chip.setTextColor(color)
    chip.background = rounded(Color.argb(40, Color.red(color), Color.green(color), Color.blue(color)), 14f, color, 1f)
}

/** Stat tile: small caption over a big number. Returns the tile and its value view. */
fun Context.statTile(caption: String): Pair<LinearLayout, TextView> {
    val value = label("0", 22f, Ui.TEXT, bold = true)
    val tile = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Ui.SURFACE, 16f, Ui.OUTLINE, 1f)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        addView(label(caption, 11f))
        addView(value)
    }
    return tile to value
}

/** Horizontal level meter; tint is updated by [setMeter]. */
fun Context.meter(): ProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
    max = 100
    progress = 0
    val track = rounded(Ui.OUTLINE, 4f)
    val fill = ClipDrawable(rounded(Ui.ACCENT, 4f), Gravity.START, ClipDrawable.HORIZONTAL)
    progressDrawable = LayerDrawable(arrayOf<Drawable>(track, fill)).apply {
        setId(0, android.R.id.background)
        setId(1, android.R.id.progress)
    }
}

fun setMeter(bar: ProgressBar, percent: Int, color: Int) {
    bar.progress = percent.coerceIn(0, 100)
    bar.progressTintList = ColorStateList.valueOf(color)
}

fun row(context: Context, vararg views: View, weights: Boolean = true, gapDp: Int = 8): LinearLayout =
    LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        views.forEachIndexed { i, v ->
            val lp = if (weights) LinearLayout.LayoutParams(0, -2, 1f) else LinearLayout.LayoutParams(-2, -2)
            if (i > 0) lp.marginStart = (gapDp * context.resources.displayMetrics.density).toInt()
            addView(v, lp)
        }
    }

/** ScrollView that wraps its content up to [maxHeightPx], then scrolls. */
class MaxHeightScrollView(context: Context) : ScrollView(context) {
    var maxHeightPx = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val spec = if (maxHeightPx > 0) MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST) else heightMeasureSpec
        super.onMeasure(widthMeasureSpec, spec)
    }
}
