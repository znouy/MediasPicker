package com.example.mediapicker.engine.glide

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.bumptech.glide.Glide
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.module.LibraryGlideModule
import kotlin.jvm.java

//  核心：把 1ms 系统缩略图加载器插进 Glide 的首要执行管道
// 1. 使用 AppGlideModule 在应用启动或初始化时完成全局单例注册，杜绝 Activity 内存泄漏
//作为 Library 发布时，严禁使用 AppGlideModule
@GlideModule
class FastThumbnailGlideModule : LibraryGlideModule() {
    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        registry.prepend(
            Uri::class.java,
            Bitmap::class.java,
            FastThumbnailModelLoader.Factory(context.applicationContext)
        )
    }
}