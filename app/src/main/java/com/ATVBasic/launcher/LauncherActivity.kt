package com.ATVBasic.launcher

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * ATV Basic. Home = wallpaper, clock + date, Settings button and a dock of 6 pinned apps.
 * Press Down on the dock to open the full app grid; Up on its first row (or Back) closes it.
 */
class LauncherActivity : Activity() {

    private companion object {
        const val TAG = "ATVBasic"
        const val COLUMNS = 6
        const val DOCK_SLOTS = 6
        const val RELOAD_DELAY_MS = 500L
        const val ROTATE_INTERVAL_MS = 60_000L
        const val FIRST_FETCH_DELAY_MS = 2_000L
        const val IDLE_HIDE_MS = 20_000L
        const val LABEL_SHOW_MS = 2_500L
        const val RETRY_MS = 30_000L
        const val REQ_STORAGE = 2
    }

    // ------------------------------------------------------------------ state

    private val ui = Handler(Looper.getMainLooper())
    private val reloadTask = Runnable { scan() }
    private val rotateTask = Runnable { rotateWallpaper() }
    private val hideUiTask = Runnable { hideUi() }
    private val hideLabelTask = Runnable { hideLabel() }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            ui.removeCallbacks(reloadTask) // coalesce bursts of install/remove events
            ui.postDelayed(reloadTask, RELOAD_DELAY_MS)
        }
    }
    private val dateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateDate()
        }
    }
    private val dateFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("d MMMM EEEE", Locale.forLanguageTag("tr-TR"))

    private val slots = ArrayList<View>(DOCK_SLOTS)
    private val dockPkgs = arrayOfNulls<String>(DOCK_SLOTS)
    private var index: Map<String, AppEntry> = emptyMap()
    private var allApps: List<AppEntry> = emptyList()

    private lateinit var io: ThreadPoolExecutor   // app scan, icons, wallpaper decode, save
    private lateinit var net: ThreadPoolExecutor  // wallpaper downloads
    private lateinit var prefs: SharedPreferences
    private lateinit var content: View
    private lateinit var wallpaper: ImageView
    private lateinit var dateView: TextView
    private lateinit var slideshowLabel: TextView
    private lateinit var grid: GridView
    private lateinit var dock: LinearLayout
    private lateinit var icons: IconLoader
    private val adapter = AppAdapter()
    private var dialog: Dialog? = null
    private var backCallback: Any? = null // OnBackInvokedCallback on API 33+

    private var started = false
    private var resumed = false
    private var uiHidden = false
    private var rotating = false
    private var slideshow = false
    private var wallpaperFailed = false
    private var scanned = false
    private var drawerOpen = false
    private var lastDock = 0
    private var shownStamp = -1L
    private var iconW = 0
    private var iconH = 0

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_launcher)

        prefs = getSharedPreferences(Prefs.FILE, MODE_PRIVATE)
        for (i in 0 until DOCK_SLOTS) dockPkgs[i] = prefs.getString(Prefs.SLOT_PREFIX + i, null)

        // Six equal columns across the content width, 16:9 tiles inside 8 dp padding.
        val dm = resources.displayMetrics
        val screenW = maxOf(dm.widthPixels, dm.heightPixels)
        val cellW = (screenW - (80 * dm.density).roundToInt()) / COLUMNS
        iconW = cellW - (16 * dm.density).roundToInt()
        iconH = iconW * 9 / 16

        io = newWorker("atv-io")
        net = newWorker("atv-net")

        content = findViewById(R.id.content)
        wallpaper = findViewById(R.id.wallpaper)
        dateView = findViewById(R.id.date)
        slideshowLabel = findViewById(R.id.slideshow_label)
        grid = findViewById(R.id.grid)
        dock = findViewById(R.id.dock)
        icons = IconLoader(this, io, ui, { refreshIcons() }, iconW, iconH)

        buildDock()

        grid.setOnItemClickListener { _, _, pos, _ -> open(adapter.getItem(pos).launchIntent()) }
        grid.setOnItemLongClickListener { _, _, pos, _ ->
            showGridMenu(adapter.getItem(pos))
            true
        }
        // Settings icon: straight to the system settings. Long press: saves the picture to Downloads.
        val settingsBtn = findViewById<View>(R.id.settings)
        settingsBtn.setOnClickListener {
            open(
                Intent(Settings.ACTION_SETTINGS),
                Intent().setClassName("com.android.tv.settings", "com.android.tv.settings.MainSettings"),
                Intent(Settings.ACTION_APPLICATION_SETTINGS),
            )
        }
        settingsBtn.setOnLongClickListener {
            saveWallpaper()
            true
        }
        // Short press: next picture. Long press: start / stop the 60 s slideshow.
        val next = findViewById<View>(R.id.next_wallpaper)
        next.setOnClickListener { nextWallpaper() }
        next.setOnLongClickListener {
            toggleSlideshow()
            true
        }

        // Android 16+ ignores the old onBackPressed()/KEYCODE_BACK path, so Back is registered
        // with the predictive-back dispatcher. The callback always consumes Back: a launcher
        // must never exit.
        if (Build.VERSION.SDK_INT >= 33) {
            val cb = OnBackInvokedCallback { handleBack() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                cb,
            )
            backCallback = cb
        }

        val pkg = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        register(packageReceiver, pkg)

        scan()

        // First launch only: offer to become the default home screen.
        if (!prefs.getBoolean(Prefs.HOME_ASKED, false)) {
            prefs.edit().putBoolean(Prefs.HOME_ASKED, true).apply()
            if (!HomeRole.isDefault(this)) ui.post { askDefaultHome() }
        }
    }

    private fun askDefaultHome() {
        if (isFinishing || isDestroyed) return
        dialog = AlertDialog.Builder(this, R.style.Theme_ATVBasic_Dialog)
            .setTitle(R.string.default_home_title)
            .setMessage(R.string.default_home_message)
            .setPositiveButton(R.string.default_home_yes) { _, _ -> HomeRole.request(this) }
            .setNegativeButton(R.string.default_home_no, null)
            .create()
        dialog?.show()
    }

    override fun onStart() {
        super.onStart()
        started = true
        val date = IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        register(dateReceiver, date)
        updateDate()
        bindDock()
        slots[lastDock].requestFocus()
    }

    override fun onResume() {
        super.onResume()
        val stamp = WallpaperStore.stamp(this)
        if (stamp != shownStamp) loadWallpaper(stamp)
        resumed = true
        slideshow = prefs.getBoolean(Prefs.SLIDESHOW, false)
        scheduleRotate()
        showUi()
    }

    override fun onPause() {
        resumed = false
        ui.removeCallbacks(rotateTask)
        ui.removeCallbacks(hideUiTask)
        ui.removeCallbacks(hideLabelTask)
        slideshowLabel.animate().cancel()
        slideshowLabel.visibility = View.GONE
        super.onPause()
    }

    override fun onStop() {
        // Hidden: release the wallpaper, icons and grid views.
        started = false
        unregisterReceiver(dateReceiver)
        dismissDialog()
        if (drawerOpen) closeDrawer(false)
        icons.clear()
        for (s in slots) holder(s).release()
        wallpaper.setImageDrawable(null)
        shownStamp = -1L
        super.onStop()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        unregisterReceiver(packageReceiver)
        if (Build.VERSION.SDK_INT >= 33) {
            (backCallback as? OnBackInvokedCallback)?.let {
                onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it)
            }
        }
        backCallback = null
        io.shutdownNow()
        net.shutdownNow()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home pressed again: back to the dock.
        lastDock = 0
        if (drawerOpen) closeDrawer(false)
        showUi()
        slots[0].requestFocus()
    }

    /** Android 8-12 path; on Android 13+ the registered callback handles Back instead. */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        handleBack()
    }

    private fun handleBack() {
        if (drawerOpen) closeDrawer(true)
    }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (uiHidden) {
            if (e.action == MotionEvent.ACTION_DOWN) showUi()
            return true // the touch that wakes the UI is not passed on
        }
        resetIdle()
        return super.dispatchTouchEvent(e)
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (uiHidden) {
            if (e.action == KeyEvent.ACTION_DOWN) showUi()
            return true // the key that wakes the UI is not passed on
        }
        resetIdle()
        if (e.action == KeyEvent.ACTION_DOWN) {
            val f = currentFocus
            when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN ->
                    if (!drawerOpen && slotIndex(f) >= 0) {
                        openDrawer()
                        return true
                    }
                KeyEvent.KEYCODE_DPAD_UP ->
                    if (drawerOpen && f === grid && grid.selectedItemPosition < COLUMNS) {
                        closeDrawer(true)
                        return true
                    }
            }
        }
        return super.dispatchKeyEvent(e)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            val f = currentFocus
            if (f === grid) {
                val pos = grid.selectedItemPosition
                if (pos >= 0 && pos < adapter.count) {
                    showGridMenu(adapter.getItem(pos))
                    return true
                }
            }
            val idx = slotIndex(f)
            if (idx >= 0) {
                showSlotMenu(idx)
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_STORAGE && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            saveWallpaper()
        }
    }

    // ------------------------------------------------------------------ helpers

    /** One short-lived, low-priority worker thread (exits after 5 s idle). */
    private fun newWorker(name: String): ThreadPoolExecutor {
        val factory = ThreadFactory { r ->
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                r.run()
            }, name)
        }
        val pool = ThreadPoolExecutor(1, 1, 5L, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>(), factory)
        pool.allowCoreThreadTimeOut(true)
        return pool
    }

    /** Runs [task] on [pool]; a failing task is logged instead of crashing the launcher. */
    private fun submit(pool: ThreadPoolExecutor, task: () -> Unit) {
        try {
            pool.execute {
                try {
                    task()
                } catch (ex: Exception) {
                    Log.w(TAG, "background task failed", ex)
                }
            }
        } catch (ex: RejectedExecutionException) {
            // the activity is shutting down
        }
    }

    private fun register(r: BroadcastReceiver, f: IntentFilter) {
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(r, f)
        }
    }

    private fun holder(v: View): Holder = v.tag as Holder

    // ------------------------------------------------------------------ data

    private fun scan() {
        val pm = packageManager
        val self = packageName
        submit(io) {
            val apps = AppEntry.query(pm, self)
            ui.post { onScanned(apps) }
        }
    }

    private fun onScanned(apps: List<AppEntry>) {
        if (isDestroyed) return
        index = apps.associateBy { it.pkg }
        scanned = true
        allApps = apps
        updateGrid()
        bindDock()
    }

    private fun loadWallpaper(stamp: Long) {
        shownStamp = stamp
        if (stamp == 0L) {
            wallpaper.setImageDrawable(null)
            return
        }
        submit(io) {
            val b = WallpaperStore.load(this)
            ui.post {
                if (!isDestroyed && shownStamp == stamp) wallpaper.setImageBitmap(b)
            }
        }
    }

    private fun updateDate() {
        dateView.text = dateFormat.format(LocalDate.now())
    }

    // ------------------------------------------------------------------ idle hiding

    private fun resetIdle() {
        ui.removeCallbacks(hideUiTask)
        if (resumed) ui.postDelayed(hideUiTask, IDLE_HIDE_MS)
    }

    private fun hideUi() {
        if (!resumed || uiHidden) return
        if (dialog?.isShowing == true) { // a menu is open: not idle
            resetIdle()
            return
        }
        if (drawerOpen) closeDrawer(false)
        uiHidden = true
        content.visibility = View.INVISIBLE
    }

    private fun showUi() {
        val wasHidden = uiHidden
        uiHidden = false
        content.visibility = View.VISIBLE
        if (wasHidden) slots[lastDock].requestFocus()
        resetIdle()
    }

    // ------------------------------------------------------------------ wallpaper rotation

    /** Arms the slideshow timer; also fetches the very first picture when there is none yet. */
    private fun scheduleRotate() {
        ui.removeCallbacks(rotateTask)
        if (!resumed || rotating) return
        if (WallpaperStore.stamp(this) == 0L) {
            // No picture yet (first launch): fetch one, retry slowly when offline.
            ui.postDelayed(rotateTask, if (wallpaperFailed) RETRY_MS else FIRST_FETCH_DELAY_MS)
        } else if (slideshow) {
            ui.postDelayed(rotateTask, ROTATE_INTERVAL_MS)
        }
    }

    /** Timer tick: downloads a new picture (slideshow or first picture). */
    private fun rotateWallpaper() {
        if (!resumed || rotating) return
        if (!slideshow && WallpaperStore.stamp(this) != 0L) return
        fetchWallpaper(false)
    }

    /** Short press on the picture button: next picture right away. */
    private fun nextWallpaper() {
        if (rotating) return
        fetchWallpaper(true)
    }

    /** Long press on the picture button: starts the 60 s slideshow, or stops it when running. */
    private fun toggleSlideshow() {
        slideshow = !slideshow
        prefs.edit().putBoolean(Prefs.SLIDESHOW, slideshow).apply()
        showLabel(if (slideshow) R.string.label_slideshow_on else R.string.label_slideshow_off)
        scheduleRotate()
    }

    /** Downloads one picture on a low-priority thread, shows it, then re-arms the rotation timer. */
    private fun fetchWallpaper(manual: Boolean) {
        rotating = true
        ui.removeCallbacks(rotateTask)
        val app = applicationContext
        submit(net) {
            var ok = false
            try {
                ok = WallpaperSource.fetchAndSet(app)
            } catch (ex: Exception) {
                // try again next round
            }
            val result = ok
            ui.post { onWallpaperFetched(result, manual) }
        }
    }

    private fun onWallpaperFetched(ok: Boolean, manual: Boolean) {
        rotating = false
        wallpaperFailed = !ok
        if (isDestroyed) return
        if (ok && resumed) {
            loadWallpaper(WallpaperStore.stamp(this))
        } else if (!ok && manual) {
            Toast.makeText(this, R.string.toast_wallpaper_failed, Toast.LENGTH_SHORT).show()
        }
        scheduleRotate()
    }

    /** Saves the current wallpaper to Downloads (Android 8/9 asks for storage permission once). */
    private fun saveWallpaper() {
        if (Build.VERSION.SDK_INT < 29 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE)
            return
        }
        val app = applicationContext
        submit(io) {
            val ok = WallpaperStore.saveToDownloads(app)
            ui.post {
                Toast.makeText(
                    app,
                    if (ok) R.string.toast_saved else R.string.toast_save_failed,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun showLabel(textRes: Int) {
        ui.removeCallbacks(hideLabelTask)
        slideshowLabel.animate().cancel()
        slideshowLabel.setText(textRes)
        slideshowLabel.alpha = 1f
        slideshowLabel.visibility = View.VISIBLE
        ui.postDelayed(hideLabelTask, LABEL_SHOW_MS)
    }

    private fun hideLabel() {
        slideshowLabel.animate().alpha(0f).setDuration(400).withEndAction {
            if (slideshowLabel.alpha == 0f) slideshowLabel.visibility = View.GONE
        }.start()
    }

    // ------------------------------------------------------------------ drawer

    private fun openDrawer() {
        grid.adapter = adapter
        grid.visibility = View.VISIBLE
        grid.setSelection(0)
        grid.requestFocus()
        drawerOpen = true
    }

    private fun closeDrawer(refocus: Boolean) {
        grid.adapter = null // frees the grid's views
        grid.visibility = View.GONE
        drawerOpen = false
        if (refocus) slots[lastDock].requestFocus()
    }

    // ------------------------------------------------------------------ dock

    private fun buildDock() {
        for (i in 0 until DOCK_SLOTS) {
            val v = layoutInflater.inflate(R.layout.item_app, dock, false)
            v.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            v.tag = Holder(v, iconW, iconH)
            v.isFocusable = true
            v.isClickable = true
            v.setBackgroundResource(R.drawable.slot_bg)
            v.setOnClickListener { onSlotClick(i) }
            v.setOnLongClickListener {
                showSlotMenu(i)
                true
            }
            v.setOnFocusChangeListener { _, has -> if (has) lastDock = i }
            dock.addView(v)
            slots.add(v)
        }
    }

    private fun slotIndex(v: View?): Int = slots.indexOfFirst { it === v }

    private fun slotEntry(idx: Int): AppEntry? = dockPkgs[idx]?.let { index[it] }

    /** The full grid shows every app except the ones pinned to the dock. */
    private fun updateGrid() {
        val pinned = dockPkgs.filterNotNull().toSet()
        adapter.setApps(allApps.filter { it.pkg !in pinned })
    }

    private fun bindDock() {
        for (i in 0 until DOCK_SLOTS) {
            val h = holder(slots[i])
            val e = slotEntry(i)
            if (e == null) {
                h.pkg = null
                val empty = dockPkgs[i] == null || scanned
                h.label.text = if (empty) getString(R.string.add_app) else ""
                h.bind(null)
            } else {
                h.pkg = e.pkg
                h.label.text = e.label
                val b = icons.peek(e.pkg)
                if (b == null && started) icons.request(e) // no icon work while hidden
                h.bind(b)
            }
        }
    }

    /** An icon finished loading: update the visible grid cells and the dock. */
    private fun refreshIcons() {
        adapter.refreshVisible()
        for (s in slots) {
            val h = holder(s)
            val p = h.pkg
            if (p != null) h.bind(icons.peek(p))
        }
    }

    private fun onSlotClick(idx: Int) {
        val e = slotEntry(idx)
        if (e != null) open(e.launchIntent()) else pickForSlot(idx)
    }

    private fun showSlotMenu(idx: Int) {
        val e = slotEntry(idx)
        if (e == null) {
            pickForSlot(idx)
            return
        }
        val labels = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        labels.add(getString(R.string.menu_change))
        acts.add { pickForSlot(idx) }
        labels.add(getString(R.string.menu_remove_dock))
        acts.add { setSlot(idx, null) }
        labels.add(getString(R.string.menu_app_info))
        acts.add { open(appInfoIntent(e.pkg)) }
        choose(e.label, labels, acts)
    }

    private fun pickForSlot(idx: Int) {
        val apps = allApps
        if (apps.isEmpty()) return
        val names = Array(apps.size) { apps[it].label }
        dismissDialog()
        dialog = AlertDialog.Builder(this, R.style.Theme_ATVBasic_Dialog)
            .setItems(names) { _, which -> setSlot(idx, apps[which].pkg) }
            .create()
        dialog?.show()
    }

    private fun setSlot(idx: Int, pkg: String?) {
        if (pkg != null) {
            for (i in 0 until DOCK_SLOTS) {
                if (pkg == dockPkgs[i]) dockPkgs[i] = null // an app lives in one slot only
            }
        }
        dockPkgs[idx] = pkg
        val ed = prefs.edit()
        for (i in 0 until DOCK_SLOTS) {
            val p = dockPkgs[i]
            if (p == null) ed.remove(Prefs.SLOT_PREFIX + i) else ed.putString(Prefs.SLOT_PREFIX + i, p)
        }
        ed.apply()
        updateGrid()
        bindDock()
    }

    private fun addToDock(e: AppEntry) {
        for (i in 0 until DOCK_SLOTS) {
            if (e.pkg == dockPkgs[i]) return // already there
        }
        for (i in 0 until DOCK_SLOTS) {
            if (slotEntry(i) == null) {
                setSlot(i, e.pkg)
                Toast.makeText(this, R.string.toast_added_dock, Toast.LENGTH_SHORT).show()
                return
            }
        }
        // Dock is full: ask which app to replace.
        val names = Array(DOCK_SLOTS) { slotEntry(it)?.label ?: "" }
        dismissDialog()
        dialog = AlertDialog.Builder(this, R.style.Theme_ATVBasic_Dialog)
            .setTitle(R.string.replace_which)
            .setItems(names) { _, which -> setSlot(which, e.pkg) }
            .create()
        dialog?.show()
    }

    // ------------------------------------------------------------------ menus / intents

    private fun showGridMenu(e: AppEntry) {
        val labels = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        labels.add(getString(R.string.menu_add_dock))
        acts.add { addToDock(e) }
        labels.add(getString(R.string.menu_app_info))
        acts.add { open(appInfoIntent(e.pkg)) }
        if (!e.system) {
            labels.add(getString(R.string.menu_uninstall))
            acts.add { open(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", e.pkg, null))) }
        }
        choose(e.label, labels, acts)
    }

    private fun choose(title: String, labels: List<String>, actions: List<() -> Unit>) {
        dismissDialog()
        dialog = AlertDialog.Builder(this, R.style.Theme_ATVBasic_Dialog)
            .setTitle(title)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .create()
        dialog?.show()
    }

    private fun dismissDialog() {
        dialog?.dismiss()
        dialog = null
    }

    private fun appInfoIntent(pkg: String): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))

    /** Starts the first intent that works; shows a toast if none does. */
    private fun open(vararg candidates: Intent) {
        for (i in candidates) {
            try {
                startActivity(i)
                return
            } catch (ex: RuntimeException) {
                // try the next candidate
            }
        }
        Toast.makeText(this, R.string.toast_unavailable, Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------ views

    /** View holder shared by grid cells and dock slots. */
    private class Holder(v: View, w: Int, h: Int) {
        val icon: ImageView = v.findViewById(R.id.icon)
        val label: TextView = v.findViewById(R.id.label)
        var pkg: String? = null
        private var bound: Bitmap? = null

        init {
            icon.layoutParams.width = w
            icon.layoutParams.height = h
            label.layoutParams.width = w
        }

        fun bind(b: Bitmap?) {
            if (b !== bound) {
                bound = b
                icon.setImageBitmap(b)
            }
        }

        fun release() {
            bound = null
            icon.setImageDrawable(null)
        }
    }

    private inner class AppAdapter : BaseAdapter() {
        private var apps: List<AppEntry> = emptyList()

        fun setApps(list: List<AppEntry>) {
            apps = list
            notifyDataSetChanged()
        }

        fun refreshVisible() {
            for (i in 0 until grid.childCount) {
                val h = grid.getChildAt(i).tag as? Holder ?: continue
                val p = h.pkg ?: continue
                h.bind(icons.peek(p))
            }
        }

        override fun getCount(): Int = apps.size

        override fun getItem(position: Int): AppEntry = apps[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v: View
            val h: Holder
            if (convertView == null) {
                v = layoutInflater.inflate(R.layout.item_app, parent, false)
                h = Holder(v, iconW, iconH)
                v.tag = h
            } else {
                v = convertView
                h = convertView.tag as Holder
            }
            val e = apps[position]
            h.pkg = e.pkg
            h.label.text = e.label
            val b = icons.peek(e.pkg)
            if (b == null) icons.request(e)
            h.bind(b)
            return v
        }
    }
}
