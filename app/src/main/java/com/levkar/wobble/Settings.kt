package com.levkar.wobble

import android.content.Context
import com.levkar.wobble.settings.Options

/** Shortcuts kept for older call sites; the values live in the settings registry ([Options]). */
object Settings {
    val RING_LABELS get() = Options.TAP_RINGS.labels
    fun ringMode(c: Context): Int = Options.TAP_RINGS.get(c)
    fun setRingMode(c: Context, mode: Int) = Options.TAP_RINGS.set(c, mode)
    /** New bubbles: Area + Curve (true, Lev's default) or the original's Point + Basic. */
    fun newBubbleModern(c: Context): Boolean = Options.NEW_BUBBLE_MODERN.get(c)
}
