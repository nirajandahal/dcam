package com.dualview.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes finished captures into the phone's own media library, so they appear in Google
 * Photos or Gallery like any other recording — no share sheet, no Downloads folder.
 */
class CaptureStore(private val context: Context) {

    data class PendingVideo(
        val uri: Uri,
        val descriptor: ParcelFileDescriptor,
        val displayName: String
    )

    fun beginVideo(displayName: String): PendingVideo? {
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
            discardVideo(uri)
            return null
        }
        return PendingVideo(uri, descriptor, displayName)
    }

    fun publishVideo(uri: Uri) {
        val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
        try {
            context.contentResolver.update(uri, values, null, null)
        } catch (t: Throwable) {
            // Ignore.
        }
    }

    fun discardVideo(uri: Uri) {
        try {
            context.contentResolver.delete(uri, null, null)
        } catch (t: Throwable) {
            // Ignore.
        }
    }

    fun saveImage(bitmap: Bitmap, displayName: String): Uri? {
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
        } ?: return null

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
            try {
                context.contentResolver.delete(uri, null, null)
            } catch (t: Throwable) {
                // Ignore.
            }
            return null
        }

        val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
        try {
            context.contentResolver.update(uri, done, null, null)
        } catch (t: Throwable) {
            // Ignore.
        }
        return uri
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
