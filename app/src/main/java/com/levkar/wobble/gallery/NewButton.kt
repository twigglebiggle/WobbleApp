package com.levkar.wobble.gallery

import android.content.Context
import android.graphics.Outline
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.levkar.wobble.ui.Icon
import com.levkar.wobble.ui.Ui
import com.levkar.wobble.ui.uiText

/**
 * The gallery's one floating control: New. Long-press shifts it to the next of three looks (Lev's idea):
 * corner circle → center circle → center pill "+ New wobble" → corner circle … Remembered.
 * The swap: shrink and fade out (90 ms), move, pop back in with a small overshoot (180 ms).
 */
class NewButton(c: Context) : LinearLayout(c) {

    private val d = resources.displayMetrics.density
    private val label = c.uiText("New wobble", 15f, Ui.uiSemi, Ui.ON_ACCENT)
    private val prefs = c.getSharedPreferences("gallery", Context.MODE_PRIVATE)
    var style = prefs.getInt("new_style", CORNER).coerceIn(0, 2); private set

    init {
        gravity = Gravity.CENTER
        val s = (28 * d).toInt()
        addView(ImageView(c).apply { setImageDrawable(Icon.NEW.drawable(Ui.ON_ACCENT, 2f)) }, LayoutParams(s, s))
        addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = (8 * d).toInt() })
        background = Ui.pressable(c, Ui.box(c, Ui.ACCENT, 999f), 999f, 0x33000000)
        elevation = 12 * d
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, v.height / 2f)
        }
        isClickable = true; isLongClickable = true
        setOnLongClickListener { Ui.haptic(this); cycle(); true }
    }

    /** Call once after adding to its FrameLayout parent. */
    fun applyStyle() {
        val lp = (layoutParams as? FrameLayout.LayoutParams) ?: return
        val pill = style == PILL
        label.visibility = if (pill) VISIBLE else GONE
        val h = ((if (pill) 54 else 64) * d).toInt()
        lp.height = h
        lp.width = if (pill) FrameLayout.LayoutParams.WRAP_CONTENT else h
        lp.gravity = Gravity.BOTTOM or (if (style == CORNER) Gravity.END else Gravity.CENTER_HORIZONTAL)
        lp.setMargins(0, 0, if (style == CORNER) (20 * d).toInt() else 0, ((if (pill) 26 else 24) * d).toInt())
        val p = if (pill) (24 * d).toInt() else 0
        setPadding(p, 0, p, 0)
        layoutParams = lp
    }

    private fun cycle() {
        animate().cancel()
        animate().scaleX(0.6f).scaleY(0.6f).alpha(0f).setDuration((90 * Ui.anim).toLong()).setInterpolator(DecelerateInterpolator()).withEndAction {
            style = (style + 1) % 3
            prefs.edit().putInt("new_style", style).apply()
            applyStyle()
            animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration((180 * Ui.anim).toLong()).setInterpolator(OvershootInterpolator(2f)).start()
        }.start()
    }

    companion object { const val CORNER = 0; const val CENTER = 1; const val PILL = 2 }
}
