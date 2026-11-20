package com.example.mediapicker.engine.glide

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.bumptech.glide.signature.ObjectKey

class FastThumbnailModelLoader(
    private val context: Context
) : ModelLoader<Uri, Bitmap> {

    override fun buildLoadData(
        model: Uri,
        width: Int,
        height: Int,
        options: Options
    ): ModelLoader.LoadData<Bitmap>? {
        // 如果没带开关（比如大图预览页面），直接返回 null！
        // Glide 看到 null，会立刻放行给官方默认通道，绝不拦截大图！
        val isFastEnabled = options.get(FastThumbOptions.ENABLE_FAST_THUMBNAIL) ?: false
        if (!isFastEnabled) return null

        //相册网格时走这个 系统缩略图通道
        return ModelLoader.LoadData(
            ObjectKey(model),
            FastThumbnailFetcher(context.contentResolver, model, width, height)
        )
    }

    override fun handles(model: Uri): Boolean {
        // 只拦截系统外置存储媒体库的 content:// 协议
        return model.scheme == ContentResolver.SCHEME_CONTENT && model.authority == MediaStore.AUTHORITY
    }

    class Factory(private val appContext: Context) : ModelLoaderFactory<Uri, Bitmap> {
        override fun build(multiFactory: MultiModelLoaderFactory): ModelLoader<Uri, Bitmap> {
            return FastThumbnailModelLoader(appContext)
        }

        override fun teardown() {}
    }
}