package com.levkar.wobble.settings

import android.content.Context

/**
 * The settings backbone. Every option is ONE line in [Options.all]; the Settings screen is drawn from that list,
 * so adding an option = add a line (and read it where it's used), removing one = delete the line.
 * Values live in SharedPreferences ([file] + [key]), so an option can also wrap a pref that already exists
 * (e.g. the viewer's Fit/Fill, the gallery's New-button style) without migrating anything.
 */
enum class Section(val title: String) {
    RINGS("Tap rings"), VIEWER("Viewer & wallpaper"), GALLERY("Gallery"), EDITOR("Editor"), FEEL("Feel"), LOOK("Look")
}

sealed class Option(val section: Section, val title: String, val note: String?, val file: String, val key: String) {
    protected fun prefs(c: Context) = c.getSharedPreferences(file, Context.MODE_PRIVATE)
    /** Back to the default. */
    fun reset(c: Context) { if (key.isNotEmpty()) prefs(c).edit().remove(key).apply() }
}

/** On/off. [labels] = two segment names (on, off) to show it as a two-way choice instead of a switch. */
class Toggle(section: Section, title: String, note: String? = null, file: String = Options.FILE, key: String,
             val default: Boolean, val labels: Pair<String, String>? = null) : Option(section, title, note, file, key) {
    fun get(c: Context) = prefs(c).getBoolean(key, default)
    fun set(c: Context, v: Boolean) = prefs(c).edit().putBoolean(key, v).apply()
}

/** One of a few named values (stored as the index). */
class Choice(section: Section, title: String, note: String? = null, file: String = Options.FILE, key: String,
             val labels: List<String>, val default: Int) : Option(section, title, note, file, key) {
    fun get(c: Context) = prefs(c).getInt(key, default).coerceIn(0, labels.lastIndex)
    fun set(c: Context, v: Int) = prefs(c).edit().putInt(key, v.coerceIn(0, labels.lastIndex)).apply()
    fun label(c: Context) = labels[get(c)]
}

/** A whole number in [min]..[max] (shown as a slider with a field, like the editor's). */
class Slider(section: Section, title: String, note: String? = null, file: String = Options.FILE, key: String,
             val min: Int, val max: Int, val default: Int, val unit: String = "") : Option(section, title, note, file, key) {
    fun get(c: Context) = prefs(c).getInt(key, default).coerceIn(min, max)
    fun set(c: Context, v: Int) = prefs(c).edit().putInt(key, v.coerceIn(min, max)).apply()
}

/** A colour (ARGB), picked with the colour picker. */
class ColorOption(section: Section, title: String, note: String? = null, file: String = Options.FILE, key: String,
                  val default: Int) : Option(section, title, note, file, key) {
    fun get(c: Context) = prefs(c).getInt(key, default)
    fun set(c: Context, v: Int) = prefs(c).edit().putInt(key, v).apply()
}

/** A row that opens something (e.g. a sheet) instead of storing a value. */
class Action(section: Section, title: String, note: String? = null, val run: (Context) -> Unit) :
    Option(section, title, note, Options.FILE, "")

object Options {
    const val FILE = "settings"

    // ---- the registry: one line per option, in screen order ----

    val TAP_RINGS = Choice(Section.RINGS, "Show tap rings", "Where the ring appears when you tap", key = "tap_rings",
        labels = listOf("Off", "Overlay", "On image"), default = 1)

    val RING_COLOR = ColorOption(Section.RINGS, "Colour", key = "ring_color", default = 0xFFFFFFFF.toInt())
    val RING_WIDTH = Slider(Section.RINGS, "Thickness", key = "ring_width", min = 5, max = 100, default = 35)
    val RING_SIZE = Slider(Section.RINGS, "Size", key = "ring_size", min = 10, max = 100, default = 50)
    val RING_FADE = Slider(Section.RINGS, "Fade time", "How long a ring stays", key = "ring_fade", min = 10, max = 100, default = 40)

    val VIEW_FILL = Toggle(Section.VIEWER, "Viewer opens in", file = "viewer", key = "fill", default = false, labels = "Fill" to "Fit")
    val VIEW_ROTATE = Choice(Section.VIEWER, "Viewer rotation", "Auto follows how you hold the phone", file = "viewer", key = "rotate",
        labels = listOf("Auto", "Landscape", "Portrait"), default = 0)
    val FIT_SURROUND = Choice(Section.VIEWER, "Around a Fit picture", "New wallpaper framings start with this", key = "fit_surround",
        labels = listOf("Black", "Graphite", "Blur"), default = 2)
    val SHOW_BUILD = Toggle(Section.VIEWER, "Show build tag", "The small version line under the viewer bar", key = "show_build", default = true)

    val COLUMNS = Choice(Section.GALLERY, "Columns", key = "gallery_columns", labels = listOf("2", "3"), default = 0)
    val NAMES = Toggle(Section.GALLERY, "Names on tiles", key = "gallery_names", default = true)
    val SORT = Choice(Section.GALLERY, "Sort by", key = "gallery_sort", labels = listOf("Last edited", "Name"), default = 0)
    val NEW_STYLE = Choice(Section.GALLERY, "New button", "Long-press the button to cycle it too", file = "gallery", key = "new_style",
        labels = listOf("Corner", "Center", "Pill"), default = 0)

    val NEW_BUBBLE_MODERN = Toggle(Section.EDITOR, "Editing style", "New bubbles: Advanced = Area + Curve, Basic = the original Point + Basic",
        key = "new_bubble_modern", default = true, labels = "Advanced" to "Basic")

    val HANDLES = Choice(Section.EDITOR, "Corner handle size", key = "handle_size", labels = listOf("S", "M", "L"), default = 1)
    val SHOW_KERNEL = Toggle(Section.EDITOR, "Show where the peak can go", "While dragging a Point bubble's center", key = "show_kernel", default = true)
    val DIM_OTHERS = Toggle(Section.EDITOR, "Dim other bubbles", "While one is selected", key = "dim_others", default = false)
    val OUTLINE_STYLE = Action(Section.EDITOR, "Outline style", "all bubbles") { c -> com.levkar.wobble.editor.AppearanceSheet(c) {}.show() }

    val TILT = Slider(Section.FEEL, "Tilt sensitivity", "100 = the original's", key = "tilt_gain", min = 0, max = 200, default = 100)
    val TOUCH = Slider(Section.FEEL, "Touch strength", "How hard pokes and drags push", key = "touch_gain", min = 0, max = 200, default = 100)
    val HAPTICS = Toggle(Section.FEEL, "Haptics", key = "haptics", default = true)
    val ANIMATIONS = Choice(Section.FEEL, "Animations", key = "animations", labels = listOf("Full", "Less", "Off"), default = 0)

    val ACCENT = ColorOption(Section.LOOK, "Accent colour", "Buttons, highlights, sliders (new screens)", key = "accent", default = 0xFFFFBF3C.toInt())
    val RESET_ALL = Action(Section.LOOK, "Reset everything", "All settings back to their defaults") { c ->
        com.levkar.wobble.ui.UiDialog.show(c, "Reset all settings?", "Everything on this screen goes back to its default.", action = "Reset", danger = true) {
            for (o in all) o.reset(c)
            (c as? android.app.Activity)?.recreate()
        }
    }

    val all: List<Option> = listOf(
        TAP_RINGS, RING_COLOR, RING_WIDTH, RING_SIZE, RING_FADE,
        VIEW_FILL, VIEW_ROTATE, FIT_SURROUND, SHOW_BUILD,
        COLUMNS, NAMES, SORT, NEW_STYLE,
        NEW_BUBBLE_MODERN, HANDLES, SHOW_KERNEL, DIM_OTHERS, OUTLINE_STYLE,
        TILT, TOUCH, HAPTICS, ANIMATIONS,
        ACCENT, RESET_ALL)
}
