package com.ATVBasic.launcher

import android.content.Context
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Random

/**
 * Wallpaper source: Wallhaven nature pictures (no API key needed). 16:9, at least Full HD,
 * most-favorited first, SFW only. One listing page (about 24 pictures) is cached in memory for
 * 60 min, so a 60 s slideshow rarely repeats a picture and stays far below the rate limit.
 * When Wallhaven is unreachable, a random Picsum photo is used instead.
 */
internal object WallpaperSource {

    private const val POOL_TTL_MS = 60 * 60_000L
    private const val MAX_IMAGE_BYTES = 8L * 1024 * 1024
    private const val MAX_JSON_BYTES = 2L * 1024 * 1024
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android TV) ATVBasic/4.0 (wallpaper launcher)"

    private val random = Random()
    private val pool = ArrayList<String>()
    private var poolTime = 0L

    /** Blocking. Downloads one picture and installs it as the wallpaper. */
    fun fetchAndSet(ctx: Context): Boolean {
        val tmp = File(ctx.cacheDir, "wp_download.tmp")
        try {
            for (attempt in 0 until 3) {
                val url = nextWallhavenUrl() ?: break
                if (download(url, tmp)) return WallpaperStore.importFile(ctx, tmp)
            }
            val fallback = "https://picsum.photos/1920/1080.jpg?random=" + System.currentTimeMillis()
            if (download(fallback, tmp)) return WallpaperStore.importFile(ctx, tmp)
        } catch (ex: Exception) {
            // reported by the caller as a failure
        } catch (ex: OutOfMemoryError) {
            // same
        } finally {
            tmp.delete()
        }
        return false
    }

    /** Takes one URL from the cached pool, (re)loading it when empty or older than 60 min. */
    @Synchronized
    private fun nextWallhavenUrl(): String? {
        if (pool.isEmpty() || System.currentTimeMillis() - poolTime > POOL_TTL_MS) {
            val fresh = try {
                listing()
            } catch (ex: Exception) {
                emptyList()
            }
            pool.clear()
            pool.addAll(fresh.shuffled())
            poolTime = System.currentTimeMillis()
        }
        return if (pool.isEmpty()) null else pool.removeAt(pool.size - 1)
    }

    @Throws(Exception::class)
    private fun listing(): List<String> {
        val q = "https://wallhaven.cc/api/v1/search?categories=100&purity=100" +
            "&q=nature&atleast=1920x1080&ratios=16x9" +
            "&sorting=favorites&order=desc&page=" + (1 + random.nextInt(5))
        val data = JSONObject(getText(q)).getJSONArray("data")
        val list = ArrayList<String>()
        for (i in 0 until data.length()) {
            val item = data.getJSONObject(i)
            // Originals are downloaded as they are: skip the very big ones (slow, memory hungry).
            if (item.optLong("file_size") > MAX_IMAGE_BYTES) continue
            val path = item.optString("path")
            if (path.startsWith("https://")) list.add(path)
        }
        return list
    }

    // ------------------------------------------------------------------ HTTP

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", USER_AGENT)
        return c
    }

    @Throws(IOException::class)
    private fun getText(url: String): String {
        val c = open(url)
        try {
            if (c.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP " + c.responseCode)
            return c.inputStream.use { input ->
                val bos = ByteArrayOutputStream()
                val buf = ByteArray(8 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_JSON_BYTES) throw IOException("too large")
                    bos.write(buf, 0, n)
                }
                bos.toString("UTF-8")
            }
        } finally {
            c.disconnect()
        }
    }

    /** Returns true when a picture was saved to [out]; any failure just means "try the next one". */
    private fun download(url: String, out: File): Boolean {
        val c = try {
            open(url)
        } catch (ex: IOException) {
            return false
        }
        try {
            if (c.responseCode != HttpURLConnection.HTTP_OK) return false
            val type = c.contentType
            if (type != null && !type.startsWith("image/")) return false
            c.inputStream.use { WallpaperStore.copy(it, out) }
            return true
        } catch (ex: IOException) {
            return false
        } finally {
            c.disconnect()
        }
    }
}
