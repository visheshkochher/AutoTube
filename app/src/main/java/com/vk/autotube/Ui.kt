package com.vk.autotube

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.TextView
import kotlin.math.roundToInt

/** Shared colours for the phone and car screens. */
object Palette {
    val BG: Int = Color.parseColor("#0B0D10")
    val SURFACE: Int = Color.parseColor("#161A1F")
    val CHIP: Int = Color.parseColor("#232830")
    val TEXT: Int = Color.WHITE
    val MUTED: Int = Color.parseColor("#9AA3AD")
    val ACCENT: Int = Color.parseColor("#FF3B47")
    val GLASS: Int = Color.argb(60, 255, 255, 255)
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

fun Context.label(text: String, sp: Float, color: Int, weight: Int = 400): TextView {
    val t = TextView(this)
    t.text = text
    t.setTextColor(color)
    t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    t.typeface = Typeface.create(Typeface.DEFAULT, weight, false)
    t.setLineSpacing(0f, 1.1f)
    return t
}

fun rounded(color: Int, radius: Float): GradientDrawable {
    val d = GradientDrawable()
    d.setCornerRadius(radius)
    d.setColor(color)
    return d
}

fun circle(color: Int): GradientDrawable {
    val d = GradientDrawable()
    d.setShape(GradientDrawable.OVAL)
    d.setColor(color)
    return d
}

/** Touch ripple over [content]; [mask] limits the ripple's shape when content is null. */
fun pressable(content: Drawable?, mask: Drawable? = null): RippleDrawable =
    RippleDrawable(ColorStateList.valueOf(Color.argb(56, 255, 255, 255)), content, mask)

fun View.clipToRounded() {
    outlineProvider = ViewOutlineProvider.BACKGROUND
    clipToOutline = true
}
