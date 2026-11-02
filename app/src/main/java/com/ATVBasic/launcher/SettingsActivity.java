package com.ATVBasic.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Floating settings window: system settings plus a wallpaper source menu (Wallhaven, Wikimedia,
 * Picsum, Bing). Choosing a source starts a 30 s rotation: Wallhaven shows its
 * most-liked pictures, the others random ones.
 */
public final class SettingsActivity extends Activity {

    private interface Job {
        boolean run() throws Exception;
    }

    static final String PREFS = "settings";
    static final String KEY_AUTO = "auto_source"; // 0 = off, else a WallpaperSource id
    static final String KEY_MODE = "auto_mode";   // WallpaperSource.RANDOM / POPULAR

    /** Menu order. */
    private static final int[] SOURCES = {
            WallpaperSource.WALLHAVEN, WallpaperSource.WIKIMEDIA,
            WallpaperSource.PICSUM, WallpaperSource.BING
    };

    private boolean busy;

    private static int sourceName(int source) {
        switch (source) {
            case WallpaperSource.WALLHAVEN: return R.string.src_wallhaven;
            case WallpaperSource.BING: return R.string.src_bing;
            case WallpaperSource.WIKIMEDIA: return R.string.src_wikimedia;
            case WallpaperSource.PICSUM: return R.string.src_picsum;
            default: return R.string.source_none;
        }
    }

    /** Most-liked pictures for Wallhaven, random ones for the others. */
    private static int modeFor(int source) {
        return source == WallpaperSource.WALLHAVEN ? WallpaperSource.POPULAR : WallpaperSource.RANDOM;
    }

    private void updateSourceRow(TextView row) {
        int src = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_AUTO, 0);
        String name = getString(sourceName(src));
        row.setText(getString(R.string.row_source, name));
    }

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        findViewById(R.id.row_system).setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (RuntimeException e) {
                Toast.makeText(this, R.string.toast_unavailable, Toast.LENGTH_SHORT).show();
            }
        });

        TextView sourceRow = (TextView) findViewById(R.id.row_source);
        updateSourceRow(sourceRow);
        sourceRow.setOnClickListener(v -> showSources());

        findViewById(R.id.row_system).requestFocus();
    }

    private void showSources() {
        String[] items = new String[SOURCES.length];
        for (int i = 0; i < items.length; i++) items[i] = getString(sourceName(SOURCES[i]));
        new AlertDialog.Builder(this, R.style.Theme_ATVBasic_Dialog)
                .setTitle(R.string.source_dialog_title)
                .setItems(items, (d, which) -> choose(SOURCES[which]))
                .show();
    }

    /** Fetches one picture now and, on success, turns on 30 s rotation for this source. */
    private void choose(final int source) {
        final int mode = modeFor(source);
        final Context app = getApplicationContext();
        runJob(() -> {
            boolean ok = WallpaperSource.fetchAndSet(app, source, mode);
            if (ok) {
                app.getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putInt(KEY_AUTO, source).putInt(KEY_MODE, mode).apply();
            }
            return ok;
        }, R.string.toast_auto_on);
    }

    /** Runs {@code job} on a background thread, reports the result with a toast, then closes. */
    private void runJob(final Job job, final int successMessage) {
        if (busy) return;
        busy = true;
        final Context app = getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        Toast.makeText(app, R.string.toast_working, Toast.LENGTH_SHORT).show();
        Thread t = new Thread(() -> {
            boolean ok = false;
            try {
                ok = job.run();
            } catch (Exception | OutOfMemoryError ignored) {
                // reported below as a failure
            }
            final boolean result = ok;
            main.post(() -> {
                Toast.makeText(app, result ? successMessage
                        : R.string.toast_wallpaper_failed, Toast.LENGTH_SHORT).show();
                busy = false;
                if (result && !isFinishing()) finish();
            });
        }, "atv-wallpaper");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }
}
