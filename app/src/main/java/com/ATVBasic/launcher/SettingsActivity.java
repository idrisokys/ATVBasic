package com.ATVBasic.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Floating settings window. A file wallpaper is static; picking an online source (Wallhaven +
 * Unsplash + Pixabay most-liked, Bing, Picsum) turns on auto-rotation every 30 s.
 */
public final class SettingsActivity extends Activity {

    private static final int REQ_PICK = 1;

    private interface Job {
        boolean run() throws Exception;
    }

    static final String PREFS = "settings";
    static final String KEY_AUTO = "auto_source";   // 0 = off (static), else WallpaperSource id
    static final String KEY_MODE = "auto_mode";     // WallpaperSource.RANDOM / POPULAR (Picsum: variant)

    private boolean busy;

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private static int sourceName(int source) {
        switch (source) {
            case WallpaperSource.UNSPLASH: return R.string.src_unsplash;
            case WallpaperSource.WALLHAVEN: return R.string.src_wallhaven;
            case WallpaperSource.PIXABAY: return R.string.src_pixabay;
            case WallpaperSource.BING: return R.string.src_bing;
            default: return R.string.src_picsum;
        }
    }

    private static int modeName(int source, int mode) {
        if (source == WallpaperSource.PICSUM) {
            return mode == WallpaperSource.GRAYSCALE ? R.string.picsum_grayscale
                    : mode == WallpaperSource.BLUR ? R.string.picsum_blur : R.string.picsum_random;
        }
        if (mode == WallpaperSource.POPULAR) {
            return source == WallpaperSource.BING ? R.string.mode_popular_bing : R.string.mode_popular;
        }
        return R.string.mode_random;
    }

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        findViewById(R.id.row_file).setOnClickListener(v -> pickFile());
        findViewById(R.id.row_online).setOnClickListener(v -> choose(WallpaperSource.MIX, WallpaperSource.POPULAR));
        findViewById(R.id.row_bing).setOnClickListener(v -> showModes(WallpaperSource.BING));
        findViewById(R.id.row_picsum).setOnClickListener(v -> showModes(WallpaperSource.PICSUM));

        findViewById(R.id.row_system).setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (RuntimeException e) {
                Toast.makeText(this, R.string.toast_unavailable, Toast.LENGTH_SHORT).show();
            }
        });
        findViewById(R.id.row_system).requestFocus();
    }

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*");
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.toast_no_picker, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && data != null
                && data.getData() != null) {
            final Uri uri = data.getData();
            final Context app = getApplicationContext();
            runJob(() -> {
                boolean ok = WallpaperStore.importUri(app, uri);
                // A picture from a file is static: stop auto-rotation.
                if (ok) app.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_AUTO, 0).apply();
                return ok;
            }, R.string.toast_wallpaper_set);
        }
    }

    /** Per-source heading list: random / most liked (Picsum: its three variants). */
    private void showModes(final int source) {
        final String[] items;
        if (source == WallpaperSource.PICSUM) {
            items = new String[]{
                    getString(R.string.picsum_random),
                    getString(R.string.picsum_grayscale),
                    getString(R.string.picsum_blur)
            };
        } else {
            items = new String[]{
                    getString(R.string.mode_random),
                    getString(modeName(source, WallpaperSource.POPULAR))
            };
        }
        new AlertDialog.Builder(this, R.style.Theme_ATVBasic_Dialog)
                .setTitle(getString(R.string.source_dialog_title, getString(sourceName(source))))
                .setItems(items, (d, which) -> choose(source, which))
                .show();
    }

    /** Fetches one picture now and, on success, turns on 30 s rotation for this source/mode. */
    private void choose(final int source, final int mode) {
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
