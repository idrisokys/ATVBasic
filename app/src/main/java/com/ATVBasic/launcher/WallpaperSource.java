package com.ATVBasic.launcher;

import android.content.Context;

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
import java.util.List;
import java.util.Random;

/**
 * Wallpaper source: Wallhaven nature pictures (no API key needed). 16:9, at least Full HD,
 * most-favorited first, SFW only. One listing page (about 24 pictures) is cached in memory for
 * 60 min, so a 60 s slideshow rarely repeats a picture and stays far below the rate limit.
 */
final class WallpaperSource {

    private static final long POOL_TTL_MS = 60 * 60_000L;
    private static final long MAX_IMAGE_BYTES = 8L * 1024 * 1024;
    private static final long MAX_JSON_BYTES = 2L * 1024 * 1024;
    private static final Random RND = new Random();

    private static ArrayList<String> pool = new ArrayList<>();
    private static long poolTime;

    private WallpaperSource() {}

    /** Blocking. Downloads one nature picture and installs it as the wallpaper. */
    static boolean fetchAndSet(Context ctx) {
        File out = new File(ctx.getCacheDir(), "wp_download");
        try {
            for (int attempt = 0; attempt < 3; attempt++) {
                String url = nextUrl();
                if (url == null) break;
                try {
                    if (fetchFile(url, out)) return WallpaperStore.importFile(ctx, out);
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

    /** Takes one URL from the cached pool, (re)loading it when empty or older than 60 min. */
    private static synchronized String nextUrl() throws Exception {
        if (pool.isEmpty() || System.currentTimeMillis() - poolTime > POOL_TTL_MS) {
            pool = new ArrayList<>(listing());
            Collections.shuffle(pool);
            poolTime = System.currentTimeMillis();
        }
        return pool.isEmpty() ? null : pool.remove(pool.size() - 1);
    }

    private static List<String> listing() throws Exception {
        String q = "https://wallhaven.cc/api/v1/search?categories=100&purity=100"
                + "&q=nature&atleast=1920x1080&ratios=16x9"
                + "&sorting=favorites&order=desc&page=" + (1 + RND.nextInt(5));
        JSONArray data = new JSONObject(getText(q)).getJSONArray("data");
        List<String> list = new ArrayList<>();
        for (int i = 0; i < data.length(); i++) {
            JSONObject item = data.getJSONObject(i);
            // Originals are downloaded as they are: skip the very big ones (slow, memory hungry).
            if (item.optLong("file_size") > MAX_IMAGE_BYTES) continue;
            String path = item.optString("path");
            if (path.startsWith("https://")) list.add(path);
        }
        return list;
    }

    // ------------------------------------------------------------------ HTTP

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(20_000);
        c.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android TV) ATVBasic/3.2 (wallpaper launcher)");
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
