package com.levkar.wobble.ui

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * Credits for a piece (Lev, b52): Author (text, or ticked "AI", which turns the text field off) and a Source link
 * (where the picture was posted or found). Empty = nothing stored.
 */
object CreditsDialog {

    /** Adds https:// to a bare link like "x.com/abc"; blank stays null. */
    fun normalizeLink(s: String?): String? {
        val t = s?.trim().orEmpty()
        if (t.isEmpty()) return null
        return if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(t)) t else "https://$t"
    }

    fun open(c: Context, link: String) {
        runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun show(c: Context, author: String?, ai: Boolean, source: String?, onSave: (author: String?, ai: Boolean, source: String?) -> Unit) {
        val d = Dialog(c)
        val pad = Ui.px(c, 20f)
        var isAi = ai
        fun field(text: String?, hint: String, type: Int) = EditText(c).apply {
            setText(text.orEmpty()); this.hint = hint; setHintTextColor(Ui.MUTED)
            typeface = Ui.ui; textSize = 15f; setTextColor(Ui.TEXT)
            background = Ui.box(c, Ui.FIELD, 8f, 0xFF2F3136.toInt())
            val p = Ui.px(c, 12f); setPadding(p, p, p, p)
            inputType = type; isSingleLine = true
        }
        val authorField = field(author, "Who made it", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        val sourceField = field(source, "Link to where it was posted", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)

        // "AI" tick box beside the author field.
        val box = FrameLayout(c)
        val tick = ImageView(c)
        val aiLabel = c.uiText("AI", 13f, Ui.uiSemi, Ui.TEXT2)
        fun paintAi() {
            box.background = if (isAi) Ui.box(c, Ui.ACCENT, 6f) else Ui.box(c, Ui.FIELD, 6f, 0xFF3A3C41.toInt())
            tick.setImageDrawable(if (isAi) Icon.CHECK.drawable(Ui.ON_ACCENT, 2.4f) else null)
            aiLabel.setTextColor(if (isAi) Ui.ACCENT else Ui.TEXT2)
            authorField.isEnabled = !isAi
            authorField.alpha = if (isAi) 0.4f else 1f
            authorField.hint = if (isAi) "AI-generated" else "Who made it"
            if (isAi) authorField.clearFocus()
        }
        box.addView(tick, FrameLayout.LayoutParams(Ui.px(c, 18f), Ui.px(c, 18f), Gravity.CENTER))
        val aiToggle = LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL; isClickable = true
            background = Ui.pressable(c, null, 8f)
            val p = Ui.px(c, 6f); setPadding(p, p, p, p)
            addView(box, LinearLayout.LayoutParams(Ui.px(c, 26f), Ui.px(c, 26f)))
            addView(aiLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = Ui.px(c, 7f) })
            setOnClickListener { isAi = !isAi; paintAi(); Ui.haptic(this, android.view.HapticFeedbackConstants.CLOCK_TICK) }
        }

        fun label(t: String) = c.uiText(t.uppercase(), 10.5f, Ui.mono, Ui.MUTED).apply { letterSpacing = 0.1f }
        val openLink = c.uiText("Open ↗", 13f, Ui.uiSemi, Ui.ACCENT).apply {
            gravity = Gravity.CENTER; isClickable = true
            background = Ui.pressable(c, null, 8f); val p = Ui.px(c, 10f); setPadding(p, 0, p, 0)
            setOnClickListener { normalizeLink(sourceField.text.toString())?.let { open(c, it) } }
        }

        val body = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.box(c, Ui.PANEL, 16f, Ui.LINE)
            setPadding(pad, pad, pad, Ui.px(c, 16f))
            addView(c.uiText("Credits", 18f, Ui.uiSemi))
            addView(label("Author"), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.px(c, 16f); bottomMargin = Ui.px(c, 6f) })
            addView(LinearLayout(c).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(authorField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(aiToggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = Ui.px(c, 8f) })
            })
            addView(label("Source"), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.px(c, 16f); bottomMargin = Ui.px(c, 6f) })
            addView(LinearLayout(c).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(sourceField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(openLink, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.px(c, 44f)).apply { marginStart = Ui.px(c, 4f) })
            })
            addView(LinearLayout(c).apply {
                gravity = Gravity.END
                fun btn(label: String, fill: Int, fg: Int, click: () -> Unit) = c.uiText(label, 14.5f, Ui.uiSemi, fg).apply {
                    gravity = Gravity.CENTER
                    val h = Ui.px(c, 18f); setPadding(h, 0, h, 0)
                    background = Ui.pressable(c, Ui.box(c, fill, 8f, if (fill == Ui.RAISED) 0xFF33353A.toInt() else 0), 8f)
                    isClickable = true; setOnClickListener { click() }
                }
                addView(btn("Cancel", Ui.RAISED, Ui.TEXT) { d.dismiss() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.px(c, 44f)))
                addView(btn("Save", Ui.ACCENT, Ui.ON_ACCENT) {
                    d.dismiss()
                    val a = if (isAi) null else authorField.text.toString().trim().ifEmpty { null }
                    onSave(a, isAi, normalizeLink(sourceField.text.toString()))
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.px(c, 44f)).apply { marginStart = Ui.px(c, 10f) })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.px(c, 20f) })
        }
        paintAi()
        d.setContentView(body)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout((c.resources.displayMetrics.widthPixels * 0.9f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.6f)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        UiMenu.unrollDialog(d, body, fromBottom = false)
        d.show()
    }

    /** One line for display: "by Name · source ↗", "AI-generated", or null when there's nothing. */
    fun summary(author: String?, ai: Boolean, source: String?): String? {
        val who = if (ai) "AI-generated" else author?.let { "by $it" }
        val host = source?.let { runCatching { Uri.parse(it).host?.removePrefix("www.") }.getOrNull() ?: it }
        return listOfNotNull(who, host).joinToString(" · ").ifEmpty { null }
    }
}
