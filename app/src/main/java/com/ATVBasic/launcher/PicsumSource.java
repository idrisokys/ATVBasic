package com.ATVBasic.launcher;

import android.content.Context;
import android.util.DisplayMetrics;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Downloads a random wallpaper-sized photo from picsum.photos. */
final class PicsumSource {

    private static final String UA = "Android:com.ATVBasic.launcher (wallpaper picker)";

    static final int MODE_RANDOM = 0;
    static final int MODE_GRAYSCALE = 1;
    static final int MODE_BLUR = 2;

    private PicsumSource() {}

    /** Blocking. Returns the downloaded file, or null on any failure. */
    static File download(Context ctx, int mode) {
        File out = new File(ctx.getCacheDir(), "wp_download");
        try {
            DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            int w = Math.min(Math.max(dm.widthPixels, dm.heightPixels), 3840);
            int h = Math.min(Math.min(dm.widthPixels, dm.heightPixels), 2160);
            StringBuilder u = new StringBuilder("https://picsum.photos/")
                    .append(w).append('/').append(h)
                    .append("?random=").append(System.currentTimeMillis());
            if (mode == MODE_GRAYSCALE) u.append("&grayscale");
            if (mode == MODE_BLUR) u.append("&blur=2");
            for (int attempt = 0; attempt < 2; attempt++) {
                if (fetchFile(u.toString(), out)) return out;
            }
        } catch (Exception | OutOfMemoryError ignored) {
            // fall through
        }
        return null;
    }

    private static boolean fetchFile(String url, File out) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(20_000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", UA);
        try {
            if (c.getResponseCode() != 200) return false;
            String type = c.getContentType();
            if (type != null && !type.startsWith("image/")) return false;
            try (InputStream in = c.getInputStream()) {
                WallpaperStore.copy(in, out);
            }
            return true;
        } finally {
            c.disconnect();
        }
    }
}
