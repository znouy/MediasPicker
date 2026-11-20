package com.example.mediapicker.feature.preview.contract

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import com.example.mediapicker.data.model.MediaItem
import com.example.mediapicker.feature.preview.ImagePreviewActivity
import com.example.mediapicker.feature.preview.PreviewMode
import com.example.mediapicker.feature.preview.holder.PreviewDataHolder

data class PickerPreviewRequest(
    val mediaList: List<MediaItem>,
    val initPosition: Int = 0,
    val selectedIds: Set<Long> = emptySet(),
    val maxLimit: Int = 9
)

data class PickerPreviewResult(
    val isConfirm: Boolean,
    val updatedSelectedIds: Set<Long>
)
 
/**
 * 相册内网格挑图预览
 * */
class PickerPreviewContract : ActivityResultContract<PickerPreviewRequest, PickerPreviewResult>() {
    override fun getSynchronousResult(
        context: Context,
        input: PickerPreviewRequest
    ): SynchronousResult<PickerPreviewResult>? {
        return if (input.mediaList.isEmpty()) {
            SynchronousResult(
                PickerPreviewResult(
                    isConfirm = false,
                    updatedSelectedIds = input.selectedIds
                )
            )
        } else null
    }

    override fun createIntent(
        context: Context,
        input: PickerPreviewRequest
    ): Intent {
        // 1. 存入内存数据仓
        PreviewDataHolder.setPreviewList(input.mediaList)

        // 2. Intent 仅携带极轻量的基础参数
        return Intent(context, ImagePreviewActivity::class.java).apply {
            putExtra(ImagePreviewActivity.EXTRA_INIT_POSITION, input.initPosition)
            putExtra(ImagePreviewActivity.EXTRA_MAX_LIMIT, input.maxLimit)
            // 传入当前选中的 ID 集合
            putExtra(ImagePreviewActivity.EXTRA_SELECTED_IDS, input.selectedIds.toLongArray())
            putExtra(ImagePreviewActivity.EXTRA_PREVIEW_MODE, PreviewMode.PICKER)
        }

    }

    override fun parseResult(
        resultCode: Int,
        intent: Intent?
    ): PickerPreviewResult {
        if (resultCode != Activity.RESULT_OK || intent == null) {
            return PickerPreviewResult(isConfirm = false, updatedSelectedIds = emptySet())
        }
        val isConfirm = intent.getBooleanExtra(ImagePreviewActivity.RESULT_IS_CONFIRM, false)
        val updatedIds = intent.getLongArrayExtra(ImagePreviewActivity.RESULT_SELECTED_IDS)?.toSet()
            ?: emptySet()
        return PickerPreviewResult(isConfirm, updatedIds)
    }

}