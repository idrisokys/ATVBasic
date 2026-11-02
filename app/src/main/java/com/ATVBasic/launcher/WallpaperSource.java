package com.ATVBasic.launcher;

import android.content.Context;
import android.util.DisplayMetrics;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Online wallpaper sources for a TV. None of them needs an API key.
 *
 *  - Wallhaven:  nature pictures (q=nature, general category), 16:9, at least Full HD, most-favorited first (sorting=favorites).
 *  - Bing:       the last 8 daily photos in Full HD (1080p); BING_DAILY = only today's photo.
 *  - Wikimedia:  Commons "Featured pictures" (community-voted), filtered to wide, big images.
 *  - Picsum:     a random photo at the exact screen size.
 *
 * Everything is downloaded at 1080p at most. Listings are cached in small in-memory pools
 * (60 min) so a 60 s rotation never repeats a
 * picture and stays far below the services' rate limits.
 */
final class WallpaperSource {

    /** Values double as the "auto_source" preference (0 = off). Ids 2, 4 and 7 are retired. */
    static final int PICSUM = 1;
    static final int WALLHAVEN = 3;
    static final int BING = 5;
    static final int WIKIMEDIA = 6;
    /** Bing's picture of the day: one single picture, no rotation. */
    static final int BING_DAILY = 8;

    static final int RANDOM = 0;
    static final int POPULAR = 1;

    private static final long POOL_TTL_MS = 60 * 60_000L;
    private static final long MAX_JSON_BYTES = 2L * 1024 * 1024;
    private static final Random RND = new Random();

    private interface Loader {
        List<String> load() throws Exception;
    }

    private static final class Pool {
        final long time = System.currentTimeMillis();
        final ArrayList<String> urls;

        Pool(List<String> list) {
            urls = new ArrayList<>(list);
            Collections.shuffle(urls);
        }
    }

    private static final Map<String, Pool> POOLS = new HashMap<>();

    private WallpaperSource() {}

    // ------------------------------------------------------------------ public entry

    /** Blocking. Downloads a picture from {@code source} and installs it as the wallpaper. */
    static boolean fetchAndSet(Context ctx, int source, int mode) {
        File out = new File(ctx.getCacheDir(), "wp_download");
        try {
            DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            int w = Math.min(Math.max(dm.widthPixels, dm.heightPixels), 1920);
            for (int attempt = 0; attempt < 3; attempt++) {
                String url = nextUrl(source, mode, w);
                if (url == null) break;
                try {
                    boolean ok = fetchFile(url, out);
                    if (ok) return WallpaperStore.importFile(ctx, out);
                } catch (IOException ignored) {
                    // this picture failed (too large, network): try the next one
                }
            }
        } catch (Exception | OutOfMemoryError ignored) {
            // reported by the caller as a failure
        }
        out.delete();
        return false;
    }

    // ------------------------------------------------------------------ URL selection

    private static String nextUrl(final int source, final int mode, final int w) throws Exception {
        switch (source) {
            case WALLHAVEN:
                return pop(source + ":" + mode, () -> wallhaven(mode));
            case BING:
                return pop(source + ":" + mode, () -> bing(8));
            case BING_DAILY:
                List<String> today = bing(1);
                return today.isEmpty() ? null : today.get(0);
            case WIKIMEDIA:
                return pop(source + ":" + mode, () -> wikimedia(w));
            case PICSUM:
                return "https://picsum.photos/" + w + "/" + Math.round(w * 9f / 16f)
                        + "?random=" + System.nanoTime();
            default:
                return null;
        }
    }

    /** Takes one URL from the cached pool, (re)loading it when empty or older than 60 min. */
    private static synchronized String pop(String key, Loader loader) throws Exception {
        Pool p = POOLS.get(key);
        if (p == null || p.urls.isEmpty()
                || System.currentTimeMillis() - p.time > POOL_TTL_MS) {
            p = new Pool(loader.load());
            POOLS.put(key, p);
        }
        return p.urls.isEmpty() ? null : p.urls.remove(p.urls.size() - 1);
    }

    private static List<String> wallhaven(int mode) throws Exception {
        String q = "https://wallhaven.cc/api/v1/search?categories=100&purity=100"
                + "&q=nature&atleast=1920x1080&ratios=16x9";
        q += mode == POPULAR
                ? "&sorting=favorites&order=desc&page=" + (1 + RND.nextInt(5))
                : "&sorting=random";
        JSONArray data = new JSONObject(getText(q)).getJSONArray("data");
        List<String> list = new ArrayList<>();
        for (int i = 0; i < data.length(); i++) {
            String path = data.getJSONObject(i).optString("path");
            if (!path.isEmpty()) list.add(path);
        }
        return list;
    }

    private static List<String> bing(int count) throws Exception {
        JSONArray a = new JSONObject(getText(
                "https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=" + count + "&mkt=tr-TR"))
                .getJSONArray("images");
        List<String> list = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            String base = a.getJSONObject(i).optString("urlbase");
            if (!base.isEmpty()) list.add("https://www.bing.com" + base + "_1920x1080.jpg");
        }
        return list;
    }

    /** Commons "Featured pictures", starting at a random place in the category. */
    private static List<String> wikimedia(int width) throws Exception {
        String base = "https://commons.wikimedia.org/w/api.php?action=query&format=json"
                + "&generator=categorymembers&gcmtitle=Category:Featured_pictures_on_Wikimedia_Commons"
                + "&gcmtype=file&gcmlimit=50&gcmsort=sortkey"
                + "&prop=imageinfo&iiprop=url%7Csize%7Cmime&iiurlwidth=" + width;
        List<String> list = new ArrayList<>();
        for (int attempt = 0; attempt < 3 && list.isEmpty(); attempt++) {
            String start = "";
            if (attempt < 2) { // three random capital letters as the start sort key
                start = "&gcmstarthexsortkey=" + String.format("%02x%02x%02x",
                        0x41 + RND.nextInt(26), 0x41 + RND.nextInt(26), 0x41 + RND.nextInt(26));
            }
            JSONObject query = new JSONObject(getText(base + start)).optJSONObject("query");
            JSONObject pages = query == null ? null : query.optJSONObject("pages");
            if (pages == null) continue;
            Iterator<String> ids = pages.keys();
            while (ids.hasNext()) {
                JSONArray info = pages.getJSONObject(ids.next()).optJSONArray("imageinfo");
                if (info == null || info.length() == 0) continue;
                JSONObject ii = info.getJSONObject(0);
                String mime = ii.optString("mime");
                if (!mime.equals("image/jpeg") && !mime.equals("image/png")) continue;
                int iw = ii.optInt("width");
                int ih = ii.optInt("height");
                if (iw < 1920 || ih < 1080) continue;
                double ratio = iw / (double) ih;
                if (ratio < 1.45 || ratio > 2.1) continue;
                String url = ii.optString("thumburl"); // scaled to the screen width: small download
                if (url.isEmpty()) url = ii.optString("url");
                if (!url.isEmpty()) list.add(url);
            }
        }
        return list;
    }

    // ------------------------------------------------------------------ HTTP

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(20_000);
        c.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android TV) ATVBasic/3.1 (wallpaper launcher)");
        return c;
    }

    private static String getText(String url) throws IOException {
        HttpURLConnection c = open(url);
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8 * 1024];
                long total = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_JSON_BYTES) throw new IOException("too large");
                    bos.write(buf, 0, n);
                }
                return bos.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }

    private static boolean fetchFile(String url, File out) throws IOException {
        HttpURLConnection c = open(url);
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
