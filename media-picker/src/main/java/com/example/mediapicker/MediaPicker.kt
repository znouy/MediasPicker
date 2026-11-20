package com.example.mediapicker

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.fragment.app.Fragment
import com.example.mediapicker.feature.crop.CropConfig


/**
 * 宿主调用的唯一门面入口类
 */
class MediaPicker private constructor() {

    class Builder internal constructor(
        private val launcher: ActivityResultLauncher<MediaPickerConfig>,
        private val isSingleMode: Boolean,
        private val maxSelectCount: Int
    ) {
        private var showHud: Boolean = false
        // 裁剪配置（默认关闭）
        private var cropConfig: CropConfig = CropConfig()
        /**
         *  开启 1:1 头像/图片裁剪
         * @param aspectX 裁剪宽比例（默认 1）
         * @param aspectY 裁剪高比例（默认 1）
         */
        fun crop(aspectX: Int = 1, aspectY: Int = 1) = apply {
            this.cropConfig = CropConfig(
                isEnabled = true,
                aspectX = aspectX,
                aspectY = aspectY
            )
        }
        /**
         * 自定义裁剪策略（支持设置最大输出分辨率与文件体积限制）
         */
        fun crop(config: CropConfig) = apply {
            this.cropConfig = config
        }

        fun showDebugHud(show: Boolean) = apply { this.showHud = show }

        fun launch() {
            launcher.launch(
                MediaPickerConfig(
                    if (isSingleMode) 1 else maxSelectCount,
                    showHud,
                    isSingleMode,
                    cropConfig
                )
            )
        }
    }

    companion object {
        /**
         *  单选入口（选头像/封面）
         * 交互：无角标，点击任意图片直接关闭页面并返回
         */
        fun pickSingle(launcher: ActivityResultLauncher<MediaPickerConfig>): Builder {
            return Builder(launcher, true, 1)
        }

        /**
         *  多选入口（发朋友圈、请假凭证）
         * 交互：无角标，点击任意图片直接关闭页面并返回
         */
        fun pickMulti(
            launcher: ActivityResultLauncher<MediaPickerConfig>,
            maxCount: Int
        ): Builder {
            return Builder(launcher, false, maxCount)
        }

        /**
         * 在 Activity 中快速注册契约
         */
        fun register(
            activity: ComponentActivity,
            callback: (List<Uri>) -> Unit
        ): ActivityResultLauncher<MediaPickerConfig> {
            return activity.registerForActivityResult(MediaPickerContract(), callback)
        }

        /**
         * 在 Fragment 中快速注册契约
         */
        fun register(
            fragment: Fragment,
            callback: (List<Uri>) -> Unit
        ): ActivityResultLauncher<MediaPickerConfig> {
            return fragment.registerForActivityResult(MediaPickerContract(), callback)
        }
    }
}

