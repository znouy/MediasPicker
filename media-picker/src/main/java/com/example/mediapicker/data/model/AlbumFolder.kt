package com.example.mediapicker.data.model


import android.net.Uri

/**
 * 相册文件夹模型
 * @param bucketId 文件夹唯一标识符（所有图片默认设为 -1）
 * @param name 文件夹名称（如：所有图片、相机、截屏等）
 * @param coverUri 该文件夹最新一张图的 Uri（用作目录列表封面）
 * @param mediaList 该文件夹包含的具体图片集合
 */
data class AlbumFolder(
    val bucketId: Long,
    val name: String,
    val coverUri: Uri,
    val mediaList: List<MediaItem>,
    val isSelected: Boolean = false
) {
    // 该相册图片张数
    val count: Int get() = mediaList.size
}