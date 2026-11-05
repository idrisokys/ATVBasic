package com.ATVBasic.launcher;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.DisplayMetrics;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Keeps one screen-sized JPEG in app storage; nothing is held in memory while hidden. */
final class WallpaperStore {

    private static final long MAX_INPUT_BYTES = 40L * 1024 * 1024;

    private WallpaperStore() {}

    static File file(Context c) {
        return new File(c.getFilesDir(), "wallpaper.jpg");
    }

    /** Last-modified time of the wallpaper, or 0 when there is none. */
    static long stamp(Context c) {
        return file(c).lastModified();
    }

    /** Decodes the saved wallpaper (RGB_565, screen-sized) or returns null. */
    static Bitmap load(Context c) {
        File f = file(c);
        if (!f.exists()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        try {
            return BitmapFactory.decodeFile(f.getPath(), o);
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    /** Scales/crops {@code src} to the screen size, saves it as the wallpaper, deletes {@code src}. */
    static boolean importFile(Context c, File src) throws IOException {
        try {
            DisplayMetrics dm = c.getResources().getDisplayMetrics();
            // 1080p is plenty for a wallpaper and keeps memory low on 4K TVs (the view scales it up).
            int tw = Math.min(Math.max(dm.widthPixels, dm.heightPixels), 1920);
            int th = Math.min(Math.min(dm.widthPixels, dm.heightPixels), 1080);

            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(src.getPath(), o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return false;

            int sample = 1;
            while (o.outWidth / (sample * 2) >= tw && o.outHeight / (sample * 2) >= th) sample *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap decoded = BitmapFactory.decodeFile(src.getPath(), o);
            if (decoded == null) return false;

            Bitmap out = Bitmap.createBitmap(tw, th, Bitmap.Config.RGB_565);
            float s = Math.max(tw / (float) decoded.getWidth(), th / (float) decoded.getHeight());
            Matrix m = new Matrix();
            m.postScale(s, s);
            m.postTranslate((tw - decoded.getWidth() * s) / 2f, (th - decoded.getHeight() * s) / 2f);
            new Canvas(out).drawBitmap(decoded, m, new Paint(Paint.FILTER_BITMAP_FLAG));
            decoded.recycle();

            File tmpOut = new File(c.getFilesDir(), "wallpaper.tmp");
            try (OutputStream os = new FileOutputStream(tmpOut)) {
                out.compress(Bitmap.CompressFormat.JPEG, 88, os);
            } finally {
                out.recycle();
            }
            return tmpOut.renameTo(file(c));
        } finally {
            src.delete();
        }
    }

    /**
     * Blocking. Copies the current wallpaper (the screen-sized JPEG) into the public Downloads folder.
     * Android 10+ needs no permission (MediaStore); Android 8/9 needs WRITE_EXTERNAL_STORAGE.
     */
    static boolean saveToDownloads(Context c) {
        File src = file(c);
        if (!src.exists()) return false;
        String name = "wallpaper_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".jpg";
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentResolver r = c.getContentResolver();
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                v.put(MediaStore.MediaColumns.IS_PENDING, 1);
                Uri uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) return false;
                try (OutputStream os = r.openOutputStream(uri);
                     InputStream in = new FileInputStream(src)) {
                    if (os == null) throw new IOException("no stream");
                    pipe(in, os);
                } catch (IOException e) {
                    r.delete(uri, null, null);
                    return false;
                }
                v.clear();
                v.put(MediaStore.MediaColumns.IS_PENDING, 0);
                r.update(uri, v, null, null);
                return true;
            }
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!dir.isDirectory() && !dir.mkdirs()) return false;
            File dst = new File(dir, name);
            try (OutputStream os = new FileOutputStream(dst);
                 InputStream in = new FileInputStream(src)) {
                pipe(in, os);
            } catch (IOException e) {
                dst.delete();
                return false;
            }
            MediaScannerConnection.scanFile(c, new String[]{dst.getPath()}, null, null);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void pipe(InputStream in, OutputStream os) throws IOException {
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
    }

    /** Copies a stream to a file, refusing inputs larger than 40 MB. */
    static void copy(InputStream in, File dst) throws IOException {
        byte[] buf = new byte[16 * 1024];
        long total = 0;
        try (OutputStream os = new FileOutputStream(dst)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_INPUT_BYTES) throw new IOException("too large");
                os.write(buf, 0, n);
            }
        }
    }
}
