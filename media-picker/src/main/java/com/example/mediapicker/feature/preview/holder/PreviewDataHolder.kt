package com.example.mediapicker.feature.preview.holder

import com.example.mediapicker.data.model.MediaItem
import kotlin.collections.isNotEmpty
import kotlin.collections.toList

/**
 * 内存级预览数据通道，防止跨进程/跨页面传递超大 List 导致 TransactionTooLargeException
 */
object PreviewDataHolder {
    @Volatile
    private var previewList: List<MediaItem> = emptyList()

    fun setPreviewList(list: List<MediaItem>) {
        // 关键点：依然建议加上 .toList() 做一层只读浅拷贝快照
        // 耗时只有不到 1 微秒，但能彻底防住外部原 List 被修改引发的崩溃
        this.previewList = list.toList()
    }

    fun getPreviewList(): List<MediaItem> = previewList

    fun hasData(): Boolean = previewList.isNotEmpty()

    fun clear() {
        previewList = emptyList()
    }
}