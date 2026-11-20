package com.example.mediapicker.data.repository

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import com.example.mediapicker.data.model.AlbumFolder
import com.example.mediapicker.data.model.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AlbumRepository(private val context: Context) {

    data class AlbumScanResult(
        val allFolders: List<AlbumFolder>,
        val currentDisplayList: List<MediaItem>
    )

    /**
     * 异步查询系统相册媒体数据
     */
    suspend fun queryMediaImages(): AlbumScanResult = withContext(Dispatchers.IO) {
        val mediaList = mutableListOf<MediaItem>()
        // 单独收集全手机的“所有视频”
        val videoList = mutableListOf<MediaItem>()

        // 使用 LinkedHashMap 保证时序：最新发生改动的相册文件夹排在最前面
        val folderBucketMap = LinkedHashMap<Long, MutableList<MediaItem>>()
        val folderNameMap = HashMap<Long, String>()

        // 1. 只投射业务必需的核心字段，减少 SQLite 跨进程读取的数据量
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.BUCKET_ID,
            MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME,
            MediaStore.Files.FileColumns.DURATION,
            MediaStore.Files.FileColumns.MEDIA_TYPE
        )

        // 2. 核心排序策略
        // 优先以“最后修改时间”倒序（刚存/刚拍的图一定在最前面）
        // 若在同一秒内（如极速连拍），通过主键 _ID 倒序，保证时序严格递增稳定
        val sortOrder =
            "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC, ${MediaStore.Files.FileColumns._ID} DESC"

        // 3. 过滤条件：仅查询有效图片，排除 0 字节破损文件及未初始化完成的脏数据
//        val selection =
//            "${MediaStore.Files.FileColumns.SIZE} > 0 AND ${MediaStore.Files.FileColumns.MIME_TYPE} NOT NULL"
        // 过滤：只查有效图片和有效视频，排除 0 字节损坏文件
        val selection =
            "(${MediaStore.Files.FileColumns.MEDIA_TYPE} = ? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?) " +
                    "AND ${MediaStore.Files.FileColumns.SIZE} > 0"
        val selectionArgs = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
        )

        // 通用媒体外置 URI
        val queryUri = MediaStore.Files.getContentUri("external")
        try {
            // 4. 新旧系统 API 兼容分支处理
            val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Android 8.0+ (API 26+) 推荐：使用 Bundle 传参
                // 享受系统底层执行计划优化与分区存储（Scoped Storage）的原生红利
                val queryArgs = Bundle().apply {
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                    putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
                    putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder)
                    // 如果未来需要支持物理分页（如每次只查 50 张），可直接解开如下两行：
                    // putInt(ContentResolver.QUERY_ARG_LIMIT, 50)
                    // putInt(ContentResolver.QUERY_ARG_OFFSET, 0)
                }
                context.contentResolver.query(
                    queryUri, projection, queryArgs, null
                )
            } else {
                // Android 7.1 及以下老设备：使用传统安全 SQL 传参，防止低版本抛出 NoSuchMethodError
                context.contentResolver.query(
                    queryUri, projection, selection, null, sortOrder
                )
            }

            cursor?.use { c ->
                val idColumn = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val mimeColumn = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                val dateModifiedColumn =
                    c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                val bucketIdCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_ID)
                val bucketNameCol =
                    c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)
                val mediaTypeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
                val durationCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DURATION)

                while (c.moveToNext()) {
                    val mediaType = c.getInt(mediaTypeCol)
                    val duration = c.getLong(durationCol)
                    val id = c.getLong(idColumn)
                    // 真实读取文件的 MIME_TYPE（如 image/png, image/gif, image/webp 等均会原样保留）
                    val mime = c.getString(mimeColumn)
                        ?: if (mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) "video/mp4" else "image/jpeg"
                    val dateModified = c.getLong(dateModifiedColumn)
                    val bucketId = c.getLong(bucketIdCol)
                    val bucketName = c.getString(bucketNameCol) ?: "其他"
                    val isVideo = mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO

                    // 基于官方标准的外部存储 URI 拼接出当前图片独一无二的 Content Uri
                    // 供下游 Glide 和系统底层硬件级 loadThumbnail(uri) 高速调取

                    // 根据媒体类型构建精准对应的 Uri，供系统 loadThumbnail 硬件直出
                    val baseContentUri =
                        if (mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) {
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        } else {
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        }
                    val contentUri = ContentUris.withAppendedId(baseContentUri, id)

                    val item = MediaItem(
                        id = id,
                        uri = contentUri,
                        mimeType = mime,
                        dateModified = dateModified, // 统一以有效业务时间驱动 UI
                        bucketId = bucketId,
                        bucketName = bucketName,
                        duration = duration,
                    )
                    // 装进“图片和视频”集合
                    mediaList.add(item)

                    //  如果是视频，单独注入全视频集合（为“所有视频”虚拟文件夹服务）
                    if (isVideo) {
                        videoList.add(item)
                    }

                    // 内存归类：装载进对应的相册分组（0 额外 I/O）
                    var subList = folderBucketMap[bucketId]
                    if (subList == null) {
                        subList = mutableListOf()
                        folderBucketMap[bucketId] = subList
                        folderNameMap[bucketId] = bucketName
                    }
                    subList.add(item)
                }
            }
        } catch (e: Exception) {
            // 捕获权限被临时回收或 Provider 偶发中断异常，返回当前已收集的数据，防止页面 Crash
            e.printStackTrace()
        }

        // 构建全部分类 Folder 列表
        val resultFolders = mutableListOf<AlbumFolder>()
        //目录 1：所有图片和视频（默认全集）
        val allCoverUri = mediaList.firstOrNull()?.uri ?: Uri.EMPTY
        resultFolders.add(
            AlbumFolder(
                bucketId = BUCKET_ID_ALL_MEDIA,
                name = "图片和视频",
                coverUri = allCoverUri,
                mediaList = mediaList,
                isSelected = true
            )
        )
        // 目录 2：所有视频（虚拟分类，只要手机里有视频，就展示这个入口）
        if (videoList.isNotEmpty()) {
            resultFolders.add(
                AlbumFolder(
                    bucketId = BUCKET_ID_ALL_VIDEOS, // 专属虚拟 ID: -2L
                    name = "所有视频",
                    coverUri = videoList.first().uri, // 最新一个视频的封面
                    mediaList = videoList,
                    isSelected = false
                )
            )
        }

        // 拼接其余相册（相机、截屏、微信等）
        folderBucketMap.forEach { (bucketId, list) ->
            val folderName = folderNameMap[bucketId] ?: "媒体相册"
            val coverUri = list.firstOrNull()?.uri ?: Uri.EMPTY
            resultFolders.add(
                AlbumFolder(
                    bucketId = bucketId,
                    name = folderName,
                    coverUri = coverUri,
                    mediaList = list,
                    isSelected = false
                )
            )
        }

        AlbumScanResult(
            allFolders = resultFolders,
            currentDisplayList = mediaList
        )

    }

    companion object {
        const val BUCKET_ID_ALL_MEDIA = -1L
        const val BUCKET_ID_ALL_VIDEOS = -2L
    }
}