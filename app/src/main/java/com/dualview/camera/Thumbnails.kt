package com.dualview.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import java.io.File

/** Small preview images for the Captures list, decoded well below full size. */
object Thumbnails {

    fun load(file: File): Bitmap? =
        if (file.name.endsWith(".mp4", true)) videoFrame(file) else photo(file)

    private fun photo(file: File): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
        }
        BitmapFactory.decodeFile(file.absolutePath, options)
    } catch (t: Throwable) {
        null
    }

    private fun videoFrame(file: File): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.getFrameAtTime(0)
        } catch (t: Throwable) {
            null
        } finally {
            try {
                retriever.release()
            } catch (t: Throwable) {
                // Ignore.
            }
        }
    }

    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        var longest = maxOf(width, height)
        while (longest > 480) {
            sample *= 2
            longest /= 2
        }
        return sample
    }
}
