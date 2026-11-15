package com.ATVBasic.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Handler
import android.util.LruCache
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * Renders each app's banner (or icon) into a small ARGB_8888 (transparent) bitmap on a background
 * thread and keeps about three dozen of them in an LRU cache. Everything is dropped when the
 * launcher hides.
 */
internal class IconLoader(
    ctx: Context,
    private val io: Executor,
    private val ui: Handler,
    private val onLoaded: Runnable,
    private val w: Int,
    private val h: Int,
) {
    private val pm = ctx.packageManager
    private val cache = object : LruCache<String, Bitmap>(maxOf(1, w * h * 4 * 36)) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val pending = HashSet<String>() // UI thread only
    private var generation = 0               // UI thread only

    fun peek(pkg: String): Bitmap? = cache.get(pkg)

    fun request(entry: AppEntry) {
        if (!pending.add(entry.pkg)) return
        val gen = generation
        try {
            io.execute {
                val bmp = render(entry)
                ui.post {
                    pending.remove(entry.pkg)
                    if (gen == generation) { // otherwise the launcher was hidden meanwhile
                        if (bmp != null) cache.put(entry.pkg, bmp)
                        onLoaded.run()
                    }
                }
            }
        } catch (ex: RejectedExecutionException) {
            pending.remove(entry.pkg) // shutting down
        }
    }

    /** Free all bitmaps (called when the launcher is no longer visible). */
    fun clear() {
        generation++
        cache.evictAll()
    }

    private fun render(entry: AppEntry): Bitmap? {
        return try {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val banner: Drawable? = entry.info.loadBanner(pm)
            val drawable: Drawable
            if (banner != null) {
                banner.setBounds(0, 0, w, h)
                drawable = banner
            } else {
                val icon = entry.info.loadIcon(pm)
                val s = h * 7 / 10
                val l = (w - s) / 2
                val t = (h - s) / 2
                icon.setBounds(l, t, l + s, t + s)
                drawable = icon
            }
            drawable.draw(canvas)
            bmp
        } catch (ex: Exception) {
            null // leave it empty
        } catch (ex: OutOfMemoryError) {
            null
        }
    }
}
