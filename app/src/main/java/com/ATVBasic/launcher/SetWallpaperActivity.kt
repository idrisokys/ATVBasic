package com.ATVBasic.launcher

import android.app.Activity
import android.app.UiModeManager
import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileInputStream

/**
 * Headless: takes an image from the Share menu ("Duvar kağıdı yap"), fits it on screen without
 * cropping and sets it both as the launcher's own wallpaper and as the system home / lock wallpaper.
 */
class SetWallpaperActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var label: TextView

    /** Shows [res] in a transparent label (like the launcher's own messages) and optionally closes after [closeMs]. */
    private fun say(res: Int, closeMs: Long = 0L) {
        label.setText(res)
        if (closeMs > 0L) ui.postDelayed({ finish() }, closeMs)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) // touches go through to the app below

        val dp = resources.displayMetrics.density
        label = TextView(this)
        label.setBackgroundResource(R.drawable.label_bg)
        label.setTextColor(getColor(R.color.text))
        label.textSize = 18f
        label.setShadowLayer(4f, 0f, 1f, 0xCC000000.toInt())
        label.setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), (8 * dp).toInt())
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL,
        )
        lp.topMargin = (28 * dp).toInt()
        val root = FrameLayout(this)
        root.addView(label, lp)
        setContentView(root)

        val uri = extractUri(intent)
        if (uri == null) {
            say(R.string.share_no_image, 2500L)
            return
        }

        say(R.string.share_working)
        val app = applicationContext
        Thread({
            val msg = try {
                applyWallpaper(app, uri)
            } catch (ex: Exception) {
                R.string.share_failed
            } catch (ex: OutOfMemoryError) {
                R.string.share_failed
            }
            runOnUiThread { say(msg, 2500L) }
        }, "atv-share-wallpaper").start()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}

@Suppress("DEPRECATION")
private fun extractUri(i: Intent?): Uri? {
    if (i == null) return null
    i.data?.let { return it }
    val stream: Uri? = if (Build.VERSION.SDK_INT >= 33) {
        i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        i.getParcelableExtra(Intent.EXTRA_STREAM)
    }
    if (stream != null) return stream
    val clip = i.clipData
    if (clip != null && clip.itemCount > 0) return clip.getItemAt(0).uri
    return null
}

private fun applyWallpaper(app: Context, uri: Uri): Int {
    val tmp = File(app.cacheDir, "share_wallpaper.tmp")
    val sysFile = File(app.cacheDir, "share_wallpaper_system.jpg")
    try {
        val input = app.contentResolver.openInputStream(uri) ?: return R.string.share_failed
        input.use { WallpaperStore.copy(it, tmp) }
        // A copy fitted (not cropped) to the device's orientation, for the system wallpaper (scaled in Rust).
        val dm = app.resources.displayMetrics
        val um = app.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        val tv = um != null && um.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        val fitted = Native.wpSystemImage(tmp, sysFile, dm.widthPixels, dm.heightPixels, tv) == true
        // The launcher's own wallpaper (this also deletes the source file).
        if (!WallpaperStore.importFile(app, tmp, fit = true)) return R.string.share_failed
        // The chosen picture must not be replaced 60 s later by the slideshow.
        app.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(Prefs.SLIDESHOW, false).apply()
        return if (!fitted) R.string.share_done_launcher else setSystem(app, sysFile)
    } finally {
        tmp.delete()
        sysFile.delete()
    }
}

/** Home + lock; home only when there is no lock screen (TV); launcher only when unsupported. */
private fun setSystem(app: Context, jpeg: File): Int {
    return try {
        val wm = WallpaperManager.getInstance(app)
        if (!wm.isWallpaperSupported || !wm.isSetWallpaperAllowed) {
            R.string.share_done_launcher
        } else {
            try {
                FileInputStream(jpeg).use { wm.setStream(it, null, true, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK) }
                R.string.share_done_all
            } catch (ex: Exception) {
                FileInputStream(jpeg).use { wm.setStream(it, null, true, WallpaperManager.FLAG_SYSTEM) }
                R.string.share_done_system
            }
        }
    } catch (ex: Exception) {
        R.string.share_done_launcher
    }
}
