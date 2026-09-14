package com.dualview.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes finished captures either into the phone's media library (so they show up in
 * Gallery straight away) or into the app's own folder, when the person would rather decide
 * later from the Captures screen.
 */
class CaptureStore(private val context: Context) {

    data class PendingVideo(
        val uri: Uri?,
        val file: File?,
        val descriptor: ParcelFileDescriptor,
        val displayName: String
    )

    fun beginVideo(displayName: String, toGallery: Boolean): PendingVideo? =
        if (toGallery) beginGalleryVideo(displayName) else beginPrivateVideo(displayName)

    private fun beginGalleryVideo(displayName: String): PendingVideo? {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$ALBUM")
            put(MediaStore.Video.Media.IS_PENDING, 1)
            put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = try {
            context.contentResolver.insert(collection, values)
        } catch (t: Throwable) {
            null
        } ?: return null

        val descriptor = try {
            context.contentResolver.openFileDescriptor(uri, "rw")
        } catch (t: Throwable) {
            null
        }
        if (descriptor == null) {
            deleteUri(uri)
            return null
        }
        return PendingVideo(uri, null, descriptor, displayName)
    }

    private fun beginPrivateVideo(displayName: String): PendingVideo? {
        val dir = privateDir(Environment.DIRECTORY_MOVIES) ?: return null
        val file = File(dir, displayName)
        val descriptor = try {
            ParcelFileDescriptor.open(
                file,
                ParcelFileDescriptor.MODE_READ_WRITE or
                        ParcelFileDescriptor.MODE_CREATE or
                        ParcelFileDescriptor.MODE_TRUNCATE
            )
        } catch (t: Throwable) {
            null
        } ?: return null
        return PendingVideo(null, file, descriptor, displayName)
    }

    fun publishVideo(pending: PendingVideo) {
        val uri = pending.uri ?: return
        val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
        try {
            context.contentResolver.update(uri, values, null, null)
        } catch (t: Throwable) {
            // Ignore.
        }
    }

    fun discardVideo(pending: PendingVideo) {
        pending.uri?.let { deleteUri(it) }
        pending.file?.let {
            try {
                it.delete()
            } catch (t: Throwable) {
                // Ignore.
            }
        }
    }

    private fun deleteUri(uri: Uri) {
        try {
            context.contentResolver.delete(uri, null, null)
        } catch (t: Throwable) {
            // Ignore.
        }
    }

    fun saveImage(bitmap: Bitmap, displayName: String, toGallery: Boolean): Boolean =
        if (toGallery) saveGalleryImage(bitmap, displayName)
        else savePrivateImage(bitmap, displayName)

    private fun saveGalleryImage(bitmap: Bitmap, displayName: String): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
            put(MediaStore.Images.Media.IS_PENDING, 1)
            put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = try {
            context.contentResolver.insert(collection, values)
        } catch (t: Throwable) {
            null
        } ?: return false

        var stream: OutputStream? = null
        val ok = try {
            stream = context.contentResolver.openOutputStream(uri)
            stream != null && bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
        } catch (t: Throwable) {
            false
        } finally {
            try {
                stream?.close()
            } catch (t: Throwable) {
                // Ignore.
            }
        }

        if (!ok) {
            deleteUri(uri)
            return false
        }
        val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
        try {
            context.contentResolver.update(uri, done, null, null)
        } catch (t: Throwable) {
            // Ignore.
        }
        return true
    }

    private fun savePrivateImage(bitmap: Bitmap, displayName: String): Boolean {
        val dir = privateDir(Environment.DIRECTORY_PICTURES) ?: return false
        val file = File(dir, displayName)
        return try {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        } catch (t: Throwable) {
            false
        }
    }

    /** Copies an app-held capture into the gallery, used by the Captures screen. */
    fun exportToGallery(file: File): Boolean {
        val isVideo = file.name.endsWith(".mp4", ignoreCase = true)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, if (isVideo) "video/mp4" else "image/jpeg")
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                if (isVideo) "${Environment.DIRECTORY_MOVIES}/$ALBUM"
                else "${Environment.DIRECTORY_PICTURES}/$ALBUM"
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = if (isVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val uri = try {
            context.contentResolver.insert(collection, values)
        } catch (t: Throwable) {
            null
        } ?: return false

        val ok = try {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
            } != null
        } catch (t: Throwable) {
            false
        }
        if (!ok) {
            deleteUri(uri)
            return false
        }
        val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        try {
            context.contentResolver.update(uri, done, null, null)
        } catch (t: Throwable) {
            // Ignore.
        }
        return true
    }

    fun privateDir(kind: String): File? {
        val dir = context.getExternalFilesDir(kind) ?: return null
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun privateCaptures(): List<File> {
        val result = ArrayList<File>()
        for (kind in listOf(Environment.DIRECTORY_MOVIES, Environment.DIRECTORY_PICTURES)) {
            privateDir(kind)?.listFiles()?.let { files ->
                result.addAll(files.filter { it.isFile && it.length() > 0 })
            }
        }
        return result.sortedByDescending { it.lastModified() }
    }

    companion object {
        const val ALBUM = "DualView"

        fun timestamp(): String =
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

        fun nameFor(prefix: String, format: OutputFormat, stamp: String, extension: String): String {
            val tag = if (format == OutputFormat.VERTICAL) "9x16" else "16x9"
            return "${prefix}_${stamp}_$tag.$extension"
        }
    }
}
