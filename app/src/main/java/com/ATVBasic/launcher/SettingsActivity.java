package com.ATVBasic.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import java.io.File;

/** Floating settings window: wallpaper (file / Picsum) and a shortcut to system settings. */
public final class SettingsActivity extends Activity {

    private static final int REQ_PICK = 1;

    private interface Job {
        boolean run() throws Exception;
    }

    private boolean busy;

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        findViewById(R.id.row_file).setOnClickListener(v -> pickFile());
        findViewById(R.id.row_picsum).setOnClickListener(v -> showPicsum());
        findViewById(R.id.row_system).setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (RuntimeException e) {
                Toast.makeText(this, R.string.toast_unavailable, Toast.LENGTH_SHORT).show();
            }
        });
        findViewById(R.id.row_file).requestFocus();
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
            runJob(() -> WallpaperStore.importUri(app, uri));
        }
    }

    private void showPicsum() {
        String[] items = {
                getString(R.string.picsum_random),
                getString(R.string.picsum_grayscale),
                getString(R.string.picsum_blur)
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.picsum_title)
                .setItems(items, (d, which) -> fetch(which))
                .show();
    }

    private void fetch(final int mode) {
        final Context app = getApplicationContext();
        runJob(() -> {
            File f = PicsumSource.download(app, mode);
            return f != null && WallpaperStore.importFile(app, f);
        });
    }

    /** Runs {@code job} on a background thread, reports the result with a toast, then closes. */
    private void runJob(final Job job) {
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
                Toast.makeText(app, result ? R.string.toast_wallpaper_set
                        : R.string.toast_wallpaper_failed, Toast.LENGTH_SHORT).show();
                busy = false;
                if (result && !isFinishing()) finish();
            });
        }, "atv-wallpaper");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }
}
