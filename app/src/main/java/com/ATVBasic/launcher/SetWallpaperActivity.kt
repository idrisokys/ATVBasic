package com.ATVBasic.launcher

import android.app.Activity
import android.app.UiModeManager
import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
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
    var sys: Bitmap? = null
    try {
        val input = app.contentResolver.openInputStream(uri) ?: return R.string.share_failed
        input.use { WallpaperStore.copy(it, tmp) }
        // A copy fitted (not cropped) to the device's orientation, for the system wallpaper.
        val fitted = decodeFit(app, tmp)
        sys = fitted
        // The launcher's own wallpaper (this also deletes the source file).
        if (!WallpaperStore.importFile(app, tmp, fit = true)) return R.string.share_failed
        // The chosen picture must not be replaced 60 s later by the slideshow.
        app.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(Prefs.SLIDESHOW, false).apply()
        return if (fitted == null) R.string.share_done_launcher else setSystem(app, fitted)
    } finally {
        tmp.delete()
        sys?.recycle()
    }
}

/** Home + lock; home only when there is no lock screen (TV); launcher only when unsupported. */
private fun setSystem(app: Context, bmp: Bitmap): Int {
    return try {
        val wm = WallpaperManager.getInstance(app)
        if (!wm.isWallpaperSupported || !wm.isSetWallpaperAllowed) {
            R.string.share_done_launcher
        } else {
            try {
                wm.setBitmap(bmp, null, true, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
                R.string.share_done_all
            } catch (ex: Exception) {
                wm.setBitmap(bmp, null, true, WallpaperManager.FLAG_SYSTEM)
                R.string.share_done_system
            }
        }
    } catch (ex: Exception) {
        R.string.share_done_launcher
    }
}

/** Phone: portrait, TV: landscape screen size; the picture is centred uncropped, the rest is black. */
private fun decodeFit(app: Context, src: File): Bitmap? {
    val dm = app.resources.displayMetrics
    var a = minOf(dm.widthPixels, dm.heightPixels)
    var b = maxOf(dm.widthPixels, dm.heightPixels)
    if (b > 2560) { // protect memory on 4K TVs
        val k = 2560f / b
        a = Math.round(a * k)
        b = 2560
    }
    val um = app.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    val tv = um != null && um.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    val tw = if (tv) b else a
    val th = if (tv) a else b

    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(src.path, o)
    if (o.outWidth <= 0 || o.outHeight <= 0) return null

    o.inJustDecodeBounds = false
    o.inSampleSize = WallpaperStore.sampleSize(o.outWidth, o.outHeight, tw, th)
    o.inPreferredConfig = Bitmap.Config.ARGB_8888
    val decoded = BitmapFactory.decodeFile(src.path, o) ?: return null

    val out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
    try {
        val s = minOf(tw / decoded.width.toFloat(), th / decoded.height.toFloat())
        val m = Matrix()
        m.postScale(s, s)
        m.postTranslate((tw - decoded.width * s) / 2f, (th - decoded.height * s) / 2f)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(decoded, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
    } finally {
        decoded.recycle()
    }
    return out
}
