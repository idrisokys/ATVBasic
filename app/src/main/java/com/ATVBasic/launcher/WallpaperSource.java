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
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Online wallpaper sources: Wallhaven, Unsplash, Pixabay, Bing (and Picsum).
 *
 * Every source has two modes: RANDOM and POPULAR ("en sevilen"):
 *  - Wallhaven: sorting=random / sorting=favorites (no key needed).
 *  - Pixabay:   order=latest / order=popular (free API key required).
 *  - Unsplash:  /photos/random / /photos?order_by=popular with an API key; without a key a
 *               curated list of well-known photos is used for both modes.
 *  - Bing:      last 8 daily photos / today's featured photo (Bing has no like counter).
 *
 * Listings are cached in small in-memory pools (20 min) so a 30 s rotation neither repeats a
 * picture nor exhausts API rate limits (Unsplash demo keys allow 50 requests/hour).
 */
final class WallpaperSource {

    /** Values double as the "auto_source" preference (0 = off / static). */
    static final int PICSUM = 1;
    static final int UNSPLASH = 2;
    static final int WALLHAVEN = 3;
    static final int PIXABAY = 4;
    static final int BING = 5;

    /** Modes for every source except Picsum. */
    static final int RANDOM = 0;
    static final int POPULAR = 1;

    /** Picsum variants (share the "mode" slot). */
    static final int PLAIN = 0;
    static final int GRAYSCALE = 1;
    static final int BLUR = 2;

    private static final long POOL_TTL_MS = 20 * 60_000L;
    private static final long MAX_JSON_BYTES = 2L * 1024 * 1024;
    private static final Random RND = new Random();

    private static final String[] UNSPLASH_IDS = {
            "1506744038136-46273834b3fb", "1469474968028-56623f02e42e",
            "1441974231531-c6227db76b6e", "1470071459604-3b5ec3a7fe05",
            "1501785888041-af3ef285b470", "1464822759023-fed622ff2c3b",
            "1506905925346-21bda4d32df4", "1472214103451-9374bd1c798e",
            "1418065460487-3e41a6c84dc5", "1519681393784-d120267933ba",
            "1476514525535-07fb3b4ae5f1", "1433086966358-54859d0ed716",
            "1507525428034-b723cf961d3e", "1465146344425-f00d5f5c8f07",
            "1511497584788-876760111969", "1494548162494-384bba4ab999"
    };

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

    // ------------------------------------------------------------------ API keys

    /** SharedPreferences name for a source's API key (Pixabay / Unsplash only). */
    static String prefKey(int source) {
        return source == PIXABAY ? "key_pixabay" : "key_unsplash";
    }

    /** Key typed in Settings, else the one baked in at build time (may be empty). */
    static String apiKey(Context c, int source) {
        String v = c.getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE)
                .getString(prefKey(source), "");
        if (v != null && !v.trim().isEmpty()) return v.trim();
        return source == PIXABAY ? BuildConfig.PIXABAY_KEY : BuildConfig.UNSPLASH_KEY;
    }

    /** Pixabay cannot be used without a key (Unsplash falls back to a curated list). */
    static boolean missingKey(Context c, int source) {
        return source == PIXABAY && apiKey(c, source).isEmpty();
    }

    // ------------------------------------------------------------------ public entry

    /** Blocking. Downloads a picture from {@code source} and installs it as the wallpaper. */
    static boolean fetchAndSet(Context ctx, int source, int mode) {
        File out = new File(ctx.getCacheDir(), "wp_download");
        try {
            DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            int w = Math.min(Math.max(dm.widthPixels, dm.heightPixels), 3840);
            int h = Math.min(Math.min(dm.widthPixels, dm.heightPixels), 2160);
            for (int attempt = 0; attempt < 3; attempt++) {
                String url = nextUrl(ctx, source, mode, w, h);
                if (url == null) break;
                boolean ok = fetchFile(url, out);
                if (!ok && url.endsWith("_UHD.jpg")) { // Bing: fall back to Full HD
                    ok = fetchFile(url.replace("_UHD.jpg", "_1920x1080.jpg"), out);
                }
                if (ok) return WallpaperStore.importFile(ctx, out);
            }
        } catch (Exception | OutOfMemoryError ignored) {
            // reported by the caller as a failure
        }
        out.delete();
        return false;
    }

    // ------------------------------------------------------------------ URL selection

    private static String nextUrl(final Context ctx, final int source, final int mode,
                                  final int w, final int h) throws Exception {
        switch (source) {
            case UNSPLASH:
                return pop(source + ":" + mode, () -> unsplash(ctx, mode, w, h));
            case WALLHAVEN:
                return pop(source + ":" + mode, () -> wallhaven(mode));
            case PIXABAY:
                return pop(source + ":" + mode, () -> pixabay(apiKey(ctx, PIXABAY), mode));
            case BING:
                return pop(source + ":" + mode, () -> bing(mode));
            default: {
                String suffix = mode == GRAYSCALE ? "&grayscale" : mode == BLUR ? "&blur=2" : "";
                return "https://picsum.photos/" + w + "/" + h
                        + "?random=" + System.nanoTime() + suffix;
            }
        }
    }

    /** Takes one URL from the cached pool, (re)loading it when empty or older than 20 min. */
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
        String q = "https://wallhaven.cc/api/v1/search?categories=111&purity=100"
                + "&atleast=1920x1080&ratios=16x9";
        q += mode == POPULAR
                ? "&sorting=favorites&order=desc&page=" + (1 + RND.nextInt(5))
                : "&sorting=random";
        JSONArray data = new JSONObject(getText(q, null)).getJSONArray("data");
        List<String> list = new ArrayList<>();
        for (int i = 0; i < data.length(); i++) {
            String path = data.getJSONObject(i).optString("path");
            if (!path.isEmpty()) list.add(path);
        }
        return list;
    }

    private static List<String> pixabay(String key, int mode) throws Exception {
        String base = "https://pixabay.com/api/?key=" + URLEncoder.encode(key, "UTF-8")
                + "&image_type=photo&orientation=horizontal&min_width=1920&min_height=1080"
                + "&safesearch=true&per_page=50&order=" + (mode == POPULAR ? "popular" : "latest");
        int maxPage = mode == POPULAR ? 3 : 8;
        String body;
        try {
            body = getText(base + "&page=" + (1 + RND.nextInt(maxPage)), null);
        } catch (IOException e) { // page out of range: first page always exists
            body = getText(base + "&page=1", null);
        }
        JSONArray hits = new JSONObject(body).getJSONArray("hits");
        List<String> list = new ArrayList<>();
        for (int i = 0; i < hits.length(); i++) {
            JSONObject o = hits.getJSONObject(i);
            String u = o.optString("fullHDURL"); // only present for approved API accounts
            if (u.isEmpty()) u = o.optString("largeImageURL");
            if (!u.isEmpty()) list.add(u);
        }
        return list;
    }

    private static List<String> unsplash(Context ctx, int mode, int w, int h) {
        String size = "w=" + w + "&h=" + h + "&fit=crop&q=85&fm=jpg";
        List<String> list = new ArrayList<>();
        String key = apiKey(ctx, UNSPLASH);
        if (!key.isEmpty()) {
            try {
                String url = mode == POPULAR
                        ? "https://api.unsplash.com/photos?order_by=popular&per_page=30&page="
                                + (1 + RND.nextInt(5))
                        : "https://api.unsplash.com/photos/random?count=30&orientation=landscape";
                JSONArray a = new JSONArray(getText(url, "Client-ID " + key));
                for (int i = 0; i < a.length(); i++) {
                    String raw = a.getJSONObject(i).getJSONObject("urls").optString("raw");
                    if (!raw.isEmpty()) list.add(raw + (raw.contains("?") ? "&" : "?") + size);
                }
            } catch (Exception ignored) {
                // no key / rate limit: use the curated list below
            }
        }
        if (list.isEmpty()) {
            for (String id : UNSPLASH_IDS) {
                list.add("https://images.unsplash.com/photo-" + id + "?" + size);
            }
        }
        return list;
    }

    private static List<String> bing(int mode) throws Exception {
        JSONArray a = new JSONObject(getText(
                "https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=8&mkt=tr-TR", null))
                .getJSONArray("images");
        int n = mode == POPULAR ? Math.min(1, a.length()) : a.length(); // today's featured photo
        List<String> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String base = a.getJSONObject(i).optString("urlbase");
            if (!base.isEmpty()) list.add("https://www.bing.com" + base + "_UHD.jpg");
        }
        return list;
    }

    // ------------------------------------------------------------------ HTTP

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(20_000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android TV) ATVBasic");
        return c;
    }

    private static String getText(String url, String authorization) throws IOException {
        HttpURLConnection c = open(url);
        if (authorization != null) c.setRequestProperty("Authorization", authorization);
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
