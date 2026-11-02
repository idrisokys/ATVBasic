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
 * Online wallpaper sources: Wallhaven, Pixabay, Bing (and Picsum).
 *
 *  - Wallhaven: most-favorited (sorting=favorites) or random, no key needed.
 *  - Pixabay:   order=popular or latest, API key from BuildConfig.PIXABAY_KEY.
 *  - Bing:      last 8 daily photos / today's featured photo.
 *
 * Listings are cached in small in-memory pools (20 min) so a 30 s rotation never repeats a
 * picture and stays far below the APIs' rate limits.
 */
final class WallpaperSource {

    /** Values double as the "auto_source" preference (0 = off / static). */
    static final int PICSUM = 1;
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

    /** Baked in at build time (PIXABAY_KEY); may be empty. */
    static String apiKey(Context c, int source) {
        return BuildConfig.PIXABAY_KEY;
    }

    /** Pixabay cannot be used without a key. */
    static boolean missingKey(Context c, int source) {
        return source == PIXABAY && apiKey(c, source).isEmpty();
    }

    // ------------------------------------------------------------------ public entry

    /** Blocking. Downloads a picture from {@code source} and installs it as the wallpaper. */
    static boolean fetchAndSet(Context ctx, int source, int mode) {
        return fetchOne(ctx, source, mode);
    }

    private static boolean fetchOne(Context ctx, int source, int mode) {
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
