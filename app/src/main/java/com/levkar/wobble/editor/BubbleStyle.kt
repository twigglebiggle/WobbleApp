package com.levkar.wobble.editor

import android.content.Context

/** How bubbles are drawn in the editor. App-wide preference, not stored in the images. */
data class BubbleStyle(
    val outlineColor: Int,     // RGB, no alpha
    val outlineAlpha: Float,   // 0..1
    val outlineWidthDp: Float,
    val fillColor: Int,        // RGB, no alpha
    val fillAlpha: Float,      // 0..1
    val pattern: Int,
) {
    enum class Role(val label: String, val freeze: Boolean = false, val chip: String = label) {
        NORMAL("Normal"), SELECTED("Selected"), LOCKED("Locked"), SELECTED_LOCKED("Sel. locked"),
        // Freeze zones (Lev): the same four looks as bubbles, in their own row (chips read the same as the bubble row).
        FREEZE("Freeze zone", true, "Normal"), FREEZE_SELECTED("Selected zone", true, "Selected"),
        FREEZE_LOCKED("Locked zone", true, "Locked"), FREEZE_SEL_LOCKED("Sel. locked zone", true, "Sel. locked")
    }

    companion object {
        const val NONE = 0
        const val HATCH = 1
        const val CROSS = 2
        const val DOTS = 3
        /** Fill strength follows the bubble's weight: shows where and how much it moves. */
        const val DOME = 4
        val PATTERN_LABELS = listOf("None", "Hatch", "Cross", "Dots", "Dome")

        /** Swatches offered in the appearance sheet. */
        val PALETTE = intArrayOf(0xFFFFFF, 0x000000, 0xFFBF3C, 0xFF5252, 0xFF4FC3, 0x7C4DFF, 0x40C4FF, 0x7CC4FF, 0x69F0AE)

        private val DEFAULTS = mapOf(
            Role.NORMAL to BubbleStyle(0xFFFFFF, 0.6f, 1.2f, 0xFFFFFF, 0.10f, NONE),
            Role.SELECTED to BubbleStyle(0xFFBF3C, 1f, 2.2f, 0xFFBF3C, 0.35f, DOME),
            Role.LOCKED to BubbleStyle(0xFFFFFF, 0.3f, 1f, 0x000000, 0.25f, HATCH),
            // Selected but locked: selection color, but dashed + hatched so it still reads as locked.
            Role.SELECTED_LOCKED to BubbleStyle(0xFFBF3C, 1f, 2.2f, 0xFFBF3C, 0.35f, HATCH),
            Role.FREEZE to BubbleStyle(0x7CC4FF, 0.75f, 2f, 0x7CC4FF, 0.43f, HATCH),
            Role.FREEZE_SELECTED to BubbleStyle(0x7CC4FF, 1f, 2.6f, 0x7CC4FF, 0.43f, HATCH),
            Role.FREEZE_LOCKED to BubbleStyle(0x7CC4FF, 0.35f, 1.2f, 0x000000, 0.25f, HATCH),
            Role.FREEZE_SEL_LOCKED to BubbleStyle(0x7CC4FF, 1f, 2.6f, 0x7CC4FF, 0.43f, HATCH),
        )

        // ---- saved colours (Outline style: picked with the colour picker, kept for reuse) ----
        private const val SAVED = "saved_colors"
        const val MAX_SAVED = 6                 // one row on a narrow phone
        fun savedColors(c: Context): List<Int> = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(SAVED, "")!!
            .split(',').mapNotNull { it.toIntOrNull() }
        fun saveColor(c: Context, rgb: Int) {
            val l = (listOf(rgb and 0xFFFFFF) + savedColors(c).filter { it != rgb and 0xFFFFFF } ).take(MAX_SAVED)
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(SAVED, l.joinToString(",")).apply()
        }
        fun removeColor(c: Context, rgb: Int) {
            val l = savedColors(c).filter { it != rgb }
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(SAVED, l.joinToString(",")).apply()
        }

        private const val PREFS = "bubble_style"

        fun load(c: Context, role: Role): BubbleStyle {
            val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val d = DEFAULTS.getValue(role)
            val k = role.name
            return BubbleStyle(
                p.getInt("${k}_oc", d.outlineColor), p.getFloat("${k}_oa", d.outlineAlpha), p.getFloat("${k}_ow", d.outlineWidthDp),
                p.getInt("${k}_fc", d.fillColor), p.getFloat("${k}_fa", d.fillAlpha), p.getInt("${k}_pt", d.pattern))
        }

        fun save(c: Context, role: Role, s: BubbleStyle) {
            val k = role.name
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt("${k}_oc", s.outlineColor).putFloat("${k}_oa", s.outlineAlpha).putFloat("${k}_ow", s.outlineWidthDp)
                .putInt("${k}_fc", s.fillColor).putFloat("${k}_fa", s.fillAlpha).putInt("${k}_pt", s.pattern).apply()
        }

        fun reset(c: Context, role: Role) = save(c, role, DEFAULTS.getValue(role))
    }
}
