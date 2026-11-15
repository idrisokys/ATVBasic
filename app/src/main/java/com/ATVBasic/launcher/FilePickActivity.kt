package com.ATVBasic.launcher

import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.TextUtils
import android.text.format.Formatter
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.io.File

/**
 * Launcher'ın kendi dosya seçicisi (uzaktan kumanda ile gezilir). Ana ekrandaki paylaş simgesine
 * uzun basınca açılır. OK: klasöre gir / dosyayı seç-bırak. En üstteki "Gönder" satırı seçilen
 * dosyaları paylaşımı açık tüm cihazlara yollar ([ShareSendService]). Geri: üst klasör, kökte kapanır.
 */
class FilePickActivity : Activity() {

    private class Row(val kind: Int, val file: File?) // 0 gönder, 1 üst klasör, 2 klasör, 3 dosya

    private val rows = ArrayList<Row>()
    private val selected = LinkedHashSet<String>()
    private lateinit var root: File
    private lateinit var dir: File
    private lateinit var list: ListView
    private lateinit var titleView: TextView
    private lateinit var infoView: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private val adapter = object : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val t = (convertView as? TextView) ?: TextView(this@FilePickActivity).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(16), 0)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
                layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
            }
            val r = rows[position]
            var color = getColor(R.color.text)
            t.setTypeface(null, Typeface.NORMAL)
            t.text = when (r.kind) {
                0 -> {
                    t.setTypeface(null, Typeface.BOLD)
                    color = getColor(if (selected.isEmpty()) R.color.text_dim else R.color.focus)
                    getString(R.string.pick_send, selected.size)
                }
                1 -> {
                    color = getColor(R.color.text_dim)
                    getString(R.string.pick_up)
                }
                2 -> "▸  " + r.file!!.name
                else -> {
                    val on = selected.contains(r.file!!.path)
                    if (on) color = getColor(R.color.focus)
                    (if (on) "[x]  " else "[  ]  ") + r.file.name + "   " +
                        Formatter.formatShortFileSize(this@FilePickActivity, r.file.length())
                }
            }
            t.setTextColor(color)
            return t
        }
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = Environment.getExternalStorageDirectory()
        dir = root

        titleView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.text))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.START
        }
        infoView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(getColor(R.color.text_dim))
            setPadding(0, dp(4), 0, dp(8))
        }
        list = ListView(this).apply {
            divider = null
            isVerticalScrollBarEnabled = false
            setSelector(R.drawable.focus_cell)
            adapter = this@FilePickActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> onRow(rows[pos]) }
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(getColor(R.color.bg))
                setPadding(dp(32), dp(20), dp(32), dp(12))
                addView(titleView)
                addView(infoView)
                addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            },
        )

        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            ) { goBack() }
        }
        load(root, null)
        list.requestFocus()
    }

    @Deprecated("Android 13 öncesi geri tuşu")
    override fun onBackPressed() {
        if (Build.VERSION.SDK_INT < 33) goBack() else super.onBackPressed()
    }

    private fun goBack() {
        if (dir.path == root.path) {
            finish()
            return
        }
        val from = dir
        load(dir.parentFile ?: root, from)
    }

    private fun load(d: File, cameFrom: File?) {
        dir = d
        rows.clear()
        rows.add(Row(0, null))
        if (d.path != root.path) rows.add(Row(1, null))

        val all = d.listFiles()
        if (all == null) {
            infoView.text = getString(R.string.pick_unreadable)
        } else {
            infoView.text = getString(R.string.pick_hint)
            val vis = all.filter { !it.name.startsWith(".") }
            vis.filter { it.isDirectory }.sortedBy { it.name.lowercase() }.forEach { rows.add(Row(2, it)) }
            vis.filter { it.isFile }.sortedBy { it.name.lowercase() }.forEach { rows.add(Row(3, it)) }
        }
        titleView.text = if (d.path == root.path) getString(R.string.pick_root) else d.path.removePrefix(root.path)
        adapter.notifyDataSetChanged()

        // İlk satır "Gönder"; odak ilk klasör/dosyaya (ya da geldiğimiz klasöre) düşer.
        var pos = rows.indexOfFirst { it.kind >= 2 }.let { if (it < 0) 0 else it }
        if (cameFrom != null) {
            val k = rows.indexOfFirst { it.file?.path == cameFrom.path }
            if (k >= 0) pos = k
        }
        list.setSelection(pos)
    }

    private fun onRow(r: Row) {
        when (r.kind) {
            0 -> send()
            1 -> goBack()
            2 -> load(r.file!!, null)
            else -> {
                val p = r.file!!.path
                if (!selected.add(p)) selected.remove(p)
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun send() {
        if (selected.isEmpty()) {
            Notice.show(this, getString(R.string.pick_none))
            return
        }
        // Android 17+: yerel ağ erişimi çalışma zamanı izni (cihaz keşfi + gönderim için gerekli).
        if (Build.VERSION.SDK_INT >= 37 &&
            checkSelfPermission(PERM_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(PERM_LOCAL_NETWORK), REQ_NET)
            return
        }
        go()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_NET) return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            go()
        } else {
            Notice.show(this, getString(R.string.notice_send_failed))
        }
    }

    private fun go() {
        if (!ShareSendService.startPaths(this, ArrayList(selected))) {
            Notice.show(this, getString(R.string.notice_send_failed))
            return
        }
        finish()
    }

    private companion object {
        const val REQ_NET = 9
        const val PERM_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}
