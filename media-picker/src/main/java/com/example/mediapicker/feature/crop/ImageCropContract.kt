package com.example.mediapicker.feature.crop


import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.result.contract.ActivityResultContract
import java.io.Serializable

/**
 * 头像/图片裁剪与压缩策略配置
 * 实现 Parcelable，用于通过 Intent 跨进程/跨 Activity 安全传递
 */
data class CropConfig(
    /** 是否开启裁剪功能（true 时点图自动跳转裁剪页面） */
    val isEnabled: Boolean = false,

    /** 裁剪框宽度比例（默认 1） */
    val aspectX: Int = 1,

    /** 裁剪框高度比例（默认 1，即 1:1 方形头像） */
    val aspectY: Int = 1,

    /**
     * 裁剪后头像输出的最大物理像素边长（默认 1080px）
     * 即使原图是 8000x8000 巨幅照片，也会在切片后等比缩减至 1080 以内，杜绝浪费显存与存储
     */
    val maxOutputSize: Int = 1080,

    /**
     * 允许输出的最大文件体积（单位：字节，默认 300KB）
     * 底层压缩引擎会自动执行阶梯质量下探，确保最终产物控制在 300KB 以内，秒传服务器
     */
    val maxFileSizeBytes: Long = 300 * 1024L
) : Serializable

data class CropRequest(
    val rawUri: Uri,
    val cropConfig: CropConfig = CropConfig()
)

class ImageCropContract : ActivityResultContract<CropRequest, Uri?>() {

    override fun createIntent(context: Context, input: CropRequest): Intent {
        return Intent(context, ImageCropActivity::class.java).apply {
            putExtra(ImageCropActivity.EXTRA_RAW_URI, input.rawUri)
            putExtra(ImageCropActivity.EXTRA_CROP_CONFIG, input.cropConfig)
        }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
        if (resultCode != Activity.RESULT_OK || intent == null) return null

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(ImageCropActivity.RESULT_CROPPED_URI, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(ImageCropActivity.RESULT_CROPPED_URI)
        }
    }
}