package com.levkar.wobble.wallpaper

import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.widget.Toast
import java.io.File

/** "Set as wallpaper": copy the .wbl into app storage, then open Android's live wallpaper preview. */
object WallpaperSetter {
    /** Opens the framing screen for a library piece (Lev: framing comes before Android's preview). */
    fun frame(activity: Activity, uri: android.net.Uri) =
        activity.startActivity(Intent(activity, WallpaperFrameActivity::class.java).setData(uri))

    /** Background thread. [frame]: the framing to use (saved as the current one, and per piece when [piece] is given). */
    fun set(activity: Activity, bytes: ByteArray, frame: Frame? = null, piece: String? = null) {
        Frame.save(activity, piece, frame ?: Frame())          // no framing given: plain center-crop
        val f = WobbleWallpaperService.file(activity)
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(bytes)
        tmp.renameTo(f)
        activity.runOnUiThread {
            val preview = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                ComponentName(activity, WobbleWallpaperService::class.java))
            try { activity.startActivity(preview) } catch (e: Exception) {
                try { activity.startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)) }
                catch (e2: Exception) { Toast.makeText(activity, "Couldn't open the wallpaper picker", Toast.LENGTH_LONG).show() }
            }
        }
    }
}
