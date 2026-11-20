package com.example.mediapicker.engine.crop

import android.graphics.Bitmap
import android.graphics.Matrix
import com.example.mediapicker.feature.crop.model.CropRegion


/**
 * 图像物理切片与空间矩阵处理器
 *
 * 负责根据物理像素坐标 (Region) 进行位图截取与尺寸微调
 */
object BitmapCropProcessor {

    /**
     * 头像专用高精度切片与等比缩放
     */
    fun crop(
        srcBitmap: Bitmap,
        region: CropRegion,
        maxDimension: Int = 1080
    ): Bitmap {
        // 1. 如果切片尺寸大于 1080，计算缩放比
        val targetScale = if (region.width > maxDimension) {
            maxDimension.toFloat() / region.width.toFloat()
        } else {
            1.0f
        }

        // 2. 仅在确实需要缩小时构建 Matrix，否则传 null 提升底层执行效率
        val matrix = if (targetScale < 1.0f) {
            Matrix().apply { postScale(targetScale, targetScale) }
        } else {
            null
        }

        // 3. 一步到位完成物理切片与尺寸缩减
        return Bitmap.createBitmap(
            srcBitmap,
            region.srcX,
            region.srcY,
            region.width,
            region.height,
            matrix,
            true
        )
    }
}