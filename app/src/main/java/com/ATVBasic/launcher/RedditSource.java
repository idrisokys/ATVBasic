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
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds a wallpaper-sized image link in a subreddit (or takes a direct link) and downloads it. */
final class RedditSource {

    private static final String UA = "Android:com.ATVBasic.launcher:v3.0 (wallpaper picker)";
    private static final Pattern SUB_URL = Pattern.compile("reddit\\.com/r/([A-Za-z0-9_]+)");

    private RedditSource() {}

    /** Blocking. Returns the downloaded file, or null on any failure. */
    static File download(Context ctx, String input) {
        File out = new File(ctx.getCacheDir(), "wp_download");
        try {
            String s = input.trim();
            List<String> urls = new ArrayList<>();
            String sub = null;
            Matcher m = SUB_URL.matcher(s);
            if (isImage(s)) {
                urls.add(s);
            } else if (m.find()) {
                sub = m.group(1);
            } else if (s.startsWith("https://")) {
                urls.add(s);
            } else if (!s.startsWith("http")) {
                sub = s.replaceFirst("(?i)^/?r/", "").replaceAll("[^A-Za-z0-9_]", "");
            }
            if (sub != null && !sub.isEmpty()) urls.addAll(imageUrls(sub));

            int tries = 0;
            for (String u : urls) {
                if (tries++ == 3) break;
                if (fetchFile(u, out)) return out;
            }
        } catch (Exception | OutOfMemoryError ignored) {
            // fall through
        }
        return null;
    }

    /** Direct i.redd.it / jpg / png links from this month's top posts, big landscape ones first. */
    private static List<String> imageUrls(String sub) throws Exception {
        String json = getString("https://www.reddit.com/r/" + sub
                + "/top.json?t=month&limit=50&raw_json=1");
        JSONArray posts = new JSONObject(json).getJSONObject("data").getJSONArray("children");
        List<String> good = new ArrayList<>();
        List<String> rest = new ArrayList<>();
        for (int i = 0; i < posts.length(); i++) {
            JSONObject d = posts.getJSONObject(i).optJSONObject("data");
            if (d == null || d.optBoolean("over_18")) continue;
            String u = d.optString("url");
            if (!isImage(u)) continue;
            boolean big = false;
            JSONObject preview = d.optJSONObject("preview");
            JSONArray imgs = preview == null ? null : preview.optJSONArray("images");
            JSONObject src = imgs == null || imgs.length() == 0 ? null
                    : imgs.getJSONObject(0).optJSONObject("source");
            if (src != null) {
                int w = src.optInt("width");
                int h = src.optInt("height");
                big = w >= 1280 && w > h;
            }
            (big ? good : rest).add(u);
        }
        Collections.shuffle(good);
        Collections.shuffle(rest);
        good.addAll(rest);
        return good;
    }

    private static boolean isImage(String u) {
        if (!u.startsWith("https://")) return false;
        String l = u.toLowerCase(Locale.ROOT);
        int q = l.indexOf('?');
        if (q > 0) l = l.substring(0, q);
        return l.startsWith("https://i.redd.it/") || l.endsWith(".jpg")
                || l.endsWith(".jpeg") || l.endsWith(".png");
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(20_000);
        c.setRequestProperty("User-Agent", UA);
        return c;
    }

    private static String getString(String url) throws IOException {
        HttpURLConnection c = open(url);
        try {
            if (c.getResponseCode() != 200) throw new IOException("http " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                    if (bos.size() > 4 * 1024 * 1024) throw new IOException("too large");
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
