package com.ATVBasic.launcher;

import android.app.Activity;
import android.app.UiModeManager;
import android.app.WallpaperManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.widget.Toast;

import java.io.File;
import java.io.InputStream;

/**
 * Arayüzsüz: Paylaş menüsünden ("Duvar kağıdı yap") gelen görseli kırpmadan ekrana sığdırır ve
 * hem launcher'ın kendi duvar kağıdı hem de sistem ana ekran + kilit ekranı duvar kağıdı yapar.
 */
public class SetWallpaperActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final Uri uri = extractUri(getIntent());
        if (uri == null) {
            Toast.makeText(this, R.string.share_no_image, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        Toast.makeText(this, R.string.share_working, Toast.LENGTH_SHORT).show();
        final Context app = getApplicationContext();
        new Thread(() -> {
            int msg;
            try {
                msg = apply(app, uri);
            } catch (Exception | OutOfMemoryError e) {
                msg = R.string.share_failed;
            }
            final int m = msg;
            runOnUiThread(() -> {
                Toast.makeText(app, m, Toast.LENGTH_LONG).show();
                finish();
            });
        }, "atv-share-wallpaper").start();
    }

    @SuppressWarnings("deprecation")
    private static Uri extractUri(Intent i) {
        if (i == null) return null;
        if (i.getData() != null) return i.getData();
        Uri stream = Build.VERSION.SDK_INT >= 33
                ? i.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class)
                : (Uri) i.getParcelableExtra(Intent.EXTRA_STREAM);
        if (stream != null) return stream;
        if (i.getClipData() != null && i.getClipData().getItemCount() > 0) {
            return i.getClipData().getItemAt(0).getUri();
        }
        return null;
    }

    private static int apply(Context app, Uri uri) throws Exception {
        File tmp = new File(app.getCacheDir(), "share_wallpaper.tmp");
        Bitmap sys = null;
        try {
            try (InputStream in = app.getContentResolver().openInputStream(uri)) {
                if (in == null) return R.string.share_failed;
                WallpaperStore.copy(in, tmp);
            }
            // Sistem duvar kağıdı için (cihazın yönüne göre) kırpmadan sığdırılmış kopya.
            sys = decodeFit(app, tmp);
            // Launcher'ın kendi duvar kağıdı (kaynak dosyayı siler).
            if (!WallpaperStore.importFile(app, tmp, true)) return R.string.share_failed;
            // Seçilen görsel 60 sn sonra slayt gösterisiyle ezilmesin.
            app.getSharedPreferences("dock", Context.MODE_PRIVATE)
                    .edit().putBoolean("slideshow", false).apply();
            return sys == null ? R.string.share_done_launcher : setSystem(app, sys);
        } finally {
            tmp.delete();
            if (sys != null) sys.recycle();
        }
    }

    /** Ana + kilit; kilit ekranı yoksa (TV) sadece ana; sistem desteklemiyorsa sadece launcher. */
    private static int setSystem(Context app, Bitmap bmp) {
        try {
            WallpaperManager wm = WallpaperManager.getInstance(app);
            if (!wm.isWallpaperSupported() || !wm.isSetWallpaperAllowed()) {
                return R.string.share_done_launcher;
            }
            try {
                wm.setBitmap(bmp, null, true, WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK);
                return R.string.share_done_all;
            } catch (Exception e) {
                wm.setBitmap(bmp, null, true, WallpaperManager.FLAG_SYSTEM);
                return R.string.share_done_system;
            }
        } catch (Exception e) {
            return R.string.share_done_launcher;
        }
    }

    /** Telefon: dikey, TV: yatay ekran boyutunda; görsel kırpılmadan ortalanır, kalan alan siyah. */
    private static Bitmap decodeFit(Context app, File src) {
        DisplayMetrics dm = app.getResources().getDisplayMetrics();
        int a = Math.min(dm.widthPixels, dm.heightPixels);
        int b = Math.max(dm.widthPixels, dm.heightPixels);
        if (b > 2560) { // 4K TV'lerde belleği korumak için
            float k = 2560f / b;
            a = Math.round(a * k);
            b = 2560;
        }
        UiModeManager um = (UiModeManager) app.getSystemService(Context.UI_MODE_SERVICE);
        boolean tv = um != null && um.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION;
        int tw = tv ? b : a;
        int th = tv ? a : b;

        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(src.getPath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;

        int sample = 1;
        while (o.outWidth / (sample * 2) >= tw && o.outHeight / (sample * 2) >= th) sample *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap decoded = BitmapFactory.decodeFile(src.getPath(), o);
        if (decoded == null) return null;

        Bitmap out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
        float s = Math.min(tw / (float) decoded.getWidth(), th / (float) decoded.getHeight());
        Matrix m = new Matrix();
        m.postScale(s, s);
        m.postTranslate((tw - decoded.getWidth() * s) / 2f, (th - decoded.getHeight() * s) / 2f);
        Canvas canvas = new Canvas(out);
        canvas.drawColor(Color.BLACK);
        canvas.drawBitmap(decoded, m, new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
        decoded.recycle();
        return out;
    }
}
