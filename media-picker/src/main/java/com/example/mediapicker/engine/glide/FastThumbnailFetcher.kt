package com.example.mediapicker.engine.glide

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.provider.MediaStore
import android.util.Size
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.data.DataFetcher

// Fetcher-拦截 Glide，强行从系统底层抽取 1ms 的预制缩略图！
class FastThumbnailFetcher(
    private val contentResolver: ContentResolver,
    private val uri: Uri,
    private val width: Int,
    private val height: Int
) : DataFetcher<Bitmap> {

    private var cancellationSignal: CancellationSignal? = null

    override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in Bitmap>) {
        try {
            cancellationSignal = CancellationSignal()
            var bitmap: Bitmap? = null

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+：直接调用系统硬件级 loadThumbnail，耗时 1~2ms，零原图 IO
                // 安全尺寸保护：宽高不能 <= 0
                val targetW = if (width > 0) width else 256
                val targetH = if (height > 0) height else 256
                bitmap = contentResolver.loadThumbnail(
                    uri,
                    Size(targetW, targetH), cancellationSignal
                )
            } else {
                // Android 9 及以下：必须区分视频通道与图片通道-查询系统预生成的 MINI_KIND (512x384) 缩略图
                val id = uri.lastPathSegment?.toLongOrNull()
                if (id != null && id > 0) {
                    val uriString = uri.toString()
                    val isVideo = uriString.contains("video", ignoreCase = true)

                    bitmap = if (isVideo) {
                        // ：Android 9 以下视频专属缩略图通道！
                        MediaStore.Video.Thumbnails.getThumbnail(
                            contentResolver,
                            id,
                            MediaStore.Video.Thumbnails.MINI_KIND,
                            null
                        )
                    } else {
                        // Android 9 以下图片专属缩略图通道
                        MediaStore.Images.Thumbnails.getThumbnail(
                            contentResolver,
                            id,
                            MediaStore.Images.Thumbnails.MINI_KIND,
                            null
                        )
                    }
                }
            }

            if (bitmap != null) {
                // 系统有缩略图，1ms 瞬间直出
                callback.onDataReady(bitmap)
            } else {
                // 抛出异常，Glide 会自动流转到下一个常规的 Loader（原图读取）继续尝试
                // 因为 Model 是标准的 Uri，Glide 收到异常后，会自动掉落到官方内置的解码链（Downsampler）
                callback.onLoadFailed(IllegalArgumentException("No system thumbnail for URI: $uri"))
            }
        } catch (e: Exception) {
            // 系统异常也直接告知 Glide 官方内置的解码链
            callback.onLoadFailed(e)
        }
    }

    override fun cleanup() {
        // Bitmap 交给 Glide 统一通过 BitmapPool 管理复用
    }

    override fun cancel() {
        // 滑动离开视野，直接在内核层阻断硬件缩略图生成
        cancellationSignal?.cancel()
    }

    override fun getDataClass(): Class<Bitmap> = Bitmap::class.java
    override fun getDataSource(): DataSource = DataSource.LOCAL
}