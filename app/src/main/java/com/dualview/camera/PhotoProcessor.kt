package com.dualview.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import com.dualview.camera.gl.TextureProgram
import java.io.ByteArrayInputStream

/**
 * Turns one full-resolution sensor JPEG into the requested crops.
 *
 * Cropping straight out of the JPEG with a region decoder means a 12-megapixel photo never
 * has to exist in memory all at once, which matters on phones with less RAM.
 */
object PhotoProcessor {

    fun process(
        context: Context,
        jpeg: ByteArray,
        imageWidth: Int,
        imageHeight: Int,
        rotation: Int,
        mirror: Boolean,
        formats: List<OutputFormat>,
        look: Int,
        toGallery: Boolean
    ): List<OutputFormat> {

        val decoder = try {
            BitmapRegionDecoder.newInstance(ByteArrayInputStream(jpeg), false)
        } catch (t: Throwable) {
            null
        } ?: return emptyList()

        val sensorWidth = if (decoder.width > 0) decoder.width else imageWidth
        val sensorHeight = if (decoder.height > 0) decoder.height else imageHeight
        val quarterTurn = rotation % 180 != 0
        val uprightWidth = if (quarterTurn) sensorHeight else sensorWidth
        val uprightHeight = if (quarterTurn) sensorWidth else sensorHeight

        val store = CaptureStore(context)
        val stamp = CaptureStore.timestamp()
        val results = ArrayList<OutputFormat>(formats.size)

        for (format in formats) {
            val crop = Planner.cropSize(format, uprightWidth, uprightHeight)
            // Translate the upright crop back into the sensor's own orientation.
            val cropSensorWidth = if (quarterTurn) crop.height else crop.width
            val cropSensorHeight = if (quarterTurn) crop.width else crop.height
            val left = ((sensorWidth - cropSensorWidth) / 2).coerceAtLeast(0)
            val top = ((sensorHeight - cropSensorHeight) / 2).coerceAtLeast(0)
            val region = Rect(
                left, top,
                (left + cropSensorWidth).coerceAtMost(sensorWidth),
                (top + cropSensorHeight).coerceAtMost(sensorHeight)
            )

            var bitmap = try {
                decoder.decodeRegion(region, null)
            } catch (t: Throwable) {
                null
            } ?: continue

            bitmap = orient(bitmap, rotation, mirror)
            bitmap = applyLook(bitmap, look)

            val name = CaptureStore.nameFor("DualView", format, stamp, "jpg")
            val ok = store.saveImage(bitmap, name, toGallery)
            bitmap.recycle()
            if (ok) results.add(format)
        }

        try {
            decoder.recycle()
        } catch (t: Throwable) {
            // Ignore.
        }
        return results
    }

    private fun orient(source: Bitmap, rotation: Int, mirror: Boolean): Bitmap {
        if (rotation == 0 && !mirror) return source
        val matrix = Matrix()
        matrix.postRotate(rotation.toFloat())
        if (mirror) matrix.postScale(-1f, 1f)
        return try {
            val rotated = Bitmap.createBitmap(
                source, 0, 0, source.width, source.height, matrix, true
            )
            if (rotated != source) source.recycle()
            rotated
        } catch (t: Throwable) {
            source
        }
    }

    private fun applyLook(source: Bitmap, look: Int): Bitmap {
        if (look == TextureProgram.LOOK_NATURAL) return source
        val matrix = ColorMatrix()
        when (look) {
            TextureProgram.LOOK_MONO -> matrix.setSaturation(0f)
            TextureProgram.LOOK_WARM -> matrix.set(
                floatArrayOf(
                    1.10f, 0f, 0f, 0f, 5f,
                    0f, 1.02f, 0f, 0f, 0f,
                    0f, 0f, 0.90f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            TextureProgram.LOOK_VIVID -> {
                matrix.setSaturation(1.45f)
                matrix.postConcat(
                    ColorMatrix(
                        floatArrayOf(
                            1.04f, 0f, 0f, 0f, 0f,
                            0f, 1.04f, 0f, 0f, 0f,
                            0f, 0f, 1.04f, 0f, 0f,
                            0f, 0f, 0f, 1f, 0f
                        )
                    )
                )
            }
        }
        return try {
            val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(matrix)
            }
            canvas.drawBitmap(source, 0f, 0f, paint)
            source.recycle()
            output
        } catch (t: Throwable) {
            source
        }
    }
}
