package com.ATVBasic.launcher

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Wallpaper source: Wallhaven nature pictures (no API key needed). 16:9, at least Full HD,
 * most-favorited first, SFW only. One listing page (about 24 pictures) is cached for 60 min,
 * so a 60 s slideshow rarely repeats a picture and stays far below the rate limit.
 * When Wallhaven is unreachable, a random Picsum photo is used instead.
 *
 * The logic lives in Rust ([Native.wpListingUrl], [Native.wpPoolLoad], [Native.wpNext], [Native.wpImport]):
 * the search URL, JSON parsing, the shuffled address pool with its 60 min lifetime, and image scaling.
 * Only the HTTPS transfer stays here (Rust's standard library has no TLS).
 */
internal object WallpaperSource {

    private const val MAX_JSON_BYTES = 2L * 1024 * 1024
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android TV) ATVBasic/4.0 (wallpaper launcher)"

    /** Blocking. Downloads one picture and installs it as the wallpaper. */
    fun fetchAndSet(ctx: Context): Boolean {
        if (!Native.ok) return false
        val tmp = File(ctx.cacheDir, "wp_download.tmp")
        try {
            for (attempt in 0 until 3) {
                val url = nextWallhavenUrl() ?: break
                if (download(url, tmp)) return WallpaperStore.importFile(ctx, tmp)
            }
            val fallback = Native.wpFallbackUrl()
            if (fallback != null && download(fallback, tmp)) return WallpaperStore.importFile(ctx, tmp)
        } catch (ex: Exception) {
            // reported by the caller as a failure
        } catch (ex: OutOfMemoryError) {
            // same
        } finally {
            tmp.delete()
        }
        return false
    }

    /** Takes one URL from the Rust pool; when it is empty or older than 60 min a fresh listing is fetched and loaded. */
    @Synchronized
    private fun nextWallhavenUrl(): String? {
        Native.wpNext()?.let { return it }
        val q = Native.wpListingUrl() ?: return null
        val body = try { getText(q) } catch (ex: Exception) { return null }
        if ((Native.wpPoolLoad(body) ?: 0) <= 0) return null
        return Native.wpNext()
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
