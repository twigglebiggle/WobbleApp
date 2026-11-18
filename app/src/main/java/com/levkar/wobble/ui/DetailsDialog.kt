package com.levkar.wobble.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout

/** Gallery → long-press → Details (Lev, b53): credits plus facts about the file. */
object DetailsDialog {

    class Info(
        val name: String,
        val author: String?, val ai: Boolean, val source: String?,
        val created: Long?, val edited: Long?,
        val width: Int, val height: Int, val format: String, val bytes: Long,
        val bubbles: Int, val freezes: Int,
    )

    fun show(c: Context, i: Info, onEditCredits: () -> Unit) {
        val d = Dialog(c)
        val pad = Ui.px(c, 20f)
        val df = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
        fun date(t: Long?) = t?.let { df.format(java.util.Date(it)) } ?: "—"
        val body = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.box(c, Ui.PANEL, 16f, Ui.LINE)
            setPadding(pad, pad, pad, Ui.px(c, 16f))
            addView(c.uiText(i.name, 18f, Ui.uiSemi).oneLine())
            fun section(t: String) = addView(c.uiText(t, 10.5f, Ui.mono, Ui.MUTED).apply { letterSpacing = 0.1f },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.px(c, 16f); bottomMargin = Ui.px(c, 2f) })
            fun row(k: String, v: String, link: String? = null) = addView(LinearLayout(c).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(0, Ui.px(c, 5f), 0, Ui.px(c, 5f))
                addView(c.uiText(k, 13.5f, Ui.ui, Ui.TEXT2), LinearLayout.LayoutParams(Ui.px(c, 96f), ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(c.uiText(v, 13.5f, Ui.ui, if (link != null) Ui.ACCENT else Ui.TEXT).oneLine(), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (link != null) { isClickable = true; background = Ui.pressable(c, null, 6f); setOnClickListener { CreditsDialog.open(c, link) } }
            })
            section("CREDITS")
            row("Author", if (i.ai) "AI-generated" else i.author ?: "—")
            row("Source", i.source?.let { (CreditsDialog.summary(null, false, it) ?: it) + "  ↗" } ?: "—", i.source)
            section("FILE")
            row("Created", date(i.created))
            row("Last edited", date(i.edited))
            row("Picture", if (i.width > 0) "${i.width} × ${i.height} · ${i.format}" else i.format)
            row("Size", if (i.bytes >= 1_000_000) "%.1f MB".format(i.bytes / 1_000_000f) else "${i.bytes / 1000} KB")
            row("Bubbles", if (i.freezes > 0) "${i.bubbles} + ${i.freezes} freeze ${if (i.freezes == 1) "zone" else "zones"}" else "${i.bubbles}")
            addView(LinearLayout(c).apply {
                gravity = Gravity.END
                fun btn(label: String, fill: Int, fg: Int, click: () -> Unit) = c.uiText(label, 14.5f, Ui.uiSemi, fg).apply {
                    gravity = Gravity.CENTER
                    val h = Ui.px(c, 18f); setPadding(h, 0, h, 0)
                    background = Ui.pressable(c, Ui.box(c, fill, 8f, if (fill == Ui.RAISED) 0xFF33353A.toInt() else 0), 8f)
                    isClickable = true; setOnClickListener { click() }
                }
                addView(btn("Edit credits…", Ui.RAISED, Ui.TEXT) { d.dismiss(); onEditCredits() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.px(c, 44f)))
                addView(btn("Close", Ui.ACCENT, Ui.ON_ACCENT) { d.dismiss() },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.px(c, 44f)).apply { marginStart = Ui.px(c, 10f) })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.px(c, 18f) })
        }
        d.setContentView(body)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout((c.resources.displayMetrics.widthPixels * 0.9f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.6f)
        }
        UiMenu.unrollDialog(d, body, fromBottom = false)
        d.show()
    }
}
