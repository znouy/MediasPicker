package com.example.mediapicker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract
import com.example.mediapicker.feature.crop.CropConfig
import com.example.mediapicker.feature.picker.AlbumPickerActivity
import java.io.Serializable


//data class CropConfig(
//    val isEnabled: Boolean = false,
//    val aspectX: Int = 1,
//    val aspectY: Int = 1,
//
//    /** 🚨 裁剪后头像的最大物理像素尺寸（微信标准为 1080px，既保真又防撑大体积） */
//    val maxOutputSize: Int = 1080,
//
//    /** 🚨 允许上传的最大文件体积（单位：KB，默认压至 300KB 以内） */
//    val maxFileSizeBytes: Long = 300 * 1024L
//) : Serializable

/**
 * 相册配置载体（支持链式配置）
 */
data class MediaPickerConfig(
    val maxSelectCount: Int = 1,
    val isShowHud: Boolean = false, // 是否开启性能 HUD 面板，方便宿主调试
    // true -> 纯单选模式（无角标，点图片直接返回）
    // false -> 多选模式（有角标、有完成按钮，即使 maxSelectCount=1 也是多选交互）
    val isSingleMode: Boolean = false,
    val cropConfig: CropConfig = CropConfig()
) : Serializable

/**
 * 官方 ActivityResultContract 契约封装
 * 彻底消灭宿主手写 onActivityResult 和 requestCode！
 */
class MediaPickerContract : ActivityResultContract<MediaPickerConfig, List<Uri>>() {

    override fun createIntent(context: Context, input: MediaPickerConfig): Intent {
        return Intent(context, AlbumPickerActivity::class.java).apply {
            putExtra(EXTRA_CONFIG, input)
        }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
        if (resultCode != Activity.RESULT_OK || intent == null) {
            return emptyList()
        }
        return intent.getParcelableArrayListExtra<Uri>(EXTRA_SELECTED_MEDIA) ?: emptyList()
    }

    companion object {
        const val EXTRA_CONFIG = "mpc_extra_config"
        const val EXTRA_SELECTED_MEDIA = "mpc_extra_selected_media"
    }
}

