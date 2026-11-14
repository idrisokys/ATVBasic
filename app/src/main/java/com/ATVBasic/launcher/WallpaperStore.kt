package com.ATVBasic.launcher

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Keeps one screen-sized JPEG in app storage; nothing is held in memory while hidden. */
internal object WallpaperStore {

    private const val MAX_INPUT_BYTES = 40L * 1024 * 1024
    private val NAME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

    // Serialises the final swap, so a slideshow download and a "share as wallpaper" never collide.
    private val lock = Any()

    fun file(c: Context): File = File(c.filesDir, "wallpaper.jpg")

    /** A value that changes whenever the wallpaper changes, or 0 when there is none. */
    fun stamp(c: Context): Long {
        val f = file(c)
        if (!f.isFile) return 0L
        return maxOf(1L, f.lastModified() * 31 + f.length())
    }

    /** Decodes the saved wallpaper (RGB_565, screen-sized) or returns null. */
    fun load(c: Context): Bitmap? {
        val f = file(c)
        if (!f.isFile) return null
        val o = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        return try {
            BitmapFactory.decodeFile(f.path, o)
        } catch (ex: OutOfMemoryError) {
            null
        }
    }

    /** Largest power-of-two sample size that still leaves the picture at least target-sized. */
    fun sampleSize(w: Int, h: Int, targetW: Int, targetH: Int): Int {
        var sample = 1
        while (w / (sample * 2) >= targetW && h / (sample * 2) >= targetH) sample *= 2
        return sample
    }

    /**
     * Scales {@code src} to the screen size, saves it as the wallpaper and deletes {@code src}.
     * With [fit] the whole picture is kept (no cropping) and centred on black; otherwise it fills
     * the screen and is cropped. Returns false when the picture cannot be decoded.
     */
    @Throws(IOException::class)
    fun importFile(c: Context, src: File, fit: Boolean = false): Boolean {
        var tmpOut: File? = null
        try {
            val dm = c.resources.displayMetrics
            // 1080p is plenty for a wallpaper and keeps memory low on 4K TVs (the view scales it up).
            val tw = minOf(maxOf(dm.widthPixels, dm.heightPixels), 1920)
            val th = minOf(minOf(dm.widthPixels, dm.heightPixels), 1080)

            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(src.path, o)
            if (o.outWidth <= 0 || o.outHeight <= 0) return false

            o.inJustDecodeBounds = false
            o.inSampleSize = sampleSize(o.outWidth, o.outHeight, tw, th)
            o.inPreferredConfig = Bitmap.Config.RGB_565
            val decoded = BitmapFactory.decodeFile(src.path, o) ?: return false

            val out = Bitmap.createBitmap(tw, th, Bitmap.Config.RGB_565)
            try {
                val sx = tw / decoded.width.toFloat()
                val sy = th / decoded.height.toFloat()
                val s = if (fit) minOf(sx, sy) else maxOf(sx, sy)
                val m = Matrix()
                m.postScale(s, s)
                m.postTranslate((tw - decoded.width * s) / 2f, (th - decoded.height * s) / 2f)
                val canvas = Canvas(out)
                if (fit) canvas.drawColor(Color.BLACK)
                canvas.drawBitmap(decoded, m, Paint(Paint.FILTER_BITMAP_FLAG))
            } finally {
                decoded.recycle()
            }

            val tmp = File.createTempFile("wallpaper", ".tmp", c.filesDir)
            tmpOut = tmp
            val written = try {
                FileOutputStream(tmp).use { out.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            } finally {
                out.recycle()
            }
            if (!written) return false

            val renamed = synchronized(lock) { tmp.renameTo(file(c)) }
            if (renamed) tmpOut = null
            return renamed
        } catch (ex: OutOfMemoryError) {
            return false
        } finally {
            tmpOut?.delete()
            src.delete()
        }
    }

    /**
     * Blocking. Copies the current wallpaper (the screen-sized JPEG) into the public Downloads folder.
     * Android 10+ needs no permission (MediaStore); Android 8/9 needs WRITE_EXTERNAL_STORAGE.
     */
    fun saveToDownloads(c: Context): Boolean {
        val src = file(c)
        if (!src.isFile) return false
        val name = "wallpaper_" + LocalDateTime.now().format(NAME_FORMAT) + ".jpg"
        return try {
            if (Build.VERSION.SDK_INT >= 29) saveViaMediaStore(c, src, name) else saveLegacy(c, src, name)
        } catch (ex: RuntimeException) {
            false
        }
    }

    @SuppressLint("NewApi")
    private fun saveViaMediaStore(c: Context, src: File, name: String): Boolean {
        val r = c.contentResolver
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: return false
        try {
            val os = r.openOutputStream(uri) ?: throw IOException("no stream")
            os.use { out -> FileInputStream(src).use { it.copyTo(out, 16 * 1024) } }
        } catch (ex: IOException) {
            r.delete(uri, null, null)
            return false
        }
        v.clear()
        v.put(MediaStore.MediaColumns.IS_PENDING, 0)
        r.update(uri, v, null, null)
        return true
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(c: Context, src: File, name: String): Boolean {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.isDirectory && !dir.mkdirs()) return false
        val dst = File(dir, name)
        try {
            FileOutputStream(dst).use { out -> FileInputStream(src).use { it.copyTo(out, 16 * 1024) } }
        } catch (ex: IOException) {
            dst.delete()
            return false
        }
        MediaScannerConnection.scanFile(c, arrayOf(dst.path), null, null)
        return true
    }

    /** Copies a stream to a file, refusing inputs larger than 40 MB. */
    @Throws(IOException::class)
    fun copy(input: InputStream, dst: File) {
        val buf = ByteArray(16 * 1024)
        var total = 0L
        FileOutputStream(dst).use { os ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_INPUT_BYTES) throw IOException("too large")
                os.write(buf, 0, n)
            }
        }
    }
}
