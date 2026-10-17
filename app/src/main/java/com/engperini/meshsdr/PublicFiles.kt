package com.engperini.meshsdr

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Files the user must be able to open: IQ recordings and exported logs.
 * Android 10+ writes them to Download/MeshSDR through MediaStore (no
 * permission needed). Older versions keep the app folder, which file managers
 * there can still browse.
 */
object PublicFiles {
    private const val FOLDER = "MeshSDR"

    class Target(val stream: OutputStream, val location: String, private val finish: () -> Unit) : Closeable {
        override fun close() {
            try { stream.close() } finally { finish() }
        }
    }

    fun create(context: Context, name: String, mime: String): Target {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore recusou criar $name")
            val stream = resolver.openOutputStream(uri)
                ?: throw IllegalStateException("Não foi possível abrir $name")
            return Target(stream, "Download/$FOLDER/$name") {
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
        }
        val file = File(context.getExternalFilesDir(null) ?: context.filesDir, name)
        return Target(FileOutputStream(file), file.absolutePath) {}
    }

}
