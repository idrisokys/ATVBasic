package com.ATVBasic.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Floating settings window: system settings plus a stacked wallpaper list (Wallhaven, Wikimedia,
 * Picsum, Bing, rotation off, Bing picture of the day); the active row shows a dot. Choosing a
 * source starts a 60 s rotation (Bing daily = one fixed picture).
 */
public final class SettingsActivity extends Activity {

    private interface Job {
        boolean run() throws Exception;
    }

    static final String PREFS = "settings";
    static final String KEY_AUTO = "auto_source"; // 0 = off, else a WallpaperSource id
    static final String KEY_MODE = "auto_mode";   // WallpaperSource.RANDOM / POPULAR

    private static final int[] ROW_IDS = {
            R.id.row_wallhaven, R.id.row_wikimedia, R.id.row_picsum, R.id.row_bing,
            R.id.row_off, R.id.row_bing_daily
    };
    /** Source behind each row; 0 = rotation off. Same order as ROW_IDS. */
    private static final int[] ROW_SOURCES = {
            WallpaperSource.WALLHAVEN, WallpaperSource.WIKIMEDIA,
            WallpaperSource.PICSUM, WallpaperSource.BING, 0, WallpaperSource.BING_DAILY
    };

    private boolean busy;

    /** Most-liked pictures for Wallhaven, random ones for the others. */
    private static int modeFor(int source) {
        return source == WallpaperSource.WALLHAVEN ? WallpaperSource.POPULAR : WallpaperSource.RANDOM;
    }

    /** Shows a dot at the right edge of the row that is currently active. */
    private void updateDots() {
        int current = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_AUTO, 0);
        for (int i = 0; i < ROW_IDS.length; i++) {
            TextView row = (TextView) findViewById(ROW_IDS[i]);
            row.setCompoundDrawablesWithIntrinsicBounds(0, 0,
                    ROW_SOURCES[i] == current ? R.drawable.selected_dot : 0, 0);
        }
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

        for (int i = 0; i < ROW_IDS.length; i++) {
            final int source = ROW_SOURCES[i];
            findViewById(ROW_IDS[i]).setOnClickListener(v -> {
                if (source == 0) turnOff(); else choose(source);
            });
        }
        updateDots();

        findViewById(R.id.row_system).requestFocus();
    }

    /** Stops the automatic picture change; the current wallpaper stays. */
    private void turnOff() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putInt(KEY_AUTO, 0).putInt(KEY_MODE, WallpaperSource.RANDOM).apply();
        updateDots();
        Toast.makeText(this, R.string.toast_rotation_off, Toast.LENGTH_SHORT).show();
        finish();
    }

    /** Fetches one picture now and, on success, selects this source (rotating every 60 s, or fixed for Bing daily). */
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
        }, source == WallpaperSource.BING_DAILY ? R.string.toast_bing_daily : R.string.toast_auto_on);
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
