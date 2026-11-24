package com.ATVBasic.launcher

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
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
 * Launcher'ın kendi dosya seçicisi (uzaktan kumanda ile gezilir; klasör listeleme Rust'ta). Ana ekrandaki dock'un sabit 6.
 * simgesi (Dosyalar) ile açılır ve varsayılan olarak İndirilenler (Download) klasöründen başlar; dosyalar en yeniden eskiye sıralıdır.
 * OK: klasöre gir / dosyayı seç-bırak. Bir dosyadayken SOL tuşu "Aç / Gönder" menüsünü açar: Aç dosyayı varsayılan uygulamayla
 * açar, Gönder seçili dosyaları (seçim yoksa üzerindeki dosyayı) paylaşımı açık tüm cihazlara yollar ([ShareSendService]).
 * Geri: üst klasör, depolama kökünde kapanır.
 */
class FilePickActivity : Activity() {

    private class Row(val kind: Int, val file: File?, val size: Long = 0L) // 1 üst klasör, 2 klasör, 3 dosya

    private val rows = ArrayList<Row>()
    private val selected = LinkedHashSet<String>()
    private lateinit var root: File
    private lateinit var dir: File
    private lateinit var list: ListView
    private lateinit var titleView: TextView
    private lateinit var infoView: TextView
    private var menu: Dialog? = null

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
        dir = startDir()

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
            // Dokunmatik: dosyaya uzun basınca da aynı Aç / Gönder menüsü (kumandada SOL tuşu).
            setOnItemLongClickListener { _, _, pos, _ -> showMenu(pos) }
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
        load(dir, null)
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
        if (d.path != root.path) rows.add(Row(1, null))

        // Klasör listeleme Rust'ta (gizliler hariç). Burada klasörler üstte kalır, dosyalar en yeniden eskiye dizilir.
        val all = Native.listDir(d)
        if (all == null) {
            infoView.text = getString(R.string.pick_unreadable)
        } else {
            infoView.text = getString(R.string.pick_hint)
            val dirs = ArrayList<Row>()
            val files = ArrayList<Pair<Long, Row>>()
            for (e in all) {
                val f = File(d, e.name)
                if (e.isDir) dirs.add(Row(2, f)) else files.add(f.lastModified() to Row(3, f, e.size))
            }
            rows.addAll(dirs)
            files.sortByDescending { it.first }
            for ((_, r) in files) rows.add(r)
        }
        titleView.text = if (d.path == root.path) getString(R.string.pick_root) else d.path.removePrefix(root.path)
        adapter.notifyDataSetChanged()

        // Odak: geldiğimiz klasöre; yoksa en yeni dosyaya; o da yoksa ilk klasöre.
        var pos = rows.indexOfFirst { it.kind == 3 }
        if (pos < 0) pos = rows.indexOfFirst { it.kind >= 2 }
        if (pos < 0) pos = 0
        if (cameFrom != null) {
            val k = rows.indexOfFirst { it.file?.path == cameFrom.path }
            if (k >= 0) pos = k
        }
        list.setSelection(pos)
    }

    /** İndirilenler klasörü (varsa) başlangıç klasörüdür; yoksa depolama kökü. */
    @Suppress("DEPRECATION")
    private fun startDir(): File {
        val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return if (dl != null && dl.isDirectory && (dl.path == root.path || dl.path.startsWith(root.path + "/"))) dl else root
    }

    // SOL tuşu: dosya satırındayken "Aç / Gönder" menüsü.
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && menu == null) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0 && showMenu(list.selectedItemPosition)) return true
            if (isFileRowFocused()) return true // tuş bırakma / tekrar olaylarını yut
        }
        return super.dispatchKeyEvent(e)
    }

    private fun isFileRowFocused(): Boolean {
        val pos = list.selectedItemPosition
        return pos in rows.indices && rows[pos].kind == 3
    }

    /** Odaktaki dosya için "Aç" ve "Gönder" seçeneklerini gösterir; dosya satırında değilse false. */
    private fun showMenu(pos: Int): Boolean {
        if (pos !in rows.indices || rows[pos].kind != 3) return false
        val row = rows[pos]
        menu?.dismiss()
        menu = AlertDialog.Builder(this, R.style.Theme_ATVBasic_Dialog)
            .setTitle(row.file!!.name)
            .setItems(arrayOf(getString(R.string.pick_menu_open), getString(R.string.pick_menu_send))) { _, which ->
                if (which == 0) openFile(row) else send(row)
            }
            .create()
            .also {
                it.setOnDismissListener { menu = null }
                it.show()
            }
        return true
    }

    override fun onDestroy() {
        menu?.dismiss()
        super.onDestroy()
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
            1 -> goBack()
            2 -> load(r.file!!, null)
            else -> {
                val p = r.file!!.path
                if (!selected.add(p)) selected.remove(p)
                adapter.notifyDataSetChanged()
            }
        }
    }

    private var pending: ArrayList<String> = ArrayList()

    /** Seçili dosyaları gönderir; hiç seçim yoksa menüyü açtığın dosyayı gönderir. */
    private fun send(focused: Row) {
        pending = if (selected.isNotEmpty()) ArrayList(selected) else ArrayList(listOfNotNull(focused.file?.path))
        if (pending.isEmpty()) {
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
        if (!ShareSendService.startPaths(this, pending)) {
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
