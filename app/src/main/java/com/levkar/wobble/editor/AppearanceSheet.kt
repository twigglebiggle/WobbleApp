package com.levkar.wobble.editor

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.levkar.wobble.ui.Ui
import com.levkar.wobble.ui.uiText

/**
 * Bottom sheet for bubble appearance: outline and fill color/opacity, outline width, fill pattern,
 * for each style (bubbles: Normal / Selected / Locked / Selected-locked; Freeze zones: unlocked / locked).
 * Colours: fixed swatches, plus saved colours and "+" for the full colour picker. The canvas above updates live.
 */
class AppearanceSheet(context: Context, private val onChanged: () -> Unit) : Dialog(context) {

    private val d = context.resources.displayMetrics.density
    private var role = BubbleStyle.Role.SELECTED
    private val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val roleChips = HashMap<BubbleStyle.Role, TextView>()

    init {
        Ui.init(context)
        val pad = (16 * d).toInt()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(Ui.PANEL); setStroke(1, Ui.LINE)
                cornerRadii = floatArrayOf(16 * d, 16 * d, 16 * d, 16 * d, 0f, 0f, 0f, 0f)
            }
            addView(context.uiText("Bubble appearance", 17f, Ui.uiSemi))
            // Two rows of styles: wobble bubbles, then Freeze zones (blue label, like the zones themselves).
            for ((label, freeze) in listOf("BUBBLES" to false, "FREEZE ZONES" to true)) {
                addView(context.uiText(label, 10f, Ui.mono, if (freeze) Ui.FREEZE else Ui.MUTED).apply { letterSpacing = 0.1f },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (12 * d).toInt(); bottomMargin = (6 * d).toInt() })
                addView(LinearLayout(context).apply {
                    for (r in BubbleStyle.Role.values().filter { it.freeze == freeze }) {
                        val chip = chip(r.chip) { role = r; rebuild() }
                        roleChips[r] = chip
                        addView(chip, LinearLayout.LayoutParams(0, (34 * d).toInt(), 1f).apply { marginEnd = (6 * d).toInt() })
                    }
                })
            }
            addView(body)
        }
        setContentView(ScrollView(context).apply { addView(root) })
        com.levkar.wobble.ui.UiMenu.unrollDialog(this, root, fromBottom = true)
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)   // keep the canvas visible for live preview
        }
        rebuild()
    }

    private fun rebuild() {
        for ((r, c) in roleChips) select(c, r == role)
        body.removeAllViews()
        val s = BubbleStyle.load(context, role)
        fun update(n: BubbleStyle) { BubbleStyle.save(context, role, n); onChanged() }

        body.addView(section("Outline"))
        body.addView(swatches(s.outlineColor, "Outline colour") { c, rebuildNow -> update(BubbleStyle.load(context, role).copy(outlineColor = c)); if (rebuildNow) rebuild() })
        body.addView(slider("Opacity", s.outlineAlpha) { update(BubbleStyle.load(context, role).copy(outlineAlpha = it)) })
        body.addView(slider("Width", s.outlineWidthDp / 6f) { update(BubbleStyle.load(context, role).copy(outlineWidthDp = it * 6f)) })

        body.addView(section("Fill"))
        body.addView(swatches(s.fillColor, "Fill colour") { c, rebuildNow -> update(BubbleStyle.load(context, role).copy(fillColor = c)); if (rebuildNow) rebuild() })
        body.addView(slider("Opacity", s.fillAlpha) { update(BubbleStyle.load(context, role).copy(fillAlpha = it)) })
        body.addView(LinearLayout(context).apply {
            BubbleStyle.PATTERN_LABELS.forEachIndexed { i, label ->
                addView(chip(label) { update(BubbleStyle.load(context, role).copy(pattern = i)); rebuild() }.also { select(it, s.pattern == i) },
                    LinearLayout.LayoutParams(0, (34 * d).toInt(), 1f).apply { marginEnd = (6 * d).toInt() })
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (8 * d).toInt() })
        body.addView(context.uiText(if (role.freeze) "Dome = shaded by how much each part is held (brighter = stiller)."
                                    else "Dome = shaded by how much each part moves (brighter = more).", 11.5f, Ui.ui, Ui.MUTED),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (10 * d).toInt() })
        body.addView(chip("Reset ${role.label.lowercase()} to default") { BubbleStyle.reset(context, role); onChanged(); rebuild() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (40 * d).toInt()).apply { topMargin = (14 * d).toInt() })
    }

    private fun section(t: String) = context.uiText(t.uppercase(), 11f, Ui.ui, Ui.MUTED).apply {
        letterSpacing = 0.08f; setPadding(0, (18 * d).toInt(), 0, (8 * d).toInt())
    }

    private fun chip(label: String, onClick: () -> Unit) = context.uiText(label, 12.5f, Ui.ui, Ui.TEXT2).apply {
        gravity = Gravity.CENTER; isClickable = true
        setOnClickListener { onClick() }
        select(this, false)
    }

    private fun select(chip: TextView, on: Boolean) {
        chip.setTextColor(if (on) Ui.ACCENT else Ui.TEXT2)
        chip.background = Ui.pressable(context, Ui.box(context, if (on) Ui.accentA(0x1A) else Ui.RAISED, 8f, if (on) Ui.ACCENT else 0xFF33353A.toInt()), 8f)
    }

    /**
     * Fixed palette, then a "Saved" row: colours picked with the colour picker (newest first, long-press removes)
     * and "+" for the picker itself. [onPick] (colour, rebuild): the picker previews live without rebuilding the sheet.
     */
    private fun swatches(current: Int, title: String, onPick: (Int, Boolean) -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val size = (28 * d).toInt(); val m = (4 * d).toInt()
        fun dot(c: Int) = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(0xFF000000.toInt() or c)
                setStroke((if (c == current) 3 * d else 1 * d).toInt(), if (c == current) Ui.ACCENT else 0x44FFFFFF)
            }
            setOnClickListener { onPick(c, true) }
        }
        addView(LinearLayout(context).apply {
            for (c in BubbleStyle.PALETTE) addView(dot(c), LinearLayout.LayoutParams(size, size).apply { setMargins(m, m, m, m) })
        })
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(context.uiText("SAVED", 10f, Ui.mono, Ui.MUTED).apply { letterSpacing = 0.1f },
                LinearLayout.LayoutParams((52 * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = m })
            for (c in BubbleStyle.savedColors(context)) addView(dot(c).apply {
                setOnLongClickListener { BubbleStyle.removeColor(context, c); rebuild(); true }
            }, LinearLayout.LayoutParams(size, size).apply { setMargins(m, m, m, m) })
            // "+": the full colour picker; the bubble previews live, Done keeps the colour and saves it here.
            addView(context.uiText("+", 17f, Ui.ui, Ui.TEXT2).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.RAISED); setStroke((1 * d).toInt(), 0x44FFFFFF) }
                setOnClickListener {
                    val start = current
                    com.levkar.wobble.ui.ColorPicker.show(context, title, 0xFF000000.toInt() or start,
                        onChange = { col -> onPick(col and 0xFFFFFF, false) },
                        onDone = { col -> BubbleStyle.saveColor(context, col and 0xFFFFFF); onPick(col and 0xFFFFFF, true) })
                }
            }, LinearLayout.LayoutParams(size, size).apply { setMargins(m, m, m, m) })
        })
    }

    private fun slider(label: String, value: Float, onChange: (Float) -> Unit) = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(context.uiText(label, 12.5f, Ui.ui, Ui.TEXT2), LinearLayout.LayoutParams((70 * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(SeekBar(context).apply {
            max = 100; progress = (value * 100).toInt().coerceIn(0, 100)
            progressTintList = ColorStateList.valueOf(Ui.ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(Ui.LINE)
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) onChange(p / 100f) }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }, LinearLayout.LayoutParams(0, (40 * d).toInt(), 1f))
    }
}
