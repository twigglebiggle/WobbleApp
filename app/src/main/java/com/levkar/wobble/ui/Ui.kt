package com.levkar.wobble.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable

/**
 * The look ("Studio Workbench, floating bars", chosen 2026-10-07): graphite panels, hairlines, one amber
 * accent, Space Grotesk for text and JetBrains Mono for numbers/names. Every screen takes its colors,
 * sizes and fonts from here, so the style can change in one place.
 */
object Ui {
    // ---- colors ----
    const val BG = 0xFF16171A.toInt()          // screen background
    const val PANEL = 0xFF1C1D21.toInt()       // bars, sheets, menus
    const val PANEL_GLASS = 0xD81C1D21.toInt() // floating bar over art
    const val RAISED = 0xFF25272B.toInt()      // secondary buttons
    const val FIELD = 0xFF121316.toInt()       // number fields
    const val LINE = 0xFF2A2C31.toInt()        // hairlines, borders
    const val LINE_SOFT = 0xFF222428.toInt()
    const val TEXT = 0xFFE6E6E3.toInt()
    const val TEXT2 = 0xFFB9BAB6.toInt()       // labels on buttons
    const val MUTED = 0xFF7B7D82.toInt()       // captions
    /** The one accent (amber by default; Settings → Look). Read when a screen starts. */
    var ACCENT = 0xFFFFBF3C.toInt(); private set
    const val FREEZE = 0xFF7CC4FF.toInt()      // Freeze zones (cool blue: "doesn't move")
    const val ON_FREEZE = 0xFF0E1A24.toInt()
    /** Text/icons on the accent: dark on light accents, light on dark ones. */
    var ON_ACCENT = 0xFF16171A.toInt(); private set
    /** The accent at a given alpha (0..255), for washes and glows. */
    fun accentA(alpha: Int) = (alpha shl 24) or (ACCENT and 0xFFFFFF)
    /** Animation speed factor: 1 = full, 0.5 = less, 0 = off (Settings → Feel). */
    var anim = 1f; private set
    var haptics = true; private set
    const val DANGER = 0xFFFF6B5A.toInt()
    const val PRESS = 0x22FFFFFF

    // ---- fonts (assets/fonts, SIL OFL) ----
    lateinit var ui: Typeface; private set          // Space Grotesk Medium
    lateinit var uiSemi: Typeface; private set
    lateinit var uiBold: Typeface; private set
    lateinit var mono: Typeface; private set        // JetBrains Mono
    lateinit var monoMedium: Typeface; private set
    private var loaded = false

    fun init(c: Context) {
        // Settings that change the look/feel are re-read every time a screen starts.
        val o = com.levkar.wobble.settings.Options
        ACCENT = o.ACCENT.get(c) or 0xFF000000.toInt()
        val lum = 0.299f * android.graphics.Color.red(ACCENT) + 0.587f * android.graphics.Color.green(ACCENT) + 0.114f * android.graphics.Color.blue(ACCENT)
        ON_ACCENT = if (lum > 140f) 0xFF16171A.toInt() else 0xFFF2F2EF.toInt()
        anim = when (o.ANIMATIONS.get(c)) { 1 -> 0.5f; 2 -> 0f; else -> 1f }
        haptics = o.HAPTICS.get(c)
        if (loaded) return
        val a = c.applicationContext.assets
        fun load(n: String) = runCatching { Typeface.createFromAsset(a, "fonts/$n.ttf") }.getOrDefault(Typeface.DEFAULT)
        ui = load("ui_medium"); uiSemi = load("ui_semibold"); uiBold = load("ui_bold")
        mono = load("mono"); monoMedium = load("mono_medium")
        loaded = true
    }

    // ---- sizes ----
    /** Haptic tick if enabled in Settings. */
    fun haptic(v: android.view.View, type: Int = android.view.HapticFeedbackConstants.LONG_PRESS) { if (haptics) v.performHapticFeedback(type) }

    fun dp(c: Context, v: Float) = v * c.resources.displayMetrics.density
    fun px(c: Context, v: Float) = (v * c.resources.displayMetrics.density + 0.5f).toInt()

    /** Rounded rectangle with optional hairline border. */
    fun box(c: Context, fill: Int, radiusDp: Float, stroke: Int = 0, strokeDp: Float = 1f): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill); cornerRadius = dp(c, radiusDp)
            if (stroke != 0) setStroke(px(c, strokeDp).coerceAtLeast(1), stroke)
        }

    /** [content] with a soft press ripple clipped to the same rounded shape. */
    fun pressable(c: Context, content: Drawable?, radiusDp: Float, color: Int = PRESS): Drawable =
        RippleDrawable(ColorStateList.valueOf(color), content, box(c, 0xFFFFFFFF.toInt(), radiusDp))
}
