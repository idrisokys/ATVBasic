package com.ATVBasic.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Floating settings window: system settings plus a stacked wallpaper list (Wallhaven, Wikimedia,
 * Picsum, Bing, rotation off, Bing picture of the day); the active row shows a dot. Choosing a
 * source starts a 60 s rotation (Bing daily = one fixed picture).
 */
public final class SettingsActivity extends Activity {

    static final String PREFS = "settings";
    static final String KEY_AUTO = "auto_source"; // 0 = off, else a WallpaperSource id
    static final String KEY_MODE = "auto_mode";   // WallpaperSource.RANDOM / POPULAR

    static final String KEY_PER_ROW = "dock_per_row"; // favourites per row: 4..9
    static final String KEY_ROWS = "dock_rows";       // favourite rows: 1..4
    static final int DEFAULT_PER_ROW = 6;
    static final int DEFAULT_ROWS = 1;
    private static final int[] PER_ROW_VALUES = {4, 5, 6, 7, 8, 9};
    private static final int[] ROW_VALUES = {1, 2, 3, 4};

    /** First launch (nothing saved yet): Wallhaven nature, most-liked first. */
    static final int DEFAULT_SOURCE = WallpaperSource.WALLHAVEN;
    static final int DEFAULT_MODE = WallpaperSource.POPULAR;

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
        int current = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_AUTO, DEFAULT_SOURCE);
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

        setupNumberRow(R.id.row_per_row, R.string.row_per_row, R.string.title_per_row,
                KEY_PER_ROW, DEFAULT_PER_ROW, PER_ROW_VALUES);
        setupNumberRow(R.id.row_rows, R.string.row_rows, R.string.title_rows,
                KEY_ROWS, DEFAULT_ROWS, ROW_VALUES);

        View home = findViewById(R.id.row_default_home);
        if (HomeRole.isDefault(this)) {
            home.setVisibility(View.GONE);
        } else {
            home.setOnClickListener(v -> {
                HomeRole.request(this);
                finish();
            });
        }

        for (int i = 0; i < ROW_IDS.length; i++) {
            final int source = ROW_SOURCES[i];
            findViewById(ROW_IDS[i]).setOnClickListener(v -> {
                if (source == 0) turnOff(); else choose(source);
            });
        }
        updateDots();

        findViewById(R.id.row_system).requestFocus();
    }

    /** A row showing "label: value"; selecting it opens a list of the allowed values. */
    private void setupNumberRow(int rowId, final int labelRes, final int titleRes,
                                final String key, final int def, final int[] values) {
        final TextView row = (TextView) findViewById(rowId);
        final android.content.SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        row.setText(getString(labelRes, sp.getInt(key, def)));
        row.setOnClickListener(v -> {
            int current = sp.getInt(key, def);
            String[] items = new String[values.length];
            int checked = -1;
            for (int i = 0; i < values.length; i++) {
                items[i] = String.valueOf(values[i]);
                if (values[i] == current) checked = i;
            }
            new android.app.AlertDialog.Builder(this, R.style.Theme_ATVBasic_Dialog)
                    .setTitle(titleRes)
                    .setSingleChoiceItems(items, checked, (d, which) -> {
                        sp.edit().putInt(key, values[which]).apply();
                        row.setText(getString(labelRes, values[which]));
                        d.dismiss();
                    })
                    .show();
        });
    }

    /** Stops the automatic picture change; the current wallpaper stays. */
    private void turnOff() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putInt(KEY_AUTO, 0).putInt(KEY_MODE, WallpaperSource.RANDOM).apply();
        Toast.makeText(this, R.string.toast_rotation_off, Toast.LENGTH_SHORT).show();
        finish();
    }

    /**
     * Fetches one picture now and, on success, selects this source (rotating every 60 s, or fixed
     * for Bing daily) and closes. A failure only shows a toast and keeps the old selection.
     */
    private void choose(final int source) {
        if (busy) return;
        busy = true;
        final int mode = modeFor(source);
        final Context app = getApplicationContext();
        Thread t = new Thread(() -> {
            boolean ok = false;
            try {
                ok = WallpaperSource.fetchAndSet(app, source, mode);
                if (ok) {
                    app.getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putInt(KEY_AUTO, source).putInt(KEY_MODE, mode).apply();
                }
            } catch (RuntimeException ignored) {
                // reported below as a failure
            }
            final boolean result = ok;
            runOnUiThread(() -> {
                busy = false;
                if (!result) {
                    Toast.makeText(app, R.string.toast_wallpaper_failed, Toast.LENGTH_SHORT).show();
                } else {
                    if (source == WallpaperSource.BING_DAILY) {
                        Toast.makeText(app, R.string.toast_bing_daily, Toast.LENGTH_SHORT).show();
                    }
                    if (!isFinishing()) finish();
                }
            });
        }, "atv-wallpaper");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }
}
