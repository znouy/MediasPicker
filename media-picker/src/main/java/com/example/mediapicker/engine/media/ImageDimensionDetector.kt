package com.example.mediapicker.engine.media

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface

//大图安全加载辅助器
object ImageDimensionDetector {

    data class ImageSpec(
        val width: Int,
        val height: Int,
        val isHugeOrLongImage: Boolean,
        val orientation: Int,
        val isFormatSupportedForSubsampling: Boolean
    )

    /**
     * 仅读取图片尺寸元数据（耗时 < 1ms，0 内存消耗）
     * 判定是否需要启用 BitmapRegionDecoder 切片渲染
     */
    fun analyzeImage(context: Context, uri: Uri, mimeType: String): ImageSpec {
        // 1. 动图 GIF 严禁进入 SubsamplingScaleImageView，否则必死
        if (mimeType.contains("gif", ignoreCase = true)) {
            return ImageSpec(0, 0, false, ExifInterface.ORIENTATION_NORMAL, false)
        }

        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }

        var orientation = ExifInterface.ORIENTATION_NORMAL
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
            // 抓取 Exif 旋转标记
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }

        } catch (e: Exception) {
            e.printStackTrace()
            return ImageSpec(0, 0, false, ExifInterface.ORIENTATION_NORMAL, false)
        }

        val w = options.outWidth
        val h = options.outHeight

        if (w <= 0 || h <= 0) {
            return ImageSpec(0, 0, false, orientation, false)
        }

        // 根据 Exif 旋转决定视觉上的宽高
        val isSwapped =
            orientation == ExifInterface.ORIENTATION_ROTATE_90 || orientation == ExifInterface.ORIENTATION_ROTATE_270
        val visualW = if (isSwapped) h else w
        val visualH = if (isSwapped) w else h

        // 核心判定规则：
        // 1. 任意单边超过 4096 像素（超过大部分手机 GPU 渲染管线的 OpenGL 纹理尺寸）
        // 2. 长宽比超过 3:1（超长长图）
        val maxDimension = maxOf(visualW, visualH)
        val minDimension = minOf(visualW, visualH)
        val isLongImage = (maxDimension.toFloat() / minDimension.toFloat()) >= 2.6f
        val isHugeImage = maxDimension > 4096

        //仅对 JPEG/PNG 等传统格式启用 Subsampling，其他非常规格式回退给 Glide
        val isSupportedFormat = mimeType.contains("jpeg", true) || mimeType.contains(
            "jpg",
            true
        ) || mimeType.contains("png", true)
        return ImageSpec(
            width = visualW,
            height = visualH,
            orientation = orientation,
            isHugeOrLongImage = (isLongImage || isHugeImage) && isSupportedFormat,
            isFormatSupportedForSubsampling = isSupportedFormat
        )
    }
}