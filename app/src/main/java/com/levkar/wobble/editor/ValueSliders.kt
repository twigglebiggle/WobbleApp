package com.levkar.wobble.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import com.levkar.wobble.ui.Ui

/**
 * Wobbliness and extent as two full-width sliders, each with a 0–100 field (whole percent).
 *
 * Sliders are relative, trackpad-style: touching does nothing, only a deliberate sideways drag
 * (past the touch slop) moves the value, so stray thumbs while poking the preview don't change it.
 * Slow drags ≈ 0.35 of the range per track width, flicks up to 1.5. Values snap to 0.01; a stored
 * value with more decimals (old files) is only replaced once that slider is actually changed.
 */
class ValueSliders(context: Context) : LinearLayout(context) {

    /** [which]: 0 = wobbliness, 1 = extent. Called live while dragging. */
    var onChange: ((which: Int, value: Float) -> Unit)? = null
    /** End of a drag or a typed value: one undo step. */
    var onCommit: (() -> Unit)? = null
    /** A slider drag started / ended (used to fade in the selected outline during preview). */
    var onDragging: ((Boolean) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val rows = arrayOf(0, 1).map { which ->
        PercentRow(context, if (which == 0) "Wobble" else "Extent", 100,
            onChange = { v -> onChange?.invoke(which, v) }, onCommit = { onCommit?.invoke() }, onDragging = { onDragging?.invoke(it) })
    }

    /** Falloff = Curve: tap the curve row to open the editor. */
    var onEditCurve: (() -> Unit)? = null
    private val curvePreview = CurvePreview(context)
    private val curveRow = LinearLayout(context).apply {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        background = Ui.pressable(context, null, 6f)
        addView(TextView(context).apply {
            text = "CURVE"; textSize = 11f; typeface = Ui.ui; letterSpacing = 0.08f; setTextColor(Ui.MUTED)
        }, LayoutParams((60 * d).toInt(), LayoutParams.WRAP_CONTENT))
        addView(curvePreview, LayoutParams(0, (28 * d).toInt(), 1f))
        addView(TextView(context).apply {
            text = "Edit ›"; textSize = 12f; typeface = Ui.ui; setTextColor(Ui.ACCENT); gravity = Gravity.CENTER
            background = Ui.box(context, 0, 6f, Ui.ACCENT)
            val h = (10 * d).toInt(); setPadding(h, 0, h, 0)
        }, LayoutParams(LayoutParams.WRAP_CONTENT, (28 * d).toInt()).apply { marginStart = (8 * d).toInt() })
        setOnClickListener { onEditCurve?.invoke() }
        visibility = GONE
    }

    init {
        orientation = VERTICAL
        for ((i, r) in rows.withIndex()) {
            if (i > 0) addView(View(context).apply { setBackgroundColor(Ui.LINE_SOFT) }, LayoutParams(LayoutParams.MATCH_PARENT, 1))
            addView(r, LayoutParams(LayoutParams.MATCH_PARENT, (40 * d).toInt()))
        }
        addView(curveRow, LayoutParams(LayoutParams.MATCH_PARENT, (40 * d).toInt()))
    }

    /** [curve] non-null: the Extent row becomes a cross-section preview of this curve (with its ceiling and scale); null: the Extent slider. */
    fun showCurve(curve: FloatArray?, ceiling: Float = com.levkar.wobble.core.Falloff.CEIL_OFF, scale: Float = 1f) {
        rows[1].visibility = if (curve == null) VISIBLE else GONE
        curveRow.visibility = if (curve == null) GONE else VISIBLE
        curve?.let { curvePreview.set(it, ceiling, scale) }
    }

    fun set(wobbliness: Float, extent: Float) { rows[0].set(wobbliness); rows[1].set(extent) }

    /** Freeze zone: the first row is Hold (blue) instead of Wobble, and the curve preview is blue. */
    fun setFreeze(on: Boolean) {
        rows[0].setLabel(if (on) "Hold" else "Wobble"); rows[0].fillColor = if (on) Ui.FREEZE else Ui.ACCENT
        curvePreview.color = if (on) Ui.FREEZE else Ui.ACCENT
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        for (r in rows) r.isEnabled = enabled
        curveRow.isEnabled = enabled
        alpha = if (enabled) 1f else 0.4f
    }
}

/**
 * One slider row: label, relative trackpad-style track, whole-number field 0..[max] (value = number / 100).
 *
 * Touching does nothing; only a deliberate sideways drag past the touch slop moves the value. Slow drags
 * ≈ 0.35 of the 0–100 range per track width, flicks up to 1.5. Values snap to 0.01; a stored value with more
 * decimals (old files) is only replaced once the row is actually changed.
 */
class PercentRow(context: Context, label: String, private val max: Int,
                 private val onChange: (Float) -> Unit, private val onCommit: () -> Unit,
                 private val onDragging: (Boolean) -> Unit = {}) : LinearLayout(context) {
    private val d = resources.displayMetrics.density
    val track = Track()
    val field = EditText(context)
    private val labelView = TextView(context)
    /** Fill colour of the track (amber; blue for a Freeze zone's Hold). */
    var fillColor = Ui.ACCENT
        set(v) { field = v; track.invalidate() }
    fun setLabel(t: String) { labelView.text = t.uppercase() }
    var value = 0.5f; private set
    private val topValue get() = max / 100f

    private fun percent(v: Float) = Math.round(v * 100f)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(labelView.apply {
            text = label.uppercase(); textSize = 11f; typeface = Ui.ui; letterSpacing = 0.08f; setTextColor(Ui.MUTED)
        }, LayoutParams((60 * d).toInt(), LayoutParams.WRAP_CONTENT))
        if (label.isBlank()) labelView.visibility = GONE                    // Settings rows: the title sits above
        addView(track, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        field.apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            imeOptions = EditorInfo.IME_ACTION_DONE
            filters = arrayOf(android.text.InputFilter.LengthFilter(3))
            textSize = 14f; gravity = Gravity.CENTER; setTextColor(Ui.TEXT); typeface = Ui.monoMedium
            background = Ui.box(context, Ui.FIELD, 5f, 0xFF2F3136.toInt()); setPadding(0, 0, 0, 0)
            setSelectAllOnFocus(true)
            setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_DONE) { applyTyped(); true } else false }
            setOnFocusChangeListener { _, has -> if (!has) applyTyped() }
        }
        addView(field, LayoutParams((50 * d).toInt(), (28 * d).toInt()).apply { marginStart = (8 * d).toInt() })
    }

    override fun setEnabled(enabled: Boolean) { super.setEnabled(enabled); track.isEnabled = enabled; field.isEnabled = enabled }

    fun set(v: Float) { value = v; track.raw = v; field.setText(percent(v).toString()); track.invalidate() }

    private fun applyTyped() {
        val n = field.text.toString().toIntOrNull()
        val target = (n ?: percent(value)).coerceIn(0, max)
        field.setText(target.toString())
        if (field.hasFocus()) {
            field.clearFocus()
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(field.windowToken, 0)
        }
        if (target != percent(value)) {
            value = target / 100f; track.raw = value; track.invalidate()
            onChange(value); onCommit()
        }
    }

    inner class Track : View(context) {
        var raw = 0.5f
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        // Studio style: thin graphite track, amber fill, a white vertical bar as the knob.
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.LINE; strokeWidth = 4 * d; strokeCap = Paint.Cap.ROUND }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.ACCENT; strokeWidth = 4 * d; strokeCap = Paint.Cap.ROUND }
        private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.MUTED; strokeWidth = 1.5f * d }
        private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        private val knobRect = android.graphics.RectF()
        private var downX = 0f; private var downY = 0f; private var lastX = 0f; private var lastT = 0L
        private var dragging = false

        private val pad get() = 10 * d

        override fun onDraw(c: Canvas) {
            val y = height / 2f; val x0 = pad; val x1 = width - pad
            val x = x0 + (x1 - x0) * value / topValue
            c.drawLine(x0, y, x1, y, line)
            if (max > 100) { val tx = x0 + (x1 - x0) / topValue; c.drawLine(tx, y - 7 * d, tx, y + 7 * d, tick) }   // 100 mark
            fill.color = fillColor
            c.drawLine(x0, y, x, y, fill)
            val kh = (if (dragging) 13 else 9) * d; val kw = (if (dragging) 3f else 2f) * d
            knobRect.set(x - kw, y - kh, x + kw, y + kh)
            c.drawRoundRect(knobRect, kw, kw, knob)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (!isEnabled) return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; lastX = e.x; lastT = e.eventTime; dragging = false; raw = value }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging) {
                        val dx = abs(e.x - downX); val dy = abs(e.y - downY)
                        if (dx < slop) return true                       // not yet a deliberate drag
                        if (dy > dx) return false                        // mostly vertical: not for us
                        dragging = true; lastX = e.x; lastT = e.eventTime
                        parent?.requestDisallowInterceptTouchEvent(true)
                        onDragging(true); invalidate()
                        return true
                    }
                    val dx = e.x - lastX
                    val dt = (e.eventTime - lastT).coerceAtLeast(1L)
                    val speed = abs(dx) / dt / d                              // dp per ms
                    val gain = (0.35f + speed * 0.9f).coerceAtMost(1.5f) / (width - 2 * pad) * topValue
                    raw = (raw + dx * gain).coerceIn(0f, topValue)
                    lastX = e.x; lastT = e.eventTime
                    val snapped = Math.round(raw * 100f) / 100f
                    if (percent(snapped) != percent(value)) {
                        value = snapped
                        field.setText(percent(value).toString())
                        invalidate()
                        onChange(value)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                    dragging = false; invalidate()
                    onDragging(false); onCommit()
                }
            }
            return true
        }
    }
}
