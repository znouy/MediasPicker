package com.example.mediapicker.data.model

import android.net.Uri

//import kotlinx.parcelize.Parcelize

//@Parcelize
data class MediaItem(
    val id: Long,
    val uri: Uri,
    val mimeType: String,
    val dateModified: Long,
    val duration: Long = 0L,//视频物理时长（单位：毫秒），图片为 0L
//    val isSelected: Boolean = false,
//    val selectIndex: Int = -1, // 选中序号，-1 表示未选
    val bucketId: Long = 0L, // 所属文件夹 ID
    val bucketName: String = "" // 所属文件夹名称
){
    // 判断是否为视频
    val isVideo: Boolean get() = mimeType.startsWith("video/", ignoreCase = true)
}