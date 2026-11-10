package com.vk.autotube.car

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import com.vk.autotube.Palette

/**
 * Progress bar with a knob. Tap anywhere on it to jump there, or drag the knob.
 * The view is taller than the bar it draws, so it's easy to hit on a car screen.
 */
class SeekBarView(ctx: Context) : View(ctx) {

    var progress = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    /** True while a finger is on the bar; outside updates should wait. */
    var dragging = false
        private set

    var onSeek: ((Float) -> Unit)? = null

    private val density = ctx.resources.displayMetrics.density
    private val trackHeight = 5f * density
    private val knobRadius = 9f * density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(80, 255, 255, 255) }
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
        canvas.drawCircle(x, cy, if (dragging) knobRadius * 1.3f else knobRadius, knobPaint)
    }

    private fun fractionAt(x: Float): Float {
        val span = width - 2f * knobRadius
        if (span <= 0f) return progress
        return ((x - knobRadius) / span).coerceIn(0f, 1f)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                progress = fractionAt(event.x)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                progress = fractionAt(event.x)
                return true
            }
            MotionEvent.ACTION_UP -> {
                progress = fractionAt(event.x)
                dragging = false
                onSeek?.invoke(progress)
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                invalidate()
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
