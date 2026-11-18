package com.levkar.wobble.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView

/** Small set of reusable controls in the app's style. Screens build their bars only from these. */

fun Context.uiText(text: CharSequence, sizeSp: Float, face: android.graphics.Typeface = Ui.ui, color: Int = Ui.TEXT) =
    TextView(this).apply {
        this.text = text; textSize = sizeSp; typeface = face; setTextColor(color); includeFontPadding = false
    }

/**
 * Icon above a short label, with an optional second line (a state such as "Overlay" or "Fit", in mono amber).
 * Used in the floating bars and the editor's tool grid.
 */
class ToolButton(c: Context, icon: Icon, label: String, sub: String? = null, private val iconDp: Float = 22f) : LinearLayout(c) {
    private val img = ImageView(c)
    private val txt = c.uiText(label, 11f, Ui.ui, Ui.TEXT2)
    private val subTxt = c.uiText("", 9.5f, Ui.mono, Ui.ACCENT)
    private val drawable = icon.drawable(Ui.TEXT2)
    var highlighted = false
        set(v) { field = v; recolor() }
    /** Filled amber cell (Preview). */
    var accent = false
        set(v) { field = v; restyle(); recolor() }
    /** Corner radius of a filled (accent / warm) cell: 0 in the editor's grouped rows, rounded inside a [FloatingBar]. */
    var cellRadiusDp = 0f
        set(v) { field = v; restyle() }
    /**
     * Keep the second-line space even without a sub-label, so every label in a bar sits on the same line
     * (Lev: "Fill / crop" sat higher than "Guides"). Set by [FloatingBar].
     */
    var reserveSub = false
        set(v) { field = v; set(sub = subTxt.text.toString().ifEmpty { null }) }
    private fun restyle() {
        background = when {
            accent -> Ui.pressable(context, Ui.box(context, Ui.ACCENT, cellRadiusDp), cellRadiusDp, 0x33000000)
            warm -> Ui.pressable(context, Ui.box(context, Ui.accentA(0x14), cellRadiusDp), cellRadiusDp)
            else -> Ui.pressable(context, null, 10f)
        }
    }
    /** Highlighted state cell (e.g. "Unlock"): amber text on a faint amber wash. */
    var warm = false
        set(v) { field = v; restyle(); recolor() }
    /** Icon only, no label (Undo / Redo). */
    fun iconOnly() { txt.visibility = GONE; subTxt.visibility = GONE }

    init {
        orientation = VERTICAL; gravity = Gravity.CENTER
        isClickable = true; isFocusable = true
        background = Ui.pressable(c, null, 10f)
        img.setImageDrawable(drawable)
        val s = Ui.px(c, iconDp)
        addView(img, LayoutParams(s, s))
        // Full-width, centered, one line each: long labels stay centered instead of spilling left.
        addView(txt, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.px(c, 5f) })
        addView(subTxt, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.px(c, 2f) })
        txt.gravity = Gravity.CENTER; subTxt.gravity = Gravity.CENTER
        txt.maxLines = 1; subTxt.maxLines = 1
        set(icon, label, sub)
    }

    fun set(icon: Icon = drawable.icon, label: String = txt.text.toString(), sub: String? = null) {
        drawable.icon = icon; drawable.invalidateSelf()
        txt.text = label
        subTxt.text = sub ?: ""
        subTxt.visibility = when { txt.visibility == GONE -> GONE; sub != null -> VISIBLE; reserveSub -> INVISIBLE; else -> GONE }
    }

    private fun recolor() {
        val col = when { accent -> Ui.ON_ACCENT; highlighted || warm -> Ui.ACCENT; else -> Ui.TEXT2 }
        drawable.color = col; drawable.invalidateSelf(); txt.setTextColor(col)
    }

    override fun setEnabled(enabled: Boolean) { super.setEnabled(enabled); alpha = if (enabled) 1f else 0.32f }
}

/** Large icon + label button (Undo, Preview). [accent] = filled amber. */
class BigButton(c: Context, icon: Icon, label: String, accent: Boolean = false) : LinearLayout(c) {
    private val iconDrawable = icon.drawable(Ui.TEXT)   // see IconButton: not "drawable"
    private val txt = c.uiText(label, 15f, Ui.uiSemi)
    var accent = accent
        set(v) { field = v; restyle() }

    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER
        isClickable = true; isFocusable = true
        val s = Ui.px(c, 20f)
        addView(ImageView(c).apply { setImageDrawable(iconDrawable) }, LayoutParams(s, s).apply { marginEnd = Ui.px(c, 9f) })
        addView(txt)
        restyle()
    }

    fun set(icon: Icon, label: String) { iconDrawable.icon = icon; iconDrawable.invalidateSelf(); txt.text = label }

    private fun restyle() {
        val fg = if (accent) Ui.ON_ACCENT else Ui.TEXT
        iconDrawable.color = fg; iconDrawable.invalidateSelf(); txt.setTextColor(fg)
        background = Ui.pressable(context,
            if (accent) Ui.box(context, Ui.ACCENT, 8f) else Ui.box(context, Ui.RAISED, 8f, 0xFF33353A.toInt()), 8f,
            if (accent) 0x33000000 else Ui.PRESS)
    }

    override fun setEnabled(enabled: Boolean) { super.setEnabled(enabled); alpha = if (enabled) 1f else 0.32f }
}

/** Small square icon button with a hairline border (back). */
class IconButton(c: Context, icon: Icon, iconDp: Float = 22f) : FrameLayout(c) {
    // Not called "drawable": inside ImageView.apply {} that name means the ImageView's own (empty) drawable.
    private val iconDrawable = icon.drawable(Ui.TEXT)
    init {
        isClickable = true; isFocusable = true
        background = Ui.pressable(c, Ui.box(c, Ui.PANEL_GLASS, 8f, Ui.LINE), 8f)
        val s = Ui.px(c, iconDp)
        addView(ImageView(c).apply { setImageDrawable(iconDrawable) }, LayoutParams(s, s, Gravity.CENTER))
        elevation = Ui.dp(c, 6f)
    }
    fun setIcon(icon: Icon) { iconDrawable.icon = icon; iconDrawable.invalidateSelf() }
}

/** Graphite glass bar floating over the art (gallery, viewer). Children share the width. */
class FloatingBar(c: Context) : LinearLayout(c) {
    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = Ui.box(c, Ui.PANEL_GLASS, 16f, 0x17FFFFFF)
        elevation = Ui.dp(c, 12f)
        val p = Ui.px(c, 4f); setPadding(p, p, p, p)
    }
    fun add(v: View, weight: Float = 1f) {
        // Cells inside the bar: filled ones round to match the bar (16dp − 4dp padding), labels share one line.
        (v as? ToolButton)?.apply { cellRadiusDp = 12f; reserveSub = true }
        addView(v, LayoutParams(0, Ui.px(context, 58f), weight))
    }

    /** Standard placement: bottom, 14dp from the sides, 18dp up. */
    fun placement() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
        val m = Ui.px(context, 14f); setMargins(m, 0, m, Ui.px(context, 18f))
    }
}

/** One entry in a [UiMenu]. */
class MenuItem(val label: String, val icon: Icon? = null, val danger: Boolean = false, val enabled: Boolean = true, val action: () -> Unit)

/** Replacement for Android's PopupMenu: a graphite card with icon rows, opening above or below its anchor. */
object UiMenu {
    fun show(anchor: View, items: List<MenuItem>) {
        val c = anchor.context
        lateinit var popup: PopupWindow
        val list = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.box(c, Ui.PANEL, 12f, Ui.LINE)
            val p = Ui.px(c, 6f); setPadding(p, p, p, p)
            for (it in items) {
                val col = when { it.danger -> Ui.DANGER; else -> Ui.TEXT }
                addView(LinearLayout(c).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    val h = Ui.px(c, 12f); setPadding(h, 0, h * 2, 0)
                    background = Ui.pressable(c, null, 8f)
                    isClickable = it.enabled; alpha = if (it.enabled) 1f else 0.35f
                    val s = Ui.px(c, 20f)
                    addView(ImageView(c).apply { it.icon?.let { ic -> setImageDrawable(ic.drawable(if (it.danger) Ui.DANGER else Ui.TEXT2)) } },
                        LinearLayout.LayoutParams(s, s).apply { marginEnd = Ui.px(c, 12f) })
                    addView(c.uiText(it.label, 14.5f, Ui.ui, col))
                    setOnClickListener { _ -> close(popup, it.action) }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.px(c, 46f)))
            }
        }
        popup = showCard(anchor, list)
    }

    /**
     * Shows any vertical card ([card]) as a pop-up next to [anchor] with the menu motion (each child of the
     * card is one "row" in the unroll). [widthPx] fixes the width; default = content width, at least 200dp.
     */
    fun showCard(anchor: View, card: LinearLayout, widthPx: Int = 0): PopupWindow {
        val c = anchor.context
        val popup = PopupWindow(card, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = Ui.dp(c, 16f)
            isOutsideTouchable = true
            animationStyle = 0                       // our own animation below, not the system's
        }
        val wSpec = if (widthPx > 0) View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY) else View.MeasureSpec.UNSPECIFIED
        card.measure(wSpec, View.MeasureSpec.UNSPECIFIED)
        val w = if (widthPx > 0) widthPx else card.measuredWidth.coerceAtLeast(Ui.px(c, 200f)); val h = card.measuredHeight
        popup.width = w
        val loc = IntArray(2); anchor.getLocationInWindow(loc)
        val screenH = anchor.rootView.height; val screenW = anchor.rootView.width
        val gap = Ui.px(c, 8f)
        val x = (loc[0] + anchor.width / 2 - w / 2).coerceIn(gap, (screenW - w - gap).coerceAtLeast(gap))
        val above = loc[1] + anchor.height + h + gap > screenH
        val y = if (above) loc[1] - h - gap else loc[1] + anchor.height + gap
        popup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y.coerceAtLeast(gap))
        unroll(card, above)
        return popup
    }

    /**
     * Opening motion: the card fades in and unrolls from the edge nearest its anchor, then the rows
     * follow one by one (each fades in and slides a few dp out of the anchor's side).
     */
    fun unroll(list: LinearLayout, above: Boolean) {
        val c = list.context
        val k = Ui.anim
        if (k == 0f) { list.alpha = 1f; list.scaleY = 1f; for (i in 0 until list.childCount) list.getChildAt(i).let { it.alpha = 1f; it.translationY = 0f }; return }
        val ease = android.view.animation.DecelerateInterpolator(2f)
        list.alpha = 0f; list.scaleY = 0.7f
        list.pivotX = list.measuredWidth / 2f
        list.pivotY = if (above) list.measuredHeight.toFloat() else 0f
        list.animate().alpha(1f).scaleY(1f).setDuration((MENU_OPEN_MS * k).toLong()).setInterpolator(ease).start()
        val shift = Ui.dp(c, 8f) * (if (above) 1f else -1f)
        val n = list.childCount
        for (i in 0 until n) {
            val row = list.getChildAt(if (above) n - 1 - i else i)     // unroll away from the anchor
            row.alpha = 0f; row.translationY = shift
            row.animate().alpha(1f).translationY(0f).setStartDelay(((40L + i * ROW_STAGGER_MS) * k).toLong())
                .setDuration((MENU_ROW_MS * k).toLong()).setInterpolator(ease).start()
        }
    }

    /**
     * Same motion for a Dialog's card (Lev: dialogs and sheets open like the menus). [fromBottom] = a bottom
     * sheet (unrolls upward), else a centered dialog (unrolls downward). Replaces the system window animation.
     */
    fun unrollDialog(d: android.app.Dialog, card: LinearLayout, fromBottom: Boolean) {
        d.window?.setWindowAnimations(0)
        card.alpha = 0f
        d.setOnShowListener { card.post { unroll(card, fromBottom) } }
    }

    /** Quick fade out, then run the picked action. */
    private fun close(popup: PopupWindow, then: () -> Unit) {
        val v = popup.contentView
        v.animate().cancel()
        v.animate().alpha(0f).setDuration((MENU_CLOSE_MS * Ui.anim).toLong()).withEndAction {
            runCatching { popup.dismiss() }
            then()
        }.start()
    }

    // Timings (ms), kept here so the feel can be tuned in one place.
    private const val MENU_OPEN_MS = 180L
    private const val MENU_ROW_MS = 160L
    private const val ROW_STAGGER_MS = 28L
    private const val MENU_CLOSE_MS = 110L
}

/** Replacement for AlertDialog: title, optional message, optional text input, Cancel + one action. */
object UiDialog {
    fun show(c: Context, title: String, message: String? = null, input: String? = null, action: String,
             danger: Boolean = false, onAction: (String) -> Unit) {
        val d = Dialog(c)
        var field: EditText? = null
        val pad = Ui.px(c, 20f)
        val body = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.box(c, Ui.PANEL, 16f, Ui.LINE)
            setPadding(pad, pad, pad, Ui.px(c, 16f))
            addView(c.uiText(title, 18f, Ui.uiSemi))
            message?.let { addView(c.uiText(it, 14f, Ui.ui, Ui.TEXT2).apply { setLineSpacing(0f, 1.25f) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.px(c, 10f) }) }
            input?.let {
                field = EditText(c).apply {
                    setText(it); setSelection(it.length); typeface = Ui.mono; textSize = 15f; setTextColor(Ui.TEXT)
                    background = Ui.box(c, Ui.FIELD, 8f, 0xFF2F3136.toInt())
                    val p = Ui.px(c, 12f); setPadding(p, p, p, p)
                    inputType = InputType.TYPE_CLASS_TEXT; imeOptions = EditorInfo.IME_ACTION_DONE; isSingleLine = true
                    setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_DONE) { d.dismiss(); onAction(text.toString()); true } else false }
                }
                addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.px(c, 14f) })
            }
            addView(LinearLayout(c).apply {
                gravity = Gravity.END
                fun btn(label: String, fill: Int, fg: Int, click: () -> Unit) = c.uiText(label, 14.5f, Ui.uiSemi, fg).apply {
                    gravity = Gravity.CENTER
                    val h = Ui.px(c, 18f); setPadding(h, 0, h, 0)
                    background = Ui.pressable(c, Ui.box(c, fill, 8f, if (fill == Ui.RAISED) 0xFF33353A.toInt() else 0), 8f)
                    isClickable = true; setOnClickListener { click() }
                }
                addView(btn("Cancel", Ui.RAISED, Ui.TEXT) { d.dismiss() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.px(c, 44f)))
                addView(btn(action, if (danger) Ui.DANGER else Ui.ACCENT, Ui.ON_ACCENT) { d.dismiss(); onAction(field?.text?.toString() ?: "") },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.px(c, 44f)).apply { marginStart = Ui.px(c, 10f) })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.px(c, 20f) })
        }
        d.setContentView(body)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout((c.resources.displayMetrics.widthPixels * 0.86f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.6f)
            if (input != null) setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        UiMenu.unrollDialog(d, body, fromBottom = false)
        d.show()
        field?.requestFocus()
    }
}

/** Ellipsizing single-line helper. */
fun TextView.oneLine(): TextView { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; return this }
