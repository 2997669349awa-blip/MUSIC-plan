package com.example.filemanager

import android.content.Context
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

/**
 * 支持缩放和拖拽的ImageView
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val matrix = Matrix()
    private var mode = NONE
    private var oldDist = 1f
    private var lastX = 0f
    private var lastY = 0f
    private var startX = 0f
    private var startY = 0f
    private var scale = 1f
    private var minScale = 0.5f
    private var maxScale = 5f

    private var savedMatrix = Matrix()
    private var originalScale = 1f

    companion object {
        private const val NONE = 0
        private const val DRAG = 1
        private const val ZOOM = 2
    }

    private val scaleDetector: ScaleGestureDetector

    init {
        scaleType = ScaleType.MATRIX
        scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scale *= detector.scaleFactor
                scale = scale.coerceIn(minScale, maxScale)
                matrix.set(savedMatrix)
                matrix.postScale(scale / originalScale, scale / originalScale, detector.focusX, detector.focusY)
                imageMatrix = matrix
                return true
            }
        })
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                savedMatrix.set(matrix)
                startX = event.x
                startY = event.y
                mode = DRAG
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                oldDist = spacing(event)
                if (oldDist > 10f) {
                    savedMatrix.set(matrix)
                    mode = ZOOM
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == DRAG) {
                    matrix.set(savedMatrix)
                    val dx = event.x - startX
                    val dy = event.y - startY
                    matrix.postTranslate(dx, dy)
                    imageMatrix = matrix
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                mode = NONE
            }
        }

        return true
    }

    private fun spacing(event: MotionEvent): Float {
        val x = event.getX(0) - event.getX(1)
        val y = event.getY(0) - event.getY(1)
        return kotlin.math.sqrt(x * x + y * y)
    }

    override fun setImageDrawable(drawable: android.graphics.drawable.Drawable?) {
        super.setImageDrawable(drawable)
        if (drawable != null) {
            originalScale = 1f
            scale = 1f
            matrix.reset()
            // 居中显示
            val viewWidth = width.toFloat()
            val viewHeight = height.toFloat()
            val drawableWidth = drawable.intrinsicWidth.toFloat()
            val drawableHeight = drawable.intrinsicHeight.toFloat()
            if (viewWidth > 0 && viewHeight > 0 && drawableWidth > 0 && drawableHeight > 0) {
                val scale = minOf(viewWidth / drawableWidth, viewHeight / drawableHeight)
                originalScale = scale
                this.scale = scale
                matrix.setScale(scale, scale)
                val dx = (viewWidth - drawableWidth * scale) / 2f
                val dy = (viewHeight - drawableHeight * scale) / 2f
                matrix.postTranslate(dx, dy)
                imageMatrix = matrix
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        drawable?.let {
            setImageDrawable(it)
        }
    }
}
