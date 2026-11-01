package com.ATVBasic.launcher;

import android.content.Context;
import android.util.DisplayMetrics;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Downloads a landscape photo from Unsplash. The old random endpoint (source.unsplash.com) is gone
 * and the official API needs a key, so this picks from a curated list of photo IDs and lets
 * images.unsplash.com crop them to the screen size.
 */
final class UnsplashSource {

    private static final String UA = "Android:com.ATVBasic.launcher (wallpaper picker)";

    private static final String[] PHOTOS = {
            "1506744038136-46273834b3fb", "1469474968028-56623f02e42e",
            "1441974231531-c6227db76b6e", "1470071459604-3b5ec3a7fe05",
            "1501785888041-af3ef285b470", "1464822759023-fed622ff2c3b",
            "1506905925346-21bda4d32df4", "1472214103451-9374bd1c798e",
            "1418065460487-3e41a6c84dc5", "1519681393784-d120267933ba",
            "1476514525535-07fb3b4ae5f1", "1433086966358-54859d0ed716",
            "1507525428034-b723cf961d3e", "1465146344425-f00d5f5c8f07",
            "1511497584788-876760111969", "1494548162494-384bba4ab999"
    };

    private UnsplashSource() {}

    /** Blocking. Returns the downloaded file, or null on any failure. */
    static File download(Context ctx) {
        File out = new File(ctx.getCacheDir(), "wp_download_unsplash");
        try {
            DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            int w = Math.min(Math.max(dm.widthPixels, dm.heightPixels), 3840);
            int h = Math.min(Math.min(dm.widthPixels, dm.heightPixels), 2160);
            List<String> ids = new ArrayList<>();
            Collections.addAll(ids, PHOTOS);
            Collections.shuffle(ids);
            for (int i = 0; i < 3; i++) {
                String u = "https://images.unsplash.com/photo-" + ids.get(i)
                        + "?w=" + w + "&h=" + h + "&fit=crop&q=85&fm=jpg";
                if (fetchFile(u, out)) return out;
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
