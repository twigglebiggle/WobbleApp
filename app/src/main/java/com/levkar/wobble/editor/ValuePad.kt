package com.levkar.wobble.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View

/**
 * One-thumb 2D control: X = wobbliness (0 left → 1 right), Y = extent (0 bottom → 1 top).
 * Both values visible at once (the original's rotating barrel showed one at a time).
 *
 * Relative, trackpad-style: touching doesn't move the dot, dragging does. Slow drags are fine
 * (a full pad width ≈ 0.25), fast flicks accelerate up to ~1.5 pad widths per value range.
 * Values snap to 0.01 steps.
 */
class ValuePad(context: Context) : View(context) {

    var wobbliness = 0.5f; private set
    var extent = 0.5f; private set
    var onChange: ((wobbliness: Float, extent: Float) -> Unit)? = null
    var onCommit: (() -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x66FFFFFF; strokeWidth = 1.5f * d }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22FFFFFF; strokeWidth = 1f * d }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xEEFFFFFF.toInt(); textSize = 12f * d; isFakeBoldText = true }
    private var lastX = 0f; private var lastY = 0f; private var lastT = 0L
    // Unsnapped values the drag accumulates into; what's shown and applied is snapped to 0.01.
    private var rawW = 0.5f; private var rawE = 0.5f

    fun set(wobbliness: Float, extent: Float) {
        this.wobbliness = wobbliness; this.extent = extent; rawW = wobbliness; rawE = extent; invalidate()
    }

    private fun snap(v: Float) = (Math.round(v * 100f) / 100f).coerceIn(0f, 1f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(h, h)                                    // always square
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val p = 6 * d
        c.drawARGB(0x66, 0, 0, 0)
        for (i in 1..3) { val f = i / 4f; c.drawLine(p + (w - 2 * p) * f, p, p + (w - 2 * p) * f, h - p, grid); c.drawLine(p, p + (h - 2 * p) * f, w - p, p + (h - 2 * p) * f, grid) }
        c.drawRect(p, p, w - p, h - p, frame)
        val x = p + (w - 2 * p) * wobbliness; val y = h - p - (h - 2 * p) * extent
        c.drawCircle(x, y, 6 * d, dot)
        c.drawText("wob %.2f".format(wobbliness), p + 3 * d, h - p - 4 * d, text)
        c.drawText("ext %.2f".format(extent), p + 3 * d, p + text.textSize + 2 * d, text)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = e.x; lastY = e.y; lastT = e.eventTime; parent?.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - lastX; val dy = e.y - lastY
                val dt = (e.eventTime - lastT).coerceAtLeast(1L)
                val speed = kotlin.math.hypot(dx, dy) / dt / d                // dp per ms
                // Pointer acceleration: 0.25 range per pad width when slow, up to 1.5 when flicking.
                val gain = (0.25f + speed * 0.9f).coerceAtMost(1.5f) / (width - 12 * d)
                rawW = (rawW + dx * gain).coerceIn(0f, 1f)
                rawE = (rawE - dy * gain).coerceIn(0f, 1f)
                val w = snap(rawW); val x = snap(rawE)
                if (w == wobbliness && x == extent) { lastX = e.x; lastY = e.y; lastT = e.eventTime; return true }
                wobbliness = w; extent = x
                lastX = e.x; lastY = e.y; lastT = e.eventTime
                invalidate()
                onChange?.invoke(wobbliness, extent)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onCommit?.invoke()
        }
        return true
    }
}
