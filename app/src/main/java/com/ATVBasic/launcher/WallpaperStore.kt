package com.ATVBasic.launcher

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

/**
 * Keeps one screen-sized JPEG in app storage; nothing is held in memory while hidden.
 * Scaling, JPEG writing and the change stamp are done in Rust ([Native.wpImport], [Native.wpStamp]);
 * Android-only parts (decoding for display, MediaStore) stay here.
 */
internal object WallpaperStore {

    private const val MAX_INPUT_BYTES = 40L * 1024 * 1024
    private val NAME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

    // Serialises the final swap, so a slideshow download and a "share as wallpaper" never collide.
    private val lock = Any()

    fun file(c: Context): File = File(c.filesDir, "wallpaper.jpg")

    /** A value that changes whenever the wallpaper changes, or 0 when there is none. */
    fun stamp(c: Context): Long = Native.wpStamp(file(c)) ?: 0L

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

    /**
     * Scales [src] to the screen size (in Rust), saves it as the wallpaper and deletes [src].
     * With [fit] the whole picture is kept (no cropping) and centred on black; otherwise it fills
     * the screen and is cropped. Returns false when the picture cannot be decoded.
     */
    fun importFile(c: Context, src: File, fit: Boolean = false): Boolean {
        val dm = c.resources.displayMetrics
        // Serialised, so a slideshow download and a "share as wallpaper" never collide on the same file.
        return synchronized(lock) {
            Native.wpImport(src, file(c), dm.widthPixels, dm.heightPixels, fit) ?: run { src.delete(); false }
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
