package com.ATVBasic.launcher

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

/**
 * Dosya seçicide basılı tutulan dosyayı başka bir uygulamayla açmak için küçük, salt-okunur
 * içerik sağlayıcı (androidx FileProvider yerine; projede bağımlılık yok). Yol denetimi Rust'ta ([Native.resolveInside]). Yalnızca
 * [FilePickActivity] tarafından verilen geçici okuma izniyle erişilir (exported=false).
 */
class OpenProvider : ContentProvider() {

    companion object {
        private fun authority(c: android.content.Context) = c.packageName + ".open"

        fun uriFor(c: android.content.Context, f: File): Uri =
            Uri.Builder().scheme("content").authority(authority(c)).appendPath(f.absolutePath.removePrefix("/")).build()

        fun mimeOf(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext == "apk") return "application/vnd.android.package-archive"
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
        }
    }

    override fun onCreate() = true

    /** Yolun gerçek (sembolik bağlar çözülmüş) hali depolama kökü içinde kalan düz bir dosya olmalı; denetim Rust'ta. */
    @Suppress("DEPRECATION")
    private fun resolve(uri: Uri): File {
        val root = Environment.getExternalStorageDirectory()
        return Native.resolveInside(root, "/" + uri.path.orEmpty().removePrefix("/"))
            ?: throw FileNotFoundException("outside storage or not a file")
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("read only")
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = mimeOf(uri.lastPathSegment.orEmpty())

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
        val f = try { resolve(uri) } catch (e: Exception) { return null }
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row = cols.map { if (it == OpenableColumns.DISPLAY_NAME) f.name else if (it == OpenableColumns.SIZE) f.length() else null }
        return MatrixCursor(cols).apply { addRow(row.toTypedArray()) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}
