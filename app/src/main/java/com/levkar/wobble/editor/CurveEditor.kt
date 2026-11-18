package com.levkar.wobble.editor

import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import com.levkar.wobble.core.Falloff
import com.levkar.wobble.ui.BigButton
import com.levkar.wobble.ui.Icon
import com.levkar.wobble.ui.Ui
import com.levkar.wobble.ui.uiText
import com.levkar.wobble.ui.UiMenu
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Small read-only cross-section of a bubble's falloff (the "Curve" row in the editor panel):
 * edge → peak → edge, i.e. the curve and its mirror, line only. Shows what actually moves: capped at the
 * the scale, capped at the ceiling. Above 100 % scale the drawing's top becomes the scale, with a faint 100 line.
 */
class CurvePreview(c: Context) : View(c) {
    private var pts = Falloff.seed(0.5f)
    private var ceiling = Falloff.CEIL_OFF; private var scale = 1f
    private val d = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.2f * d; color = Ui.ACCENT; strokeJoin = Paint.Join.ROUND }
    /** Line colour (blue for a Freeze zone). */
    var color: Int
        get() = line.color
        set(v) { line.color = v; invalidate() }
    private val base = Paint().apply { color = Ui.LINE; strokeWidth = 1f }
    private val hundred = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.LINE; strokeWidth = 1f; pathEffect = DashPathEffect(floatArrayOf(3 * d, 3 * d), 0f) }
    private val path = Path()
    fun set(p: FloatArray, ceiling: Float = Falloff.CEIL_OFF, scale: Float = 1f) { pts = p.copyOf(); this.ceiling = ceiling; this.scale = scale; invalidate() }
    override fun onDraw(c: Canvas) {
        val pad = 3 * d; val w = width - 2 * pad; val h = height - 2 * pad
        val top = maxOf(1f, scale)
        c.drawLine(pad, height - pad, width - pad, height - pad, base)
        if (top > 1f) { val y = pad + (1 - 1f / top) * h; c.drawLine(pad, y, width - pad, y, hundred) }
        path.reset()
        for (i in 0..96) {
            val x = i / 96f; val r = kotlin.math.abs(x * 2f - 1f)          // 0 at the middle (peak), 1 at both edges
            val y = minOf(Falloff.value(pts, r) * scale, ceiling) / top
            val sx = pad + x * w; val sy = pad + (1 - y) * h
            if (i == 0) path.moveTo(sx, sy) else path.lineTo(sx, sy)
        }
        c.drawPath(path, line)
    }
}

/**
 * The falloff curve editor. Left = the bubble's peak, right = its edge, height = how strongly that
 * ring of pixels follows the wobble. Drag a point to move it; tap the line to add a point; tap a point
 * (without moving) to remove it. End points can't be removed and only move up/down; middle points
 * can't pass their neighbours. The grey dashed line is the bubble's Extent shape, for reference.
 */
class CurveView(c: Context) : View(c) {
    var pts: FloatArray = Falloff.seed(0.5f); private set
    var extent = 0.5f
        set(v) { field = v; invalidate() }
    /** Live, on every change. */
    var onChange: ((FloatArray) -> Unit)? = null
    /** End of a gesture that changed something: one undo step. */
    var onCommit: (() -> Unit)? = null
    /** Absolute motion cap (Falloff.CEIL_OFF = off), moved with the diamond on the rail at the right. Live. */
    var ceiling = Falloff.CEIL_OFF
        set(v) { field = v; invalidate() }
    /**
     * Scale (the bubble's height): the graph shows motion, so the curve visibly squeezes below 100 (Lev).
     * Above 100 the graph's top grows to the scale instead, so the curve keeps its full height (no precision lost).
     */
    var scale = 1f
        set(v) { field = v; invalidate() }
    /** The curve line's colour (blue for a Freeze zone; everything else in the sheet stays amber). */
    var lineColor: Int
        get() = line.color
        set(v) { line.color = v; rawLine.color = (v and 0x00FFFFFF) or 0x4D000000; invalidate() }
    private val top get() = maxOf(1f, scale)
    private val s get() = scale.coerceAtLeast(0.01f)
    var onCeiling: ((Float) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(c).scaledTouchSlop.toFloat()
    private val hit = 24 * d
    private val pad = 18 * d
    private val grid = Paint().apply { color = 0xFF24262A.toInt(); strokeWidth = 1f }
    private val ghost = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.4f * d; color = Ui.MUTED; pathEffect = DashPathEffect(floatArrayOf(4 * d, 4 * d), 0f)
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f * d; color = Ui.ACCENT; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val dotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val endFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.BG }
    private val dotRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d; color = Ui.BG }
    private val endRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d; color = Color.WHITE }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.accentA(0x2E) }
    private val haloRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = Ui.ACCENT }
    private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.MUTED; textSize = 10 * d; runCatching { typeface = Ui.mono } }
    private val path = Path()
    private val rawLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * d; color = Ui.accentA(0x4D); pathEffect = DashPathEffect(floatArrayOf(3 * d, 4 * d), 0f) }
    private val ceilLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = Ui.ACCENT; pathEffect = DashPathEffect(floatArrayOf(7 * d, 5 * d), 0f) }
    private val ceilText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.ACCENT; textSize = 10 * d; runCatching { typeface = Ui.monoMedium } }
    private val railPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3A3C41.toInt(); strokeWidth = 4 * d; strokeCap = Paint.Cap.ROUND }
    private val diamondFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.BG }
    private val diamondRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * d; color = Ui.ACCENT }
    private val railW = 40 * d
    private val hundred = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f * d; color = Ui.MUTED; pathEffect = DashPathEffect(floatArrayOf(3 * d, 4 * d), 0f) }
    private val railX get() = width - pad - railW / 2f + 6 * d

    fun set(p: FloatArray) { pts = p.copyOf(); invalidate() }

    private val gw get() = width - 2 * pad - railW
    private val gh get() = height - 2 * pad - 14 * d                       // room for the axis labels
    private fun sx(x: Float) = pad + x * gw
    private fun sy(m: Float) = pad + (1 - m / top) * gh                     // m = motion, 0..top
    private fun vx(px: Float) = ((px - pad) / gw).coerceIn(0f, 1f)
    private fun vy(py: Float) = ((1 - (py - pad) / gh) * top).coerceIn(0f, top)
    private val count get() = pts.size / 2

    override fun onDraw(c: Canvas) {
        for (i in 0..4) {
            c.drawLine(sx(i / 4f), sy(top), sx(i / 4f), sy(0f), grid)
            c.drawLine(sx(0f), sy(top * i / 4f), sx(1f), sy(top * i / 4f), grid)
        }
        if (top > 1f) {                                       // stretched: mark 100 and the top value
            c.drawLine(sx(0f), sy(1f), sx(1f), sy(1f), hundred)
            axis.textAlign = Paint.Align.RIGHT
            c.drawText("100", sx(1f) - 4 * d, sy(1f) - 4 * d, axis)
            c.drawText(Math.round(top * 100).toString(), sx(1f) - 4 * d, sy(top) + 12 * d, axis)
            axis.textAlign = Paint.Align.LEFT
        }
        path.reset()
        for (i in 0..60) { val x = i / 60f; val y = Falloff.extentValue(extent, x) * scale; if (i == 0) path.moveTo(sx(x), sy(y)) else path.lineTo(sx(x), sy(y)) }
        c.drawPath(path, ghost)
        val capped = ceiling < scale                          // the cap actually bites
        if (ceiling < Falloff.CEIL_OFF) {
            c.drawLine(sx(0f), sy(minOf(ceiling, top)), sx(1f), sy(minOf(ceiling, top)), ceilLine)
            c.drawText("ceiling ${Math.round(ceiling * 100)}", sx(0f) + 4 * d, sy(minOf(ceiling, top)) - 6 * d, ceilText)
        }
        if (capped) {                                         // the uncapped shape, faint, so points above the cap still make sense
            path.reset()
            for (i in 0..120) { val x = i / 120f; val y = Falloff.value(pts, x) * scale; if (i == 0) path.moveTo(sx(x), sy(y)) else path.lineTo(sx(x), sy(y)) }
            c.drawPath(path, rawLine)
        }
        path.reset()
        for (i in 0..120) { val x = i / 120f; val y = minOf(Falloff.value(pts, x) * scale, ceiling); if (i == 0) path.moveTo(sx(x), sy(y)) else path.lineTo(sx(x), sy(y)) }
        c.drawPath(path, line)
        // Ceiling rail: diamond = the cap; at the top = off.
        c.drawLine(railX, sy(top), railX, sy(0f), railPaint)
        val cy = sy(minOf(ceiling, top)); val r = (if (dragCeil) 13 else 10) * d
        path.reset(); path.moveTo(railX, cy - r); path.lineTo(railX + r, cy); path.lineTo(railX, cy + r); path.lineTo(railX - r, cy); path.close()
        c.drawPath(path, diamondFill); c.drawPath(path, diamondRing)
        for (i in 0 until count) {
            val x = sx(pts[2 * i]); val y = sy(pts[2 * i + 1] * scale)
            if (i == drag) { c.drawCircle(x, y, 17 * d, halo); c.drawCircle(x, y, 17 * d, haloRing) }
            val end = i == 0 || i == count - 1
            c.drawCircle(x, y, 7.5f * d, if (end) endFill else dotFill)
            c.drawCircle(x, y, 7.5f * d, if (end) endRing else dotRing)
        }
        c.drawText("peak", sx(0f), height - pad + 6 * d, axis)
        axis.textAlign = Paint.Align.RIGHT; c.drawText("edge", sx(1f), height - pad + 6 * d, axis); axis.textAlign = Paint.Align.LEFT
        c.drawText("motion", sx(0f) + 4 * d, sy(top) + 12 * d, axis)
    }

    private var drag = -1
    private var dragCeil = false
    private var downX = 0f; private var downY = 0f
    private var moved = false
    private var changed = false

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = e.x; downY = e.y; moved = false; changed = false
                dragCeil = abs(e.x - railX) < railW / 2f + 6 * d           // the whole rail column grabs the diamond
                drag = if (dragCeil) -1 else nearestPoint(e.x, e.y)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!moved && hypot(e.x - downX, e.y - downY) < slop) return true
                moved = true
                if (dragCeil) {
                    // (ceiling is absolute: it stays where it is when Scale changes)
                    val v = vy(e.y).let { if (it > top * 0.97f) Falloff.CEIL_OFF else Math.round(it * 100f) / 100f }   // near the top snaps to off
                    if (v != ceiling) { ceiling = v; onCeiling?.invoke(v); changed = true }
                } else if (drag >= 0) { movePoint(drag, vx(e.x), (vy(e.y) / s).coerceIn(0f, 1f)); changed = true }
            }
            MotionEvent.ACTION_UP -> {
                if (!moved && !dragCeil) {
                    if (drag > 0 && drag < count - 1) { removePoint(drag); changed = true }
                    else if (drag < 0) {
                        val x = vx(e.x)
                        if (abs(sy(Falloff.value(pts, x) * scale) - e.y) < hit && addPoint(x)) changed = true
                    }
                }
                drag = -1; dragCeil = false; invalidate()
                if (changed) onCommit?.invoke()
            }
            MotionEvent.ACTION_CANCEL -> { drag = -1; dragCeil = false; invalidate(); if (changed) onCommit?.invoke() }
        }
        return true
    }

    private fun nearestPoint(px: Float, py: Float): Int {
        var best = -1; var bestD = hit
        for (i in 0 until count) {
            val dd = hypot(sx(pts[2 * i]) - px, sy(pts[2 * i + 1] * scale) - py)
            if (dd < bestD) { bestD = dd; best = i }
        }
        return best
    }

    private fun movePoint(i: Int, x: Float, y: Float) {
        val p = pts.copyOf()
        p[2 * i + 1] = y
        if (i > 0 && i < count - 1) p[2 * i] = x.coerceIn(p[2 * (i - 1)] + MIN_GAP, p[2 * (i + 1)] - MIN_GAP)
        pts = p; invalidate(); onChange?.invoke(p.copyOf())
    }

    private fun removePoint(i: Int) {
        val l = pts.toMutableList(); l.removeAt(2 * i + 1); l.removeAt(2 * i)
        pts = l.toFloatArray(); invalidate(); onChange?.invoke(pts.copyOf())
    }

    private fun addPoint(x: Float): Boolean {
        if (count >= MAX_POINTS) return false
        var at = 1
        while (at < count && pts[2 * at] < x) at++
        if (x - pts[2 * (at - 1)] < MIN_GAP || pts[2 * at] - x < MIN_GAP) return false
        val l = pts.toMutableList(); l.add(2 * at, Falloff.value(pts, x)); l.add(2 * at, x)
        pts = l.toFloatArray(); invalidate(); onChange?.invoke(pts.copyOf())
        return true
    }

    companion object { private const val MIN_GAP = 0.03f; private const val MAX_POINTS = 12 }
}

/** Bottom sheet holding the curve editor. No dim: the bubble above stays visible and updates live. */
class CurveSheet(c: Context, curve: FloatArray, extent: Float, ceiling: Float, scale: Float, freeze: Boolean,
                 onChange: (FloatArray) -> Unit, onCeiling: (Float) -> Unit, onScale: (Float) -> Unit,
                 onCommit: () -> Unit, private val onReset: () -> FloatArray?) : Dialog(c) {
    val view = CurveView(c).apply {
        set(curve); this.extent = extent; this.ceiling = ceiling; this.scale = scale
        if (freeze) lineColor = Ui.FREEZE
        this.onChange = onChange; this.onCommit = onCommit; this.onCeiling = onCeiling
    }
    /** Scale: the whole curve × 0–150 %, on its own row so the graph never loses precision (Lev). */
    private val scaleRow = PercentRow(c, "Scale", 150, onChange = { v -> view.scale = v; onScale(v) }, onCommit = onCommit).apply { set(scale) }

    init {
        Ui.init(c)
        val d = c.resources.displayMetrics.density
        val pad = (14 * d).toInt()
        val root = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, (18 * d).toInt())
            background = GradientDrawable().apply {
                setColor(Ui.PANEL); setStroke(1, Ui.LINE)
                cornerRadii = floatArrayOf(16 * d, 16 * d, 16 * d, 16 * d, 0f, 0f, 0f, 0f)
            }
            addView(LinearLayout(c).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(c.uiText(if (freeze) "Freeze edge" else "Falloff curve", 16f, Ui.uiSemi), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(c.uiText("Done", 13f, Ui.uiSemi, Ui.ON_ACCENT).apply {
                    gravity = Gravity.CENTER; val h = (14 * d).toInt(); setPadding(h, 0, h, 0)
                    background = Ui.pressable(c, Ui.box(c, Ui.ACCENT, 7f), 7f, 0x33000000)
                    isClickable = true; setOnClickListener { dismiss() }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (34 * d).toInt()))
            })
            addView(view.apply { background = Ui.box(c, Ui.FIELD, 10f, 0xFF2F3136.toInt()) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (220 * d).toInt()).apply { topMargin = (10 * d).toInt() })
            if (!freeze) addView(scaleRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (40 * d).toInt()).apply { topMargin = (6 * d).toInt() })
            // Hint lines: bold parts are the gestures/handles.
            for (parts in listOf(
                listOf("Drag" to true, " a point · " to false, "tap the line" to true, " to add · " to false, "tap a point" to true, " to remove" to false),
                listOf("Diamond" to true, ": ceiling, nothing moves more than its line (top = off)" to false),
                listOf("Scale" to true, ": squeeze or stretch the whole curve, 0–150" to false)).let { if (freeze) it.dropLast(1) else it }) {
                addView(c.uiText(android.text.SpannableStringBuilder().apply {
                    for ((t, bold) in parts) if (bold) append(t, android.text.style.ForegroundColorSpan(Ui.TEXT), 0) else append(t)
                }, 12.5f, Ui.ui, Ui.TEXT2), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (6 * d).toInt() })
            }
            addView(BigButton(c, Icon.UNDO, "Reset to Basic").apply {
                setOnClickListener { onReset()?.let { view.set(it); view.ceiling = Falloff.CEIL_OFF } }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (44 * d).toInt()).apply { topMargin = (14 * d).toInt() })
        }
        setContentView(root)
        UiMenu.unrollDialog(this, root, fromBottom = true)
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
    }
}
