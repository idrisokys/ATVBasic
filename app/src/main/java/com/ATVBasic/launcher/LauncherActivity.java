package com.ATVBasic.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * ATV Basic. Home = wallpaper, clock + date, Settings button and a dock of 6 pinned apps.
 * Press Down on the dock to open the full app grid; Up on its first row (or Back) closes it.
 */
public final class LauncherActivity extends Activity {

    private static final int COLUMNS = 6;
    private static final int DOCK_SLOTS = 6;
    private static final long RELOAD_DELAY_MS = 500;
    private static final long ROTATE_INTERVAL_MS = 60_000;
    private static final long IDLE_HIDE_MS = 20_000;
    private static final long LABEL_SHOW_MS = 2_500;
    private static final long RETRY_MS = 30_000;
    private static final String KEY_SLIDESHOW = "slideshow";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable reload = this::scan;
    private final Runnable rotate = this::rotateWallpaper;
    private final Runnable hideUi = this::hideUi;
    private final Runnable hideLabel = this::hideLabel;
    private View content;
    private boolean uiHidden;
    private boolean resumed;
    private boolean rotating;
    private boolean slideshow;
    private boolean wallpaperFailed;
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            ui.removeCallbacks(reload); // coalesce bursts of install/remove events
            ui.postDelayed(reload, RELOAD_DELAY_MS);
        }
    };
    private final BroadcastReceiver dateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            updateDate();
        }
    };
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("d MMMM EEEE", new Locale("tr", "TR"));

    private final View[] slots = new View[DOCK_SLOTS];
    private final String[] dockPkgs = new String[DOCK_SLOTS];
    private HashMap<String, AppEntry> index = new HashMap<>();

    private ThreadPoolExecutor io;
    private SharedPreferences prefs;
    private ImageView wallpaper;
    private TextView dateView;
    private TextView slideshowLabel;
    private GridView grid;
    private LinearLayout dock;
    private AppAdapter adapter;
    private IconLoader icons;
    private Dialog dialog;

    private List<AppEntry> allApps = Collections.emptyList();
    private boolean scanned;
    private boolean drawerOpen;
    private int lastDock;
    private long shownStamp = -1;
    private int iconW;
    private int iconH;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_launcher);

        prefs = getSharedPreferences("dock", MODE_PRIVATE);
        for (int i = 0; i < DOCK_SLOTS; i++) dockPkgs[i] = prefs.getString("slot" + i, null);

        // Six equal columns across the content width, 16:9 tiles inside 8 dp padding.
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int screenW = Math.max(dm.widthPixels, dm.heightPixels);
        int cellW = (screenW - Math.round(80 * dm.density)) / COLUMNS;
        iconW = cellW - Math.round(16 * dm.density);
        iconH = iconW * 9 / 16;

        // One short-lived, low-priority worker thread (exits after 5 s idle).
        io = new ThreadPoolExecutor(1, 1, 5, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
                r -> new Thread(() -> {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                    r.run();
                }, "atv-io"));
        io.allowCoreThreadTimeOut(true);

        content = findViewById(R.id.content);
        wallpaper = findViewById(R.id.wallpaper);
        dateView = findViewById(R.id.date);
        slideshowLabel = findViewById(R.id.slideshow_label);
        grid = findViewById(R.id.grid);
        dock = findViewById(R.id.dock);
        adapter = new AppAdapter();
        icons = new IconLoader(this, io, ui, this::refreshIcons, iconW, iconH);

        buildDock();

        grid.setOnItemClickListener((p, v, pos, id) -> open(adapter.getItem(pos).launchIntent()));
        grid.setOnItemLongClickListener((p, v, pos, id) -> {
            showGridMenu(adapter.getItem(pos));
            return true;
        });
        // Settings icon: straight to the system settings.
        findViewById(R.id.settings).setOnClickListener(v -> open(new Intent(Settings.ACTION_SETTINGS)));
        // Short press: next picture. Long press: start / stop the 60 s slideshow.
        View next = findViewById(R.id.next_wallpaper);
        next.setOnClickListener(v -> nextWallpaper());
        next.setOnLongClickListener(v -> {
            toggleSlideshow();
            return true;
        });

        IntentFilter pkg = new IntentFilter();
        pkg.addAction(Intent.ACTION_PACKAGE_ADDED);
        pkg.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pkg.addDataScheme("package");
        register(packageReceiver, pkg);

        scan();

        // First launch only: offer to become the default home screen.
        if (!prefs.getBoolean("home_asked", false)) {
            prefs.edit().putBoolean("home_asked", true).apply();
            if (!HomeRole.isDefault(this)) ui.post(this::askDefaultHome);
        }
    }

    private void askDefaultHome() {
        if (isFinishing() || isDestroyed()) return;
        dialog = new AlertDialog.Builder(this, R.style.Theme_ATVBasic_Dialog)
                .setTitle(R.string.default_home_title)
                .setMessage(R.string.default_home_message)
                .setPositiveButton(R.string.default_home_yes, (d, w) -> HomeRole.request(this))
                .setNegativeButton(R.string.default_home_no, null)
                .create();
        dialog.show();
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter date = new IntentFilter();
        date.addAction(Intent.ACTION_DATE_CHANGED);
        date.addAction(Intent.ACTION_TIME_CHANGED);
        date.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        register(dateReceiver, date);
        updateDate();
        bindDock();
        slots[lastDock].requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        long stamp = WallpaperStore.stamp(this);
        if (stamp != shownStamp) loadWallpaper(stamp);
        resumed = true;
        slideshow = prefs.getBoolean(KEY_SLIDESHOW, false);
        scheduleRotate();
        showUi();
    }

    @Override
    protected void onPause() {
        resumed = false;
        ui.removeCallbacks(rotate);
        ui.removeCallbacks(hideUi);
        ui.removeCallbacks(hideLabel);
        slideshowLabel.animate().cancel();
        slideshowLabel.setVisibility(View.GONE);
        super.onPause();
    }

    @Override
    protected void onStop() {
        // Hidden: release the wallpaper, icons and grid views.
        unregisterReceiver(dateReceiver);
        dismissDialog();
        if (drawerOpen) closeDrawer(false);
        icons.clear();
        for (View s : slots) ((Holder) s.getTag()).release();
        wallpaper.setImageDrawable(null);
        shownStamp = -1;
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        unregisterReceiver(packageReceiver);
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed again: back to the dock.
        lastDock = 0;
        if (drawerOpen) closeDrawer(false);
        showUi();
        slots[0].requestFocus();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (drawerOpen) closeDrawer(true); // otherwise a launcher never exits
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        if (uiHidden) {
            if (e.getAction() == MotionEvent.ACTION_DOWN) showUi();
            return true; // the touch that wakes the UI is not passed on
        }
        resetIdle();
        return super.dispatchTouchEvent(e);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (uiHidden) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) showUi();
            return true; // the key that wakes the UI is not passed on
        }
        resetIdle();
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            View f = getCurrentFocus();
            int key = e.getKeyCode();
            if (key == KeyEvent.KEYCODE_DPAD_DOWN && !drawerOpen && slotIndex(f) >= 0) {
                openDrawer();
                return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_UP && drawerOpen && f == grid
                    && grid.getSelectedItemPosition() < COLUMNS) {
                closeDrawer(true);
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            View f = getCurrentFocus();
            if (f == grid) {
                int pos = grid.getSelectedItemPosition();
                if (pos >= 0 && pos < adapter.getCount()) {
                    showGridMenu(adapter.getItem(pos));
                    return true;
                }
            }
            int idx = slotIndex(f);
            if (idx >= 0) {
                showSlotMenu(idx);
                return true;
            }
        }
        return super.onKeyUp(keyCode, event);
    }

    private void register(BroadcastReceiver r, IntentFilter f) {
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(r, f);
        }
    }

    // ------------------------------------------------------------------ data

    private void scan() {
        final PackageManager pm = getPackageManager();
        final String self = getPackageName();
        io.execute(() -> {
            final List<AppEntry> apps = AppEntry.query(pm, self);
            ui.post(() -> {
                if (isDestroyed()) return;
                HashMap<String, AppEntry> map = new HashMap<>();
                for (AppEntry a : apps) map.put(a.pkg, a);
                index = map;
                scanned = true;
                allApps = apps;
                updateGrid();
                bindDock();
            });
        });
    }

    private void loadWallpaper(final long stamp) {
        shownStamp = stamp;
        if (stamp == 0) {
            wallpaper.setImageDrawable(null);
            return;
        }
        io.execute(() -> {
            final Bitmap b = WallpaperStore.load(this);
            ui.post(() -> {
                if (!isDestroyed() && shownStamp == stamp) wallpaper.setImageBitmap(b);
            });
        });
    }

    // ------------------------------------------------------------------ idle hiding

    private void resetIdle() {
        ui.removeCallbacks(hideUi);
        if (resumed) ui.postDelayed(hideUi, IDLE_HIDE_MS);
    }

    private void hideUi() {
        if (!resumed || uiHidden) return;
        if (dialog != null && dialog.isShowing()) { // a menu is open: not idle
            resetIdle();
            return;
        }
        if (drawerOpen) closeDrawer(false);
        uiHidden = true;
        content.setVisibility(View.INVISIBLE);
    }

    private void showUi() {
        boolean wasHidden = uiHidden;
        uiHidden = false;
        content.setVisibility(View.VISIBLE);
        if (wasHidden) slots[lastDock].requestFocus();
        resetIdle();
    }

    /** Arms the slideshow timer; also fetches the very first picture when there is none yet. */
    private void scheduleRotate() {
        ui.removeCallbacks(rotate);
        if (!resumed || rotating) return;
        if (WallpaperStore.stamp(this) == 0) {
            // No picture yet (first launch): fetch one, retry slowly when offline.
            ui.postDelayed(rotate, wallpaperFailed ? RETRY_MS : 2_000);
        } else if (slideshow) {
            ui.postDelayed(rotate, ROTATE_INTERVAL_MS);
        }
    }

    /** Timer tick: downloads a new Wallhaven nature picture (slideshow or first picture). */
    private void rotateWallpaper() {
        if (!resumed || rotating) return;
        if (!slideshow && WallpaperStore.stamp(this) != 0) return;
        fetchWallpaper(false);
    }

    /** Short press on the picture button: next picture right away. */
    private void nextWallpaper() {
        if (rotating) return;
        fetchWallpaper(true);
    }

    /** Long press on the picture button: starts the 60 s slideshow, or stops it when running. */
    private void toggleSlideshow() {
        slideshow = !slideshow;
        prefs.edit().putBoolean(KEY_SLIDESHOW, slideshow).apply();
        showLabel(slideshow ? R.string.label_slideshow_on : R.string.label_slideshow_off);
        scheduleRotate();
    }

    private void showLabel(int textRes) {
        ui.removeCallbacks(hideLabel);
        slideshowLabel.animate().cancel();
        slideshowLabel.setText(textRes);
        slideshowLabel.setAlpha(1f);
        slideshowLabel.setVisibility(View.VISIBLE);
        ui.postDelayed(hideLabel, LABEL_SHOW_MS);
    }

    private void hideLabel() {
        slideshowLabel.animate().alpha(0f).setDuration(400)
                .withEndAction(() -> {
                    if (slideshowLabel.getAlpha() == 0f) slideshowLabel.setVisibility(View.GONE);
                }).start();
    }

    /** Downloads one picture on a low-priority thread, shows it, then re-arms the rotation timer. */
    private void fetchWallpaper(final boolean manual) {
        rotating = true;
        ui.removeCallbacks(rotate);
        final Context app = getApplicationContext();
        Thread t = new Thread(() -> {
            boolean ok = false;
            try {
                ok = WallpaperSource.fetchAndSet(app);
            } catch (RuntimeException ignored) {
                // try again next round
            }
            final boolean result = ok;
            ui.post(() -> {
                rotating = false;
                wallpaperFailed = !result;
                if (isDestroyed()) return;
                if (result && resumed) loadWallpaper(WallpaperStore.stamp(this));
                else if (!result && manual) {
                    Toast.makeText(this, R.string.toast_wallpaper_failed, Toast.LENGTH_SHORT).show();
                }
                scheduleRotate();
            });
        }, "atv-wallpaper");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private void updateDate() {
        dateFormat.setTimeZone(TimeZone.getDefault());
        dateView.setText(dateFormat.format(new Date()));
    }

    // ------------------------------------------------------------------ drawer

    private void openDrawer() {
        grid.setAdapter(adapter);
        grid.setVisibility(View.VISIBLE);
        grid.setSelection(0);
        grid.requestFocus();
        drawerOpen = true;
    }

    private void closeDrawer(boolean refocus) {
        grid.setAdapter(null); // frees the grid's views
        grid.setVisibility(View.GONE);
        drawerOpen = false;
        if (refocus) slots[lastDock].requestFocus();
    }

    // ------------------------------------------------------------------ dock

    private void buildDock() {
        for (int i = 0; i < DOCK_SLOTS; i++) {
            final int idx = i;
            View v = getLayoutInflater().inflate(R.layout.item_app, dock, false);
            v.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            v.setTag(new Holder(v, iconW, iconH));
            v.setFocusable(true);
            v.setClickable(true);
            v.setBackgroundResource(R.drawable.slot_bg);
            v.setOnClickListener(x -> onSlotClick(idx));
            v.setOnLongClickListener(x -> {
                showSlotMenu(idx);
                return true;
            });
            v.setOnFocusChangeListener((x, has) -> {
                if (has) lastDock = idx;
            });
            dock.addView(v);
            slots[i] = v;
        }
    }

    private int slotIndex(View v) {
        for (int i = 0; i < DOCK_SLOTS; i++) if (slots[i] == v) return i;
        return -1;
    }

    private AppEntry slotEntry(int idx) {
        return dockPkgs[idx] == null ? null : index.get(dockPkgs[idx]);
    }

    /** The full grid shows every app except the ones pinned to the dock. */
    private void updateGrid() {
        ArrayList<AppEntry> shown = new ArrayList<>(allApps.size());
        for (AppEntry a : allApps) {
            boolean pinned = false;
            for (String p : dockPkgs) {
                if (a.pkg.equals(p)) {
                    pinned = true;
                    break;
                }
            }
            if (!pinned) shown.add(a);
        }
        adapter.setApps(shown);
    }

    private void bindDock() {
        for (int i = 0; i < DOCK_SLOTS; i++) {
            Holder h = (Holder) slots[i].getTag();
            AppEntry e = slotEntry(i);
            if (e == null) {
                h.pkg = null;
                boolean empty = dockPkgs[i] == null || scanned;
                h.label.setText(empty ? getString(R.string.add_app) : "");
                h.bind(null);
            } else {
                h.pkg = e.pkg;
                h.label.setText(e.label);
                Bitmap b = icons.peek(e.pkg);
                if (b == null) icons.request(e);
                h.bind(b);
            }
        }
    }

    /** An icon finished loading: update the visible grid cells and the dock. */
    private void refreshIcons() {
        adapter.refreshVisible();
        for (View s : slots) {
            Holder h = (Holder) s.getTag();
            if (h.pkg != null) h.bind(icons.peek(h.pkg));
        }
    }

    private void onSlotClick(int idx) {
        AppEntry e = slotEntry(idx);
        if (e != null) {
            open(e.launchIntent());
        } else {
            pickForSlot(idx);
        }
    }

    private void showSlotMenu(final int idx) {
        final AppEntry e = slotEntry(idx);
        if (e == null) {
            pickForSlot(idx);
            return;
        }
        ArrayList<String> labels = new ArrayList<>();
        ArrayList<Runnable> acts = new ArrayList<>();
        labels.add(getString(R.string.menu_change));
        acts.add(() -> pickForSlot(idx));
        labels.add(getString(R.string.menu_remove_dock));
        acts.add(() -> setSlot(idx, null));
        labels.add(getString(R.string.menu_app_info));
        acts.add(() -> open(appInfoIntent(e.pkg)));
        choose(e.label, labels, acts);
    }

    private void pickForSlot(final int idx) {
        final List<AppEntry> apps = allApps;
        if (apps.isEmpty()) return;
        String[] names = new String[apps.size()];
        for (int i = 0; i < names.length; i++) names[i] = apps.get(i).label;
        dismissDialog();
        dialog = new AlertDialog.Builder(this, R.style.Theme_ATVBasic_Dialog)
                .setItems(names, (d, which) -> setSlot(idx, apps.get(which).pkg))
                .create();
        dialog.show();
    }

    private void setSlot(int idx, String pkg) {
        if (pkg != null) {
            for (int i = 0; i < DOCK_SLOTS; i++) {
                if (pkg.equals(dockPkgs[i])) dockPkgs[i] = null; // an app lives in one slot only
            }
        }
        dockPkgs[idx] = pkg;
        SharedPreferences.Editor ed = prefs.edit();
        for (int i = 0; i < DOCK_SLOTS; i++) {
            if (dockPkgs[i] == null) {
                ed.remove("slot" + i);
            } else {
                ed.putString("slot" + i, dockPkgs[i]);
            }
        }
        ed.apply();
        updateGrid();
        bindDock();
    }

    private void addToDock(final AppEntry e) {
        for (int i = 0; i < DOCK_SLOTS; i++) {
            if (e.pkg.equals(dockPkgs[i])) return; // already there
        }
        for (int i = 0; i < DOCK_SLOTS; i++) {
            if (slotEntry(i) == null) {
                setSlot(i, e.pkg);
                Toast.makeText(this, R.string.toast_added_dock, Toast.LENGTH_SHORT).show();
                return;
            }
        }
        // Dock is full: ask which app to replace.
        String[] names = new String[DOCK_SLOTS];
        for (int i = 0; i < DOCK_SLOTS; i++) names[i] = slotEntry(i).label;
        dismissDialog();
        dialog = new AlertDialog.Builder(this, R.style.Theme_ATVBasic_Dialog)
                .setTitle(R.string.replace_which)
                .setItems(names, (d, which) -> setSlot(which, e.pkg))
                .create();
        dialog.show();
    }

    // ------------------------------------------------------------------ menus / intents

    private void showGridMenu(final AppEntry e) {
        ArrayList<String> labels = new ArrayList<>();
        ArrayList<Runnable> acts = new ArrayList<>();
        labels.add(getString(R.string.menu_add_dock));
        acts.add(() -> addToDock(e));
        labels.add(getString(R.string.menu_app_info));
        acts.add(() -> open(appInfoIntent(e.pkg)));
        if (!e.system) {
            labels.add(getString(R.string.menu_uninstall));
            acts.add(() -> open(new Intent(Intent.ACTION_DELETE, Uri.fromParts("package", e.pkg, null))));
        }
        choose(e.label, labels, acts);
    }

    private void choose(String title, List<String> labels, final List<Runnable> actions) {
        dismissDialog();
        dialog = new AlertDialog.Builder(this, R.style.Theme_ATVBasic_Dialog)
                .setTitle(title)
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .create();
        dialog.show();
    }

    private void dismissDialog() {
        if (dialog != null) {
            dialog.dismiss();
            dialog = null;
        }
    }

    private static Intent appInfoIntent(String pkg) {
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", pkg, null));
    }

    /** Starts the first intent that works; shows a toast if none does. */
    private void open(Intent... candidates) {
        for (Intent i : candidates) {
            try {
                startActivity(i);
                return;
            } catch (RuntimeException ignored) {
                // Try the next candidate.
            }
        }
        Toast.makeText(this, R.string.toast_unavailable, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ grid adapter

    /** View holder shared by grid cells and dock slots. */
    private static final class Holder {
        final ImageView icon;
        final TextView label;
        String pkg;
        Bitmap bound;

        Holder(View v, int w, int h) {
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);
            icon.getLayoutParams().width = w;
            icon.getLayoutParams().height = h;
            label.getLayoutParams().width = w;
        }

        void bind(Bitmap b) {
            if (b != bound) {
                bound = b;
                icon.setImageBitmap(b);
            }
        }

        void release() {
            bound = null;
            icon.setImageDrawable(null);
        }
    }

    private final class AppAdapter extends BaseAdapter {
        List<AppEntry> apps = Collections.emptyList();

        void setApps(List<AppEntry> list) {
            apps = list;
            notifyDataSetChanged();
        }

        void refreshVisible() {
            for (int i = 0; i < grid.getChildCount(); i++) {
                Holder h = (Holder) grid.getChildAt(i).getTag();
                if (h != null && h.pkg != null) h.bind(icons.peek(h.pkg));
            }
        }

        @Override public int getCount() { return apps.size(); }
        @Override public AppEntry getItem(int position) { return apps.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Holder h;
            View v = convertView;
            if (v == null) {
                v = getLayoutInflater().inflate(R.layout.item_app, parent, false);
                h = new Holder(v, iconW, iconH);
                v.setTag(h);
            } else {
                h = (Holder) v.getTag();
            }
            AppEntry e = apps.get(position);
            h.pkg = e.pkg;
            h.label.setText(e.label);
            Bitmap b = icons.peek(e.pkg);
            if (b == null) icons.request(e);
            h.bind(b);
            return v;
        }
    }
}
