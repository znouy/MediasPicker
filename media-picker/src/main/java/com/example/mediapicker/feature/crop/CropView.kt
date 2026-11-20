package com.example.mediapicker.feature.crop

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.example.mediapicker.feature.crop.model.CropRegion
import kotlin.math.max
import androidx.core.graphics.toColorInt

/**
 * 头像专属 1:1 裁剪控件
 * 1. 严格还原微信头像“移动和缩放”
 * 2. 具备完整的橡皮筋抗阻尼手感（允许捏小至框内、拉出黑边）
 * 3. 松手平滑减速回弹吸附（Spring-Back Animation）
 * 4. 零额外内存分配，精准输出物理像素 CropRegion
 */
class CropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var sourceBitmap: Bitmap? = null
    private val imageMatrix = Matrix()
    private val matrixValues = FloatArray(9)

    // 1:1 裁剪视口矩形
    val cropRect = RectF()

    // 微信同款 70% 暗黑镂空蒙层
    private val maskPaint = Paint().apply {
        color = "#B3000000".toColorInt()
        style = Paint.Style.FILL
    }

    // 1:1 白色外边框细线
    private val borderPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
        isAntiAlias = true
    }

    // 缩放物理极值
    private var minScale = 1.0f           // 合法硬底线：图片刚好填满裁剪框
    private var maxScale = 4.0f           // 合法硬上限：最多放大 4 倍
    private val overMinScaleFactor = 0.6f // 软下限：手势期间允许捏小至 minScale 的 60%
    private val overMaxScaleFactor = 1.25f // 软上限：手势期间允许放大至 maxScale 的 125%

    // 阻尼系数（越拉越紧）
    private val DAMPING_FACTOR = 0.35f

    // 回弹动画器
    private var springBackAnimator: ValueAnimator? = null

    // 1. 双指缩放监听器（带缩放阻尼）
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            var factor = detector.scaleFactor
            val currentScale = getMatrixScale()

            // 当尺寸小于 minScale 还在缩小，或大于 maxScale 还在放大时施加阻尼
            if (currentScale < minScale && factor < 1.0f) {
                factor = 1.0f - (1.0f - factor) * DAMPING_FACTOR
                if (currentScale * factor < minScale * overMinScaleFactor) {
                    factor = 1.0f // 触达软下限，卡住
                }
            } else if (currentScale > maxScale && factor > 1.0f) {
                factor = 1.0f + (factor - 1.0f) * DAMPING_FACTOR
                if (currentScale * factor > maxScale * overMaxScaleFactor) {
                    factor = 1.0f // 触达软上限，卡住
                }
            }

            imageMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
            invalidate()
            return true
        }
    })

    // 2. 单指平移手势监听器（带越界位移阻尼）
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            val bmp = sourceBitmap ?: return false
            val bounds = RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
            imageMatrix.mapRect(bounds)

            var dx = -distanceX
            var dy = -distanceY

            // 边缘拉入裁剪框内部露出黑底时，衰减位移
            if (bounds.left > cropRect.left && dx > 0) dx *= DAMPING_FACTOR
            if (bounds.right < cropRect.right && dx < 0) dx *= DAMPING_FACTOR
            if (bounds.top > cropRect.top && dy > 0) dy *= DAMPING_FACTOR
            if (bounds.bottom < cropRect.bottom && dy < 0) dy *= DAMPING_FACTOR

            imageMatrix.postTranslate(dx, dy)
            invalidate()
            return true
        }
    })

    fun setImageBitmap(bitmap: Bitmap) {
        this.sourceBitmap = bitmap
        // 长图防爆显存保护：长边 > 4096 自动降级为软件图层
        if (bitmap.width > 4096 || bitmap.height > 4096) {
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        } else {
            setLayerType(LAYER_TYPE_HARDWARE, null)
        }
        initImageAndCrop()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val padding = 16f * resources.displayMetrics.density
        val size = w - padding * 2
        val left = padding
        val top = (h - size) / 2f
        cropRect.set(left, top, left + size, top + size)
        initImageAndCrop()
    }

    private fun initImageAndCrop() {
        val bmp = sourceBitmap ?: return
        if (cropRect.width() <= 0) return

        imageMatrix.reset()
        // 短边刚好填满裁剪框的起始比例
        val scaleX = cropRect.width() / bmp.width.toFloat()
        val scaleY = cropRect.height() / bmp.height.toFloat()
        minScale = max(scaleX, scaleY)
        maxScale = minScale * 4f

        imageMatrix.postScale(minScale, minScale)

        val scaledW = bmp.width * minScale
        val scaledH = bmp.height * minScale
        val dx = cropRect.left + (cropRect.width() - scaledW) / 2f
        val dy = cropRect.top + (cropRect.height() - scaledH) / 2f
        imageMatrix.postTranslate(dx, dy)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                springBackAnimator?.cancel()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // 🚨 最后一根手指离开屏幕，启动平滑回弹归位动画！
                startSpringBackAnimation()
            }
        }

        var ret = scaleDetector.onTouchEvent(event)
        ret = gestureDetector.onTouchEvent(event) || ret
        return ret || super.onTouchEvent(event)
    }

    /**
     * 核心回弹引擎：计算最近的合法贴合状态，启动 260ms 减速平滑插值
     */
    private fun startSpringBackAnimation() {
        val bmp = sourceBitmap ?: return

        val currentScale = getMatrixScale()
        val targetScale = currentScale.coerceIn(minScale, maxScale)

        // 模拟推导目标矩阵
        val targetMatrix = Matrix(imageMatrix)
        if (targetScale != currentScale) {
            val scaleFactor = targetScale / currentScale
            targetMatrix.postScale(scaleFactor, scaleFactor, cropRect.centerX(), cropRect.centerY())
        }

        val targetBounds = RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
        targetMatrix.mapRect(targetBounds)

        var deltaX = 0f
        var deltaY = 0f

        // 水平回弹吸附计算
        if (targetBounds.width() >= cropRect.width()) {
            if (targetBounds.left > cropRect.left) {
                deltaX = cropRect.left - targetBounds.left
            } else if (targetBounds.right < cropRect.right) {
                deltaX = cropRect.right - targetBounds.right
            }
        } else {
            deltaX = cropRect.centerX() - targetBounds.centerX()
        }

        // 垂直回弹吸附计算
        if (targetBounds.height() >= cropRect.height()) {
            if (targetBounds.top > cropRect.top) {
                deltaY = cropRect.top - targetBounds.top
            } else if (targetBounds.bottom < cropRect.bottom) {
                deltaY = cropRect.bottom - targetBounds.bottom
            }
        } else {
            deltaY = cropRect.centerY() - targetBounds.centerY()
        }

        targetMatrix.postTranslate(deltaX, deltaY)

        // 矩阵插值过渡动画（260ms 微信同款减速曲线）
        val startValues = FloatArray(9)
        val endValues = FloatArray(9)
        imageMatrix.getValues(startValues)
        targetMatrix.getValues(endValues)

        springBackAnimator?.cancel()
        springBackAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260L
            interpolator = DecelerateInterpolator(2.0f)
            addUpdateListener { animation ->
                val fraction = animation.animatedFraction
                val currentValues = FloatArray(9)
                for (i in 0..8) {
                    currentValues[i] = startValues[i] + (endValues[i] - startValues[i]) * fraction
                }
                imageMatrix.setValues(currentValues)
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = sourceBitmap ?: return
        if (bmp.isRecycled) return

        // 1. 绘制底层变换的位图
        canvas.drawBitmap(bmp, imageMatrix, null)

        // 2. 绘制微信同款暗黑镂空蒙层
        canvas.save()
        val path = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            addRect(cropRect, Path.Direction.CW)
        }
        canvas.drawPath(path, maskPaint)
        canvas.restore()

        // 3. 仅绘制纯净的 1:1 白色外边框（无任何九宫格线条干扰）
        canvas.drawRect(cropRect, borderPaint)
    }

    private fun getMatrixScale(): Float {
        imageMatrix.getValues(matrixValues)
        return matrixValues[Matrix.MSCALE_X]
    }

    /**
     * 计算真实切片坐标（零内存分配，供 ViewModel 切片使用）
     */
    fun calculateCropRegion(): CropRegion? {
        val bmp = sourceBitmap ?: return null
        if (bmp.isRecycled) return null

        val currentBounds = RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
        imageMatrix.mapRect(currentBounds)

        val scale = currentBounds.width() / bmp.width.toFloat()
        if (scale <= 0f) return null

        val srcX = ((cropRect.left - currentBounds.left) / scale).toInt().coerceIn(0, bmp.width - 1)
        val srcY = ((cropRect.top - currentBounds.top) / scale).toInt().coerceIn(0, bmp.height - 1)
        val srcWidth = (cropRect.width() / scale).toInt().coerceIn(1, bmp.width - srcX)
        val srcHeight = (cropRect.height() / scale).toInt().coerceIn(1, bmp.height - srcY)

        return CropRegion(srcX, srcY, srcWidth, srcHeight)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        springBackAnimator?.cancel()
    }
}