package com.example.mediapicker.feature.preview.contract

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.result.contract.ActivityResultContract
import com.example.mediapicker.data.model.MediaItem
import com.example.mediapicker.feature.preview.ImagePreviewActivity
import com.example.mediapicker.feature.preview.PreviewMode
import com.example.mediapicker.feature.preview.holder.PreviewDataHolder

data class FromPreviewRequest(
    val uris: List<Uri>,
    val initPosition: Int = 0//点击进入预览时的角标
)

/**
 * 表单删除模式浏览
 * */
class FormPreviewContract : ActivityResultContract<FromPreviewRequest, List<Uri>>() {
    override fun createIntent(
        context: Context,
        input: FromPreviewRequest
    ): Intent {
        // 将 List<Uri> 快速包装为 List<MediaItem> 送进数据仓
        val mediaItems = input.uris.mapIndexed { index, uri ->
            MediaItem(
                id = index.toLong(),
                uri = uri,
                mimeType = context.contentResolver.getType(uri) ?: "image/jpeg",
                dateModified = 0L
            )
        }
        PreviewDataHolder.setPreviewList(mediaItems)

        // 携带 Form 模式标志启动
        return Intent(context, ImagePreviewActivity::class.java).apply {
            putExtra(ImagePreviewActivity.EXTRA_INIT_POSITION, input.initPosition)
            putExtra(ImagePreviewActivity.EXTRA_PREVIEW_MODE, PreviewMode.FORM) //
        }
    }

    override fun parseResult(
        resultCode: Int,
        intent: Intent?
    ): List<Uri> {
        if (resultCode != Activity.RESULT_OK || intent == null) return emptyList()

        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableArrayListExtra(
                ImagePreviewActivity.RESULT_REMAINING_URIS,
                Uri::class.java
            )
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra(ImagePreviewActivity.RESULT_REMAINING_URIS)
        } ?: emptyList()
    }


}