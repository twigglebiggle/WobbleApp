package com.levkar.wobble.wallpaper

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.levkar.wobble.settings.SettingsActivity

/** Opened from the "Settings" button in Android's wallpaper preview: the app's one Settings screen. */
class WallpaperSettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, SettingsActivity::class.java))
        finish()
    }
}
