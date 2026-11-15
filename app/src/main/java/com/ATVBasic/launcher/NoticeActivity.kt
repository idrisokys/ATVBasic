package com.ATVBasic.launcher

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout

/** Şeffaf, dokunmaya kapalı, kendiliğinden kapanan bildirim yazısı. */
class NoticeActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        )
        val text = intent.getStringExtra(Notice.EXTRA_TEXT).orEmpty()
        val dp = resources.displayMetrics.density
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL,
        )
        lp.topMargin = (28 * dp).toInt()
        val root = FrameLayout(this)
        root.addView(Notice.makeLabel(this, text), lp)
        setContentView(root)
        ui.postDelayed({ finish() }, Notice.SHOW_MS)
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
