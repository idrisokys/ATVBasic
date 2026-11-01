package com.ATVBasic.launcher;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.util.LruCache;

import java.util.HashSet;
import java.util.concurrent.Executor;

/**
 * Renders each app's banner (or icon) into a small RGB_565 bitmap on a background thread and
 * keeps about three dozen of them in an LRU cache. Everything is dropped when the launcher hides.
 */
final class IconLoader {

    private final PackageManager pm;
    private final Executor io;
    private final Handler ui;
    private final Runnable onLoaded;
    private final int w;
    private final int h;
    private final int tileColor;
    private final LruCache<String, Bitmap> cache;
    private final HashSet<String> pending = new HashSet<>(); // UI thread only
    private int generation;                                  // UI thread only

    IconLoader(Context ctx, Executor io, Handler ui, Runnable onLoaded, int w, int h) {
        this.pm = ctx.getPackageManager();
        this.io = io;
        this.ui = ui;
        this.onLoaded = onLoaded;
        this.w = w;
        this.h = h;
        this.tileColor = ctx.getColor(R.color.tile);
        this.cache = new LruCache<String, Bitmap>(Math.max(1, w * h * 2 * 36)) {
            @Override protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount();
            }
        };
    }

    Bitmap peek(String pkg) {
        return cache.get(pkg);
    }

    void request(final AppEntry e) {
        if (!pending.add(e.pkg)) return;
        final int gen = generation;
        io.execute(() -> {
            final Bitmap b = render(e);
            ui.post(() -> {
                pending.remove(e.pkg);
                if (gen != generation) return; // launcher was hidden meanwhile
                cache.put(e.pkg, b);
                onLoaded.run();
            });
        });
    }

    /** Free all bitmaps (called when the launcher is no longer visible). */
    void clear() {
        generation++;
        cache.evictAll();
    }

    private Bitmap render(AppEntry e) {
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        Canvas c = new Canvas(bmp);
        c.drawColor(tileColor);
        try {
            Drawable d = e.info.loadBanner(pm);
            if (d != null) {
                d.setBounds(0, 0, w, h);
            } else {
                d = e.info.loadIcon(pm);
                int s = h * 7 / 10;
                int l = (w - s) / 2;
                int t = (h - s) / 2;
                d.setBounds(l, t, l + s, t + s);
            }
            d.draw(c);
        } catch (Exception | OutOfMemoryError ignored) {
            // Leave the plain tile.
        }
        return bmp;
    }
}
