package com.example.mediapicker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.example.mediapicker.data.model.MediaItem
import com.example.mediapicker.feature.preview.ImagePreviewActivity
import com.example.mediapicker.feature.preview.PreviewMode
import com.example.mediapicker.feature.preview.contract.PickerPreviewContract
import com.example.mediapicker.feature.preview.contract.PickerPreviewRequest
import com.example.mediapicker.feature.preview.contract.PickerPreviewResult
import com.example.mediapicker.feature.preview.holder.PreviewDataHolder
import kotlin.collections.mapIndexed

/**
 * 编译期模式标记接口（幻影标记，零运行时内存开销）
 */
sealed interface PreviewType {
    /** 纯只读标记 */
    object Read : PreviewType

    /** 表单浏览删除标记 */
    object Form : PreviewType

    /** 相册挑图标记 */
    object Picker : PreviewType
}

/**
 * 预览图片功能调用入口
 *
 * */
class MediaPreview private constructor() {

    /**
     * 全工程唯一的通用大图预览建造者！
     * 带有泛型标记 T : PreviewType
     */
    class Builder<T : PreviewType> internal constructor(
        private val context: Context,
        private val mode: PreviewMode,
        private val launcher: ActivityResultLauncher<Intent>? = null,
        private val uris: List<Uri> = emptyList(),
        private val mediaList: List<MediaItem> = emptyList()
    ) {
        // 通用字段：起始展示的下标位置
        internal var initPosition: Int = 0

        // 相册挑图专属字段（internal 供扩展函数赋值）
        internal var selectedIds: Set<Long> = emptySet()
        internal var maxLimit: Int = 9

        /**
         * 通用配置：设置打开大图时初始停留的位置（三大模式共享）
         */
        fun initPosition(position: Int) = apply {
            this.initPosition = position
        }

        /**
         * 统一发射入口
         */
        fun launch() {
            // 1. 将数据安全灌入内存仓（杜绝跨进程 TransactionTooLargeException）
            if (mediaList.isNotEmpty()) {
                PreviewDataHolder.setPreviewList(mediaList)
            } else if (uris.isNotEmpty()) {
                val items = uris.mapIndexed { index, uri ->
                    MediaItem(
                        id = index.toLong(),
                        uri = uri,
                        mimeType = context.contentResolver.getType(uri) ?: "image/jpeg",
                        dateModified = 0L
                    )
                }
                PreviewDataHolder.setPreviewList(items)
            } else {
                return // 空数据不发射
            }

            // 2. 组装 Intent 参数
            val intent = Intent(context, ImagePreviewActivity::class.java).apply {
                putExtra(ImagePreviewActivity.EXTRA_INIT_POSITION, initPosition)
                putExtra(ImagePreviewActivity.EXTRA_PREVIEW_MODE, mode)
                if (mode == PreviewMode.PICKER) {
                    putExtra(ImagePreviewActivity.EXTRA_MAX_LIMIT, maxLimit)
                    putExtra(ImagePreviewActivity.EXTRA_SELECTED_IDS, selectedIds.toLongArray())
                }
            }

            // 3. 根据是否需要回调分流启动
            if (launcher != null) {
                launcher.launch(intent)
            } else {
                context.startActivity(intent)
            }
        }
    }


    companion object {

        // ========================================================
        // 1 图片只预览入口
        // 不做其他操作，纯查看大图，
        // ========================================================
        /**
         * 只读模式入口
         * */
        fun previewRead(context: Context, uris: List<Uri>): Builder<PreviewType.Read> {
            return Builder(context = context, mode = PreviewMode.READ, uris = uris)
        }
        // ========================================================
        // 2. 表单浏览删除模式入口
        // 图片浏览右上角带垃圾桶，返回最新剩余的 List<Uri>
        // ========================================================
        /**
         *
         * 表单浏览删除模式入口 (Form)
         *  传入 Launcher，右上角带垃圾桶，返回最新剩余的 List<Uri>
         * */
        fun previewForm(
            context: Context, launcher: ActivityResultLauncher<Intent>, uris: List<Uri>
        ): Builder<PreviewType.Form> {
            return Builder(
                context = context, mode = PreviewMode.FORM, launcher = launcher, uris = uris
            )
        }

        /**
         * 注册表单删除契约（Activity 版）
         * */
        fun registerForm(
            activity: ComponentActivity, callback: (remainingUris: List<Uri>) -> Unit
        ): ActivityResultLauncher<Intent> {
            return activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                handleFormResult(result.resultCode, result.data, callback)
            }
        }

        /**
         * 注册表单删除契约（Fragment 版）
         * */
        fun registerForm(
            fragment: Fragment, callback: (remainingUris: List<Uri>) -> Unit
        ): ActivityResultLauncher<Intent> {
            return fragment.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                handleFormResult(result.resultCode, result.data, callback)
            }
        }

        private fun handleFormResult(
            resultCode: Int,
            data: Intent?,
            callback: (List<Uri>) -> Unit
        ) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                val remainingUris = if (Build.VERSION.SDK_INT >= 33) {
                    data.getParcelableArrayListExtra(
                        ImagePreviewActivity.RESULT_REMAINING_URIS, Uri::class.java
                    )
                } else {
                    @Suppress("DEPRECATION") data.getParcelableArrayListExtra(ImagePreviewActivity.RESULT_REMAINING_URIS)
                } ?: emptyList()
                callback(remainingUris)
            }
        }

        // ========================================================
        // 3. 相册挑选大图入口 (PICKER)
        // 右上角数字角标，底部“完成”提交，返回更新后的 ID 集合与确认状态
        // ========================================================
        data class PickerResult(
            val isConfirm: Boolean,
            val updatedSelectedIds: Set<Long>
        )

        fun previewPicker(
            context: Context, launcher: ActivityResultLauncher<Intent>, mediaList: List<MediaItem>
        ): Builder<PreviewType.Picker> {
            return Builder<PreviewType.Picker>(
                context = context,
                mode = PreviewMode.PICKER,
                launcher = launcher,
                mediaList = mediaList
            )
        }

        /** 注册相册挑图契约（Activity 版） */
        fun registerPicker(
            activity: ComponentActivity, callback: (PickerResult) -> Unit
        ): ActivityResultLauncher<Intent> {
            return activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                handlePickerResult(result.resultCode, result.data, callback)
            }
        }

        /** 注册相册挑图契约（Fragment 版） */
        fun registerPicker(
            fragment: Fragment,
            callback: (PickerResult) -> Unit
        ): ActivityResultLauncher<Intent> {
            return fragment.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                handlePickerResult(result.resultCode, result.data, callback)
            }
        }

        private fun handlePickerResult(
            resultCode: Int,
            data: Intent?,
            callback: (PickerResult) -> Unit
        ) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                val isConfirm = data.getBooleanExtra(ImagePreviewActivity.RESULT_IS_CONFIRM, false)
                val updatedIds =
                    data.getLongArrayExtra(ImagePreviewActivity.RESULT_SELECTED_IDS)?.toSet()
                        ?: emptySet()
                callback(PickerResult(isConfirm, updatedIds))
            }
        }
    }
}

// ========================================================
// 仅针对 [PreviewType.Picker] 开放！
// 只读和删除模式在编译期根本匹配不到这几个方法，彻底杜绝误用！
// ========================================================

/** 设置当前已勾选的 ID 集合（仅限相册挑图模式） */
fun MediaPreview.Builder<PreviewType.Picker>.selectedIds(ids: Set<Long>): MediaPreview.Builder<PreviewType.Picker> =
    apply {
        this.selectedIds = ids
    }

/** 设置最大可选上限（仅限相册挑图模式） */
fun MediaPreview.Builder<PreviewType.Picker>.maxLimit(limit: Int): MediaPreview.Builder<PreviewType.Picker> =
    apply {
        this.maxLimit = limit
    }