package com.ATVBasic.launcher

import android.app.Activity
import android.content.Intent
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
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.io.File

/**
 * Launcher'ın kendi dosya seçicisi (uzaktan kumanda ile gezilir; klasör listeleme Rust'ta). Uygulama listesinin (aşağı tuşuyla açılan ekran)
 * üstündeki dosya simgesiyle açılır. OK: klasöre gir / dosyayı seç-bırak. Bir dosyada OK'e (ya da dokunmatikte dosyaya) basılı tutmak dosyayı varsayılan uygulamayla açar. En üstteki "Gönder" satırı seçilen
 * dosyaları paylaşımı açık tüm cihazlara yollar ([ShareSendService]). Geri: üst klasör, kökte kapanır.
 */
class FilePickActivity : Activity() {

    private class Row(val kind: Int, val file: File?, val size: Long = 0L) // 0 gönder, 1 üst klasör, 2 klasör, 3 dosya

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
                        Formatter.formatShortFileSize(this@FilePickActivity, r.size)
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
            setOnItemClickListener { _, _, pos, _ -> if (!swallowClick) onRow(rows[pos]) }
            setOnItemLongClickListener { _, _, pos, _ -> openFile(rows[pos]); true }
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

        // Klasör listeleme Rust'ta: gizliler hariç, önce klasörler, sonra dosyalar, ada göre sıralı.
        val all = Native.listDir(d)
        if (all == null) {
            infoView.text = getString(R.string.pick_unreadable)
        } else {
            infoView.text = getString(R.string.pick_hint)
            for (e in all) rows.add(Row(if (e.isDir) 2 else 3, File(d, e.name), e.size))
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

    // Uzaktan kumandada OK'e basılı tutma: ListView bunu uzun tıklama saymaz, tuşları kendimiz izleriz.
    private var longDone = false
    private var swallowClick = false

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val k = e.keyCode
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (e.action == KeyEvent.ACTION_DOWN) {
                if (e.repeatCount == 0) longDone = false
                else if (!longDone) {
                    longDone = true
                    val pos = list.selectedItemPosition
                    if (pos in rows.indices) openFile(rows[pos])
                    return true
                } else return true
            } else if (e.action == KeyEvent.ACTION_UP && longDone) {
                // Basılı tutma dosyayı açtı; tuş bırakılınca normal tıklama (seç/bırak) çalışmasın.
                swallowClick = true
                try { return super.dispatchKeyEvent(e) } finally {
                    swallowClick = false
                    longDone = false
                }
            }
        }
        return super.dispatchKeyEvent(e)
    }

    /** Dosyayı sistemin varsayılan açma eylemiyle (ACTION_VIEW) açar; klasör ve diğer satırlarda bir şey yapmaz. */
    private fun openFile(r: Row) {
        if (r.kind != 3) return
        val f = r.file ?: return
        try {
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndType(OpenProvider.uriFor(this, f), OpenProvider.mimeOf(f.name))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(i)
        } catch (e: Exception) {
            Notice.show(this, getString(R.string.pick_open_failed))
        }
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
