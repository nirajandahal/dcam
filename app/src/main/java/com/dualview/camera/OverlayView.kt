package com.dualview.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * The guide frames drawn over the preview. Amber marks what the 9:16 file will contain,
 * blue marks the 16:9 file, and everything outside both is dimmed — so what you see is
 * exactly what gets saved.
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * resources.displayMetrics.density
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * resources.displayMetrics.density
        color = Color.argb(90, 255, 255, 255)
    }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 11f * resources.displayMetrics.scaledDensity
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private val verticalRect = RectF()
    private val horizontalRect = RectF()
    private val outsidePath = Path()
    private val rectPath = Path()

    var formatMode: FormatMode = FormatMode.BOTH
        set(value) {
            field = value
            invalidate()
        }

    var showGrid: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val formats = Planner.formatsFor(formatMode)
        val showVertical = formats.contains(OutputFormat.VERTICAL)
        val showHorizontal = formats.contains(OutputFormat.HORIZONTAL)

        if (showVertical) {
            val crop = Planner.cropSize(OutputFormat.VERTICAL, width, height)
            val cw = crop.width.toFloat()
            val ch = crop.height.toFloat()
            verticalRect.set((w - cw) / 2f, (h - ch) / 2f, (w + cw) / 2f, (h + ch) / 2f)
        }
        if (showHorizontal) {
            val crop = Planner.cropSize(OutputFormat.HORIZONTAL, width, height)
            val cw = crop.width.toFloat()
            val ch = crop.height.toFloat()
            horizontalRect.set((w - cw) / 2f, (h - ch) / 2f, (w + cw) / 2f, (h + ch) / 2f)
        }

        // Dim everything that no active format will keep.
        outsidePath.reset()
        outsidePath.addRect(0f, 0f, w, h, Path.Direction.CW)
        if (showVertical) {
            rectPath.reset()
            rectPath.addRect(verticalRect, Path.Direction.CW)
            outsidePath.op(rectPath, Path.Op.DIFFERENCE)
        }
        if (showHorizontal) {
            rectPath.reset()
            rectPath.addRect(horizontalRect, Path.Direction.CW)
            outsidePath.op(rectPath, Path.Op.DIFFERENCE)
        }
        canvas.drawPath(outsidePath, dimPaint)

        if (showGrid) drawGrid(canvas, w, h)

        if (showHorizontal) {
            framePaint.color = HORIZONTAL_COLOR
            canvas.drawRect(horizontalRect, framePaint)
            drawLabel(canvas, "16:9", horizontalRect.left, horizontalRect.top, HORIZONTAL_COLOR)
        }
        if (showVertical) {
            framePaint.color = VERTICAL_COLOR
            canvas.drawRect(verticalRect, framePaint)
            drawLabel(canvas, "9:16", verticalRect.left, verticalRect.top, VERTICAL_COLOR)
        }
    }

    private fun drawGrid(canvas: Canvas, w: Float, h: Float) {
        for (i in 1..2) {
            val x = w * i / 3f
            canvas.drawLine(x, 0f, x, h, gridPaint)
            val y = h * i / 3f
            canvas.drawLine(0f, y, w, y, gridPaint)
        }
    }

    private fun drawLabel(canvas: Canvas, text: String, left: Float, top: Float, color: Int) {
        val padH = 6f * resources.displayMetrics.density
        val padV = 3f * resources.displayMetrics.density
        val textWidth = labelTextPaint.measureText(text)
        val metrics = labelTextPaint.fontMetrics
        val textHeight = metrics.descent - metrics.ascent
        val boxLeft = left + 4f * resources.displayMetrics.density
        val boxTop = top + 4f * resources.displayMetrics.density
        val boxRight = boxLeft + textWidth + padH * 2
        val boxBottom = boxTop + textHeight + padV * 2
        labelBgPaint.color = color
        val radius = 4f * resources.displayMetrics.density
        canvas.drawRoundRect(boxLeft, boxTop, boxRight, boxBottom, radius, radius, labelBgPaint)
        canvas.drawText(text, boxLeft + padH, boxBottom - padV - metrics.descent, labelTextPaint)
    }

    companion object {
        private const val VERTICAL_COLOR = 0xFFF5A524.toInt()
        private const val HORIZONTAL_COLOR = 0xFF4DA3FF.toInt()
    }
}
