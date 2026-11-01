package com.bare.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** ATV Basic: a clock, two shortcuts and a grid of apps. Nothing runs while idle. */
public final class LauncherActivity extends Activity {

    private static final long RELOAD_DELAY_MS = 500;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable reload = this::scan;
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            ui.removeCallbacks(reload); // coalesce bursts of install/remove events
            ui.postDelayed(reload, RELOAD_DELAY_MS);
        }
    };

    private ThreadPoolExecutor io;
    private GridView grid;
    private AppAdapter adapter;
    private IconLoader icons;
    private Dialog menu;
    private int pendingPos;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_launcher);

        // One short-lived, low-priority worker thread (exits after 5 s idle).
        io = new ThreadPoolExecutor(1, 1, 5, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
                r -> new Thread(() -> {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                    r.run();
                }, "atv-io"));
        io.allowCoreThreadTimeOut(true);

        grid = findViewById(R.id.grid);
        adapter = new AppAdapter();
        icons = new IconLoader(this, io, ui, adapter::refreshVisible);

        grid.setOnItemClickListener((p, v, pos, id) -> open(adapter.getItem(pos).launchIntent()));
        grid.setOnItemLongClickListener((p, v, pos, id) -> {
            showMenu(adapter.getItem(pos));
            return true;
        });
        findViewById(R.id.network).setOnClickListener(v -> open(
                new Intent(Settings.ACTION_WIFI_SETTINGS),
                new Intent(Settings.ACTION_WIRELESS_SETTINGS)));
        findViewById(R.id.settings).setOnClickListener(v ->
                open(new Intent(Settings.ACTION_SETTINGS)));

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(packageReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(packageReceiver, filter);
        }

        scan();
    }

    @Override
    protected void onStart() {
        super.onStart();
        grid.setAdapter(adapter);
        restoreSelection();
    }

    @Override
    protected void onStop() {
        // Hidden: release icon bitmaps and views, remember where the user was.
        if (menu != null) {
            menu.dismiss();
            menu = null;
        }
        pendingPos = Math.max(0, grid.getSelectedItemPosition());
        grid.setAdapter(null);
        icons.clear();
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
        // Home pressed again: go back to the first app.
        pendingPos = 0;
        if (grid.getAdapter() != null) {
            grid.setSelection(0);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        // A launcher never exits.
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU && grid.hasFocus()) {
            int pos = grid.getSelectedItemPosition();
            if (pos >= 0 && pos < adapter.getCount()) {
                showMenu(adapter.getItem(pos));
                return true;
            }
        }
        return super.onKeyUp(keyCode, event);
    }

    private void scan() {
        final PackageManager pm = getPackageManager();
        final String self = getPackageName();
        io.execute(() -> {
            final List<AppEntry> apps = AppEntry.query(pm, self);
            ui.post(() -> {
                if (isDestroyed()) return;
                adapter.setApps(apps);
                restoreSelection();
            });
        });
    }

    private void restoreSelection() {
        int count = adapter.getCount();
        if (count == 0 || grid.getAdapter() == null) return;
        if (pendingPos >= 0) {
            grid.setSelection(Math.min(pendingPos, count - 1));
            pendingPos = -1;
        }
        grid.requestFocus();
    }

    private void showMenu(final AppEntry e) {
        String[] items = e.system
                ? new String[]{getString(R.string.menu_app_info)}
                : new String[]{getString(R.string.menu_app_info), getString(R.string.menu_uninstall)};
        final Uri uri = Uri.fromParts("package", e.pkg, null);
        menu = new AlertDialog.Builder(this)
                .setTitle(e.label)
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri));
                    } else {
                        open(new Intent(Intent.ACTION_DELETE, uri));
                    }
                })
                .create();
        menu.show();
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

    // ------------------------------------------------------------------ adapter

    private static final class Holder {
        final ImageView icon;
        final TextView label;
        String pkg;
        Bitmap bound;

        Holder(View v) {
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);
        }

        void bind(Bitmap b) {
            if (b != bound) {
                bound = b;
                icon.setImageBitmap(b);
            }
        }
    }

    private final class AppAdapter extends BaseAdapter {
        private List<AppEntry> apps = Collections.emptyList();

        void setApps(List<AppEntry> list) {
            apps = list;
            notifyDataSetChanged();
        }

        /** Called when an icon finished loading: update only the visible cells. */
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
                h = new Holder(v);
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
