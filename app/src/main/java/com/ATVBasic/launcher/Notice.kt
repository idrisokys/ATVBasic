package com.ATVBasic.launcher

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast

/**
 * Ekranda şeffaf arkaplanlı bildirim yazısı (dosya alındı / gönderildi).
 * Sıra: 1) "Diğer uygulamaların üzerinde göster" izni varsa pencere olarak,
 * 2) yoksa [NoticeActivity] ile, 3) o da olmazsa sistem Toast'ı.
 */
object Notice {
    private val ui = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var wm: WindowManager? = null
    private val hide = Runnable { remove() }

    const val EXTRA_TEXT = "text"
    const val SHOW_MS = 4000L

    fun show(ctx: Context, text: String) {
        val app = ctx.applicationContext
        ui.post {
            if (tryOverlay(app, text)) return@post
            try {
                app.startActivity(
                    Intent(app, NoticeActivity::class.java)
                        .putExtra(EXTRA_TEXT, text)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
                )
            } catch (e: Exception) {
                Toast.makeText(app, text, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Ortak şeffaf yazı görünümü (arkaplan: label_bg, gölgeli beyaz yazı). */
    fun makeLabel(ctx: Context, text: String): TextView {
        val dp = ctx.resources.displayMetrics.density
        return TextView(ctx).apply {
            this.text = text
            setBackgroundResource(R.drawable.label_bg)
            setTextColor(ctx.getColor(R.color.text))
            textSize = 18f
            setShadowLayer(4f, 0f, 1f, 0xCC000000.toInt())
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), (8 * dp).toInt())
        }
    }

    private fun tryOverlay(app: Context, text: String): Boolean {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(app)) return false
        return try {
            remove()
            val w = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val v = makeLabel(app, text)
            val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            )
            lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            lp.y = (28 * app.resources.displayMetrics.density).toInt()
            w.addView(v, lp)
            wm = w
            view = v
            ui.removeCallbacks(hide)
            ui.postDelayed(hide, SHOW_MS)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun remove() {
        try { view?.let { wm?.removeView(it) } } catch (e: Exception) { }
        view = null
    }
}
