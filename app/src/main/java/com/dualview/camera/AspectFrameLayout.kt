package com.dualview.camera

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * Holds the preview at the camera frame's own shape.
 *
 * Doing this at measure time means the preview is the right shape on its very first frame.
 * The previous approach resized it by hand after layout, which could lose a race and leave
 * the preview stretched until something else triggered a fresh layout pass.
 */
class AspectFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** Width divided by height. Zero means "behave like a normal FrameLayout". */
    var aspectRatio: Float = 0f
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
        val availableHeight = MeasureSpec.getSize(heightMeasureSpec)

        if (aspectRatio <= 0f || availableWidth <= 0 || availableHeight <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        var width = availableWidth
        var height = (width / aspectRatio).toInt()
        if (height > availableHeight) {
            height = availableHeight
            width = (height * aspectRatio).toInt()
        }

        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
    }
}
