package com.vk.autotube.car

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import com.vk.autotube.Palette

/** Thin progress bar with a knob. Tap anywhere on it to jump there. */
class SeekBarView(ctx: Context) : View(ctx) {

    var progress = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    var onSeek: ((Float) -> Unit)? = null

    private val density = ctx.resources.displayMetrics.density
    private val trackHeight = 4f * density
    private val knobRadius = 7f * density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 255, 255, 255) }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.ACCENT }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = knobRadius
        val right = width - knobRadius
        if (right <= left) return
        val cy = height / 2f
        val half = trackHeight / 2f
        val x = left + (right - left) * progress
        canvas.drawRoundRect(left, cy - half, right, cy + half, half, half, trackPaint)
        canvas.drawRoundRect(left, cy - half, x, cy + half, half, half, fillPaint)
        canvas.drawCircle(x, cy, knobRadius, knobPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                val span = width - 2f * knobRadius
                if (span > 0f) {
                    val f = ((event.x - knobRadius) / span).coerceIn(0f, 1f)
                    progress = f
                    onSeek?.invoke(f)
                }
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
