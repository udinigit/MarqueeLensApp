package com.marqueelens

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/** Hands a file the app wrote (a list or backup) to WhatsApp, Gmail and other apps when you share it. */
class FileShare : ContentProvider() {
    override fun onCreate() = true

    private fun fileOf(uri: Uri): File? {
        val dir = dir(context ?: return null)
        val f = File(dir, uri.lastPathSegment ?: return null)
        return f.takeIf { it.canonicalPath.startsWith(dir.canonicalPath) && it.exists() }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? =
        fileOf(uri)?.let { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) }

    override fun getType(uri: Uri): String = when (uri.lastPathSegment?.substringAfterLast('.')?.lowercase()) {
        "json" -> "application/json"; "csv" -> "text/csv"; else -> "text/plain"
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val f = fileOf(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols).apply {
            if (f != null) addRow(cols.map { if (it == OpenableColumns.SIZE) f.length() else if (it == OpenableColumns.DISPLAY_NAME) f.name else null }.toTypedArray())
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0

    companion object {
        fun dir(ctx: Context) = File(ctx.cacheDir, "share").apply { mkdirs() }

        /** Writes the text to a shareable file and returns its address. */
        fun write(ctx: Context, name: String, text: String): Uri {
            val safe = name.replace(Regex("[^\\p{L}\\p{N}._-]+"), "-").take(80).ifBlank { "marquee-lens.json" }
            dir(ctx).listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000 }?.forEach { it.delete() }
            File(dir(ctx), safe).writeText(text)
            return Uri.parse("content://${ctx.packageName}.files/$safe")
        }
    }
}
