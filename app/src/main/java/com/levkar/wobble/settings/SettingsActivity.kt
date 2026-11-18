package com.levkar.wobble.settings

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.levkar.wobble.editor.PercentRow
import com.levkar.wobble.ui.ColorPicker
import com.levkar.wobble.ui.Icon
import com.levkar.wobble.ui.IconButton
import com.levkar.wobble.ui.MenuItem
import com.levkar.wobble.ui.Ui
import com.levkar.wobble.ui.UiMenu
import com.levkar.wobble.ui.uiText

/**
 * Settings, drawn entirely from [Options.all]: one section per [Section] that has options, one row per option.
 * Nothing here knows about individual options, so options come and go by editing the registry alone.
 */
class SettingsActivity : Activity() {

    private val d by lazy { resources.displayMetrics.density }
    private fun px(v: Float) = (v * d).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(14f), 0, px(14f), px(40f)) }
        for (section in Section.values()) {
            val opts = Options.all.filter { it.section == section }
            if (opts.isEmpty()) continue
            list.addView(uiText(section.title.uppercase(), 11f, Ui.mono, Ui.ACCENT).apply { letterSpacing = 0.1f },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(26f); bottomMargin = px(8f); marginStart = px(4f) })
            val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = Ui.box(context, Ui.PANEL, 12f, Ui.LINE) }
            opts.forEachIndexed { i, o ->
                if (i > 0) card.addView(View(this).apply { setBackgroundColor(Ui.LINE_SOFT) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
                card.addView(row(o))
            }
            list.addView(card)
        }
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(px(14f), px(18f), px(14f), px(6f))
            addView(IconButton(context, Icon.BACK).apply { setOnClickListener { finish() } }, LinearLayout.LayoutParams(px(42f), px(42f)))
            addView(uiText("Settings", 20f, Ui.uiSemi), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = px(12f) })
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG)
            addView(header)
            addView(ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(list) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        })
        hideSystemBars()
    }

    /** One option row: title (+ note) on the left, its control on the right or below. */
    private fun row(o: Option): View {
        val c: Context = this
        val texts = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(uiText(o.title, 14.5f, Ui.ui))
            o.note?.let { addView(uiText(it, 11f, Ui.ui, Ui.MUTED).apply { setPadding(0, px(3f), 0, 0) }) }
        }
        val r = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(px(14f), px(12f), px(12f), px(12f)) }
        fun side(v: View, w: Int = ViewGroup.LayoutParams.WRAP_CONTENT) {
            r.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(v, LinearLayout.LayoutParams(w, px(34f)).apply { marginStart = px(10f) })
        }
        when (o) {
            is Toggle -> if (o.labels != null) side(segments(listOf(o.labels.first, o.labels.second), { if (o.get(c)) 0 else 1 }) { o.set(c, it == 0) })
                         else side(Switch(c, o.get(c)) { o.set(c, it) }, px(52f))
            is Choice -> if (o.labels.size <= 3) side(segments(o.labels, { o.get(c) }) { o.set(c, it) })
                         else side(valueButton(o.label(c)).apply {
                             setOnClickListener { v -> UiMenu.show(v, o.labels.mapIndexed { i, l -> MenuItem(l) { o.set(c, i); (v as android.widget.TextView).text = "$l ▾" } }) }
                         })
            is Slider -> {
                val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(px(14f), px(12f), px(12f), px(6f)) }
                col.addView(texts)
                col.addView(PercentRow(c, "", o.max, onChange = { v -> o.set(c, Math.round(v * 100)) }, onCommit = {}).apply { set(o.get(c) / 100f) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(40f)))
                return col
            }
            is ColorOption -> {
                val sw = View(c).apply { background = Ui.box(c, o.get(c), 8f, 0xFF3A3C41.toInt()) }
                sw.setOnClickListener {
                    ColorPicker.show(c, o.title, o.get(c), onChange = { col -> sw.background = Ui.box(c, col, 8f, 0xFF3A3C41.toInt()); o.set(c, col) }, onDone = { col -> o.set(c, col) })
                }
                side(sw, px(46f))
            }
            is Action -> { side(uiText("›", 20f, Ui.ui, Ui.MUTED).apply { gravity = Gravity.CENTER }); r.isClickable = true; r.background = Ui.pressable(c, null, 12f); r.setOnClickListener { o.run(c) } }
        }
        return r
    }

    /** Segmented control (2–3 choices), the same look as the editor's Bubble… rows. */
    private fun segments(labels: List<String>, current: () -> Int, pick: (Int) -> Unit): View {
        val c: Context = this
        val boxes = labels.map { l -> uiText(l, 12.5f, Ui.ui).apply { gravity = Gravity.CENTER; val h = px(10f); setPadding(h, 0, h, 0) } }
        val bar = LinearLayout(c).apply { background = Ui.box(c, Ui.FIELD, 8f, 0xFF2F3136.toInt()); val p = px(3f); setPadding(p, p, p, p) }
        fun paint() = boxes.forEachIndexed { i, t ->
            val on = i == current()
            t.background = if (on) Ui.box(c, Ui.ACCENT, 6f) else Ui.pressable(c, null, 6f)
            t.setTextColor(if (on) Ui.ON_ACCENT else Ui.TEXT2)
        }
        boxes.forEachIndexed { i, t ->
            t.setOnClickListener { pick(i); paint() }
            bar.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        paint()
        return bar
    }

    private fun valueButton(text: String) = uiText("$text ▾", 13f, Ui.ui, Ui.TEXT).apply {
        gravity = Gravity.CENTER; val h = px(12f); setPadding(h, 0, h, 0)
        background = Ui.pressable(context, Ui.box(context, Ui.FIELD, 8f, 0xFF2F3136.toInt()), 8f); isClickable = true
    }

    /** A small on/off switch in the app's style. */
    private class Switch(c: Context, on: Boolean, val changed: (Boolean) -> Unit) : FrameLayout(c) {
        private var state = on
        private val knob = View(c)
        private val d = c.resources.displayMetrics.density
        init {
            isClickable = true
            addView(knob, LayoutParams((24 * d).toInt(), (24 * d).toInt(), Gravity.CENTER_VERTICAL))
            setOnClickListener { state = !state; paint(true); changed(state) }
            paint(false)
        }
        private fun paint(animate: Boolean) {
            background = Ui.box(context, if (state) Ui.ACCENT else 0xFF2A2C31.toInt(), 17f)
            knob.background = Ui.box(context, if (state) Ui.ON_ACCENT else 0xFFB9BAB6.toInt(), 12f)
            post {
                val x = if (state) width - knob.width - 5 * d else 5 * d
                if (animate) knob.animate().translationX(x).setDuration(140).start() else knob.translationX = x
            }
        }
    }

    private fun hideSystemBars() {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        window.insetsController?.let { it.hide(WindowInsets.Type.systemBars()); it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }
    }
}
