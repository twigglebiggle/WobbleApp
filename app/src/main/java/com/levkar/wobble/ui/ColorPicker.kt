package com.levkar.wobble.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout

/**
 * Colour picker sheet: saturation/brightness square, hue bar, a row of presets, and a small hex field for
 * exact values. [onChange] runs live while picking (so the thing being coloured updates behind the sheet);
 * [onDone] when the sheet closes with Done. Cancel (back / tap outside) restores the starting colour via [onChange].
 */
object ColorPicker {

    private val PRESETS = intArrayOf(
        0xFF000000.toInt(), 0xFF16171A.toInt(), 0xFF2A2C31.toInt(), 0xFFF2EEE6.toInt(),
        0xFF2B1B3D.toInt(), 0xFF3A1F2B.toInt(), 0xFF1B2D3A.toInt(), 0xFF1F3326.toInt(), 0xFFFFBF3C.toInt(),
    )

    fun show(c: Context, title: String, start: Int, onChange: (Int) -> Unit, onDone: (Int) -> Unit) {
        Ui.init(c)
        val d = c.resources.displayMetrics.density
        val dialog = Dialog(c)
        val hsv = FloatArray(3).also { Color.colorToHSV(start, it) }
        var done = false
        fun color() = Color.HSVToColor(hsv)

        val sv = SvSquare(c, hsv)
        val hue = HueBar(c, hsv)
        val preview = View(c)
        val hex = EditText(c)
        var typing = false

        fun refresh(fromHex: Boolean = false) {
            val col = color()
            preview.background = Ui.box(c, col, 8f, 0xFF3A3C41.toInt())
            if (!fromHex) { typing = true; hex.setText(String.format("#%06X", col and 0xFFFFFF)); typing = false }
            sv.invalidate(); hue.invalidate()
            onChange(col)
        }
        sv.onPick = { refresh() }
        hue.onPick = { refresh() }

        hex.apply {
            typeface = Ui.mono; textSize = 14f; setTextColor(Ui.TEXT); gravity = Gravity.CENTER
            background = Ui.box(c, Ui.FIELD, 8f, 0xFF2F3136.toInt()); setPadding(0, 0, 0, 0)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS; isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, cnt: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, cnt: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    if (typing) return
                    val t = s.toString().trim().removePrefix("#")
                    if (t.length != 6) return
                    val col = runCatching { Color.parseColor("#$t") }.getOrNull() ?: return
                    Color.colorToHSV(col, hsv); refresh(fromHex = true)
                }
            })
        }

        val pad = (16 * d).toInt()
        val root = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (14 * d).toInt(), pad, (18 * d).toInt())
            background = GradientDrawable().apply {
                setColor(Ui.PANEL); setStroke(1, Ui.LINE)
                cornerRadii = floatArrayOf(16 * d, 16 * d, 16 * d, 16 * d, 0f, 0f, 0f, 0f)
            }
            addView(LinearLayout(c).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(c.uiText(title, 16f, Ui.uiSemi), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(c.uiText("Done", 13f, Ui.uiSemi, Ui.ON_ACCENT).apply {
                    gravity = Gravity.CENTER; val h = (14 * d).toInt(); setPadding(h, 0, h, 0)
                    background = Ui.pressable(c, Ui.box(c, Ui.ACCENT, 7f), 7f, 0x33000000)
                    isClickable = true; setOnClickListener { done = true; onDone(color()); dialog.dismiss() }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (34 * d).toInt()))
            })
            addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (190 * d).toInt()).apply { topMargin = (12 * d).toInt() })
            addView(hue, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (30 * d).toInt()).apply { topMargin = (12 * d).toInt() })
            addView(LinearLayout(c).apply {                                   // presets
                for (p in PRESETS) addView(View(c).apply {
                    background = Ui.box(c, p, 7f, 0xFF3A3C41.toInt())
                    setOnClickListener { Color.colorToHSV(p, hsv); refresh() }
                }, LinearLayout.LayoutParams(0, (30 * d).toInt(), 1f).apply { marginEnd = (6 * d).toInt() })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (12 * d).toInt() })
            addView(LinearLayout(c).apply {                                   // preview + hex (the extra, not the main way)
                gravity = Gravity.CENTER_VERTICAL
                addView(preview, LinearLayout.LayoutParams((44 * d).toInt(), (32 * d).toInt()))
                addView(View(c), LinearLayout.LayoutParams(0, 1, 1f))
                addView(c.uiText("HEX", 10f, Ui.mono, Ui.MUTED), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = (8 * d).toInt() })
                addView(hex, LinearLayout.LayoutParams((104 * d).toInt(), (32 * d).toInt()))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (14 * d).toInt() })
        }
        dialog.setContentView(root)
        UiMenu.unrollDialog(dialog, root, fromBottom = true)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)          // the coloured thing stays visible
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dialog.setOnDismissListener { if (!done) onChange(start) }
        refresh()
        dialog.show()
    }

    /** Saturation left→right, brightness bottom→top, for the current hue. */
    private class SvSquare(c: Context, val hsv: FloatArray) : View(c) {
        var onPick: () -> Unit = {}
        private val d = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d; color = Color.WHITE }
        private val ringDark = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * d; color = 0x99000000.toInt() }
        private val r = RectF()
        private var shaderHue = -1f

        override fun onDraw(c: Canvas) {
            r.set(0f, 0f, width.toFloat(), height.toFloat())
            if (shaderHue != hsv[0] || paint.shader == null) {
                val pure = Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f))
                val sat = LinearGradient(0f, 0f, width.toFloat(), 0f, Color.WHITE, pure, Shader.TileMode.CLAMP)
                val value = LinearGradient(0f, 0f, 0f, height.toFloat(), Color.WHITE, Color.BLACK, Shader.TileMode.CLAMP)
                paint.shader = ComposeShader(value, sat, PorterDuff.Mode.MULTIPLY); shaderHue = hsv[0]
            }
            c.drawRoundRect(r, 10 * d, 10 * d, paint)
            val x = hsv[1] * width; val y = (1 - hsv[2]) * height
            c.drawCircle(x, y, 10 * d, ringDark); c.drawCircle(x, y, 9 * d, ring)
        }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { paint.shader = null }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            parent?.requestDisallowInterceptTouchEvent(true)
            hsv[1] = (e.x / width).coerceIn(0f, 1f); hsv[2] = (1 - e.y / height).coerceIn(0f, 1f)
            onPick(); return true
        }
    }

    /** The hue rainbow. */
    private class HueBar(c: Context, val hsv: FloatArray) : View(c) {
        var onPick: () -> Unit = {}
        private val d = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val knobEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * d; color = 0x99000000.toInt() }
        private val r = RectF()

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            val cols = IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) }
            paint.shader = LinearGradient(0f, 0f, w.toFloat(), 0f, cols, null, Shader.TileMode.CLAMP)
        }

        override fun onDraw(c: Canvas) {
            val inset = 4 * d
            r.set(0f, inset, width.toFloat(), height - inset)
            c.drawRoundRect(r, r.height() / 2, r.height() / 2, paint)
            val x = (hsv[0] / 360f) * width
            r.set(x - 3 * d, 0f, x + 3 * d, height.toFloat())
            c.drawRoundRect(r, 3 * d, 3 * d, knob); c.drawRoundRect(r, 3 * d, 3 * d, knobEdge)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            parent?.requestDisallowInterceptTouchEvent(true)
            hsv[0] = (e.x / width).coerceIn(0f, 1f) * 359.9f
            onPick(); return true
        }
    }
}
