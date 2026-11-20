package com.example.mediapicker.engine.glide

import com.bumptech.glide.load.Option

/**
 * 加载系统缩略图开关
 * */
object FastThumbOptions {
    // 只有相册网格才开这个开关；大图预览不开，完全各走各的
    val ENABLE_FAST_THUMBNAIL: Option<Boolean> = Option.memory(
        "com.example.mediapicker.ENABLE_FAST_THUMBNAIL",
        false
    )
}