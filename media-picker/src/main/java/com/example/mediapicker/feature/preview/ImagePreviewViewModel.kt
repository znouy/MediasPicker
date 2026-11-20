package com.example.mediapicker.feature.preview


import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mediapicker.data.model.MediaItem
import com.example.mediapicker.feature.preview.holder.PreviewDataHolder
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.collections.getOrNull

enum class PreviewMode {
    /** 相册内选择模式：右上角为勾选角标，底部为“完成”按钮 */
    PICKER,

    /** 外部已选图片浏览模式（表单模式）：右上角为删除按钮，无底部“完成”栏 */
    FORM,

    /**只读模式，没有任何其他操作*/
    READ
}

data class PreviewUiState(
    val mediaList: List<MediaItem> = emptyList(),
    val currentPosition: Int = 0,
    val selectedItemIds: LinkedHashSet<Long> = linkedSetOf(),
    val maxSelectLimit: Int = DEFAULT_MAX_SELECT_LIMIT,
    val isSystemUiVisible: Boolean = true,
    val previewMode: PreviewMode = PreviewMode.PICKER
) {
    companion object {
        const val DEFAULT_MAX_SELECT_LIMIT = 9 // 默认多选上限
    }

    val totalCount: Int get() = mediaList.size
    val currentItem: MediaItem? get() = mediaList.getOrNull(currentPosition)
    val selectedCount: Int get() = selectedItemIds.size

    // 当前图是否被选中，若是，返回 1-based 的序号，否则为 -1
    val currentSelectIndex: Int
        get() {
            val current = currentItem ?: return -1
            val index = selectedItemIds.indexOf(current.id)
            return if (index != -1) index + 1 else -1
        }
}

sealed interface PreviewUiEffect {
    data class ShowToast(val message: String) : PreviewUiEffect

    //相册选择退出
    data class FinishWithPickerResult(val isConfirm: Boolean, val selectedIds: LongArray) :
        PreviewUiEffect

    // 表单删除模式退出：把最终剩余的 Uri 列表完整交回 MainActivity
    data class FinishWithFormResult(val remainingUris: List<Uri>) : PreviewUiEffect

    //只读模式直接退出
    object FinishDirectly : PreviewUiEffect
}

class ImagePreviewViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(PreviewUiState())
    val uiState: StateFlow<PreviewUiState> = _uiState.asStateFlow()

    private val _uiEffect = MutableSharedFlow<PreviewUiEffect>()
    val uiEffect: SharedFlow<PreviewUiEffect> = _uiEffect.asSharedFlow()

    fun initData(initPosition: Int, maxLimit: Int, preSelectedIds: LongArray?, mode: PreviewMode) {
        val list = PreviewDataHolder.getPreviewList()
        val initialSelected = linkedSetOf<Long>()
        preSelectedIds?.forEach { initialSelected.add(it) }

        _uiState.update {
            it.copy(
                mediaList = list,
                currentPosition = initPosition.coerceIn(0, (list.size - 1).coerceAtLeast(0)),
                maxSelectLimit = maxLimit.coerceAtLeast(1),
                selectedItemIds = initialSelected,
                previewMode = mode
            )
        }
    }

    fun onPageChanged(newPosition: Int) {
        if (_uiState.value.currentPosition != newPosition) {
            _uiState.update { it.copy(currentPosition = newPosition) }
        }
    }

    /**
     * 勾选/反选（相册模式专用）
     * */
    fun toggleCurrentSelect() {
        val currentState = _uiState.value
        val currentItem = currentState.currentItem ?: return
        val currentSelected = LinkedHashSet(currentState.selectedItemIds)

        if (currentSelected.contains(currentItem.id)) {
            currentSelected.remove(currentItem.id)
        } else {
            if (currentSelected.size >= currentState.maxSelectLimit) {
                viewModelScope.launch {
                    _uiEffect.emit(PreviewUiEffect.ShowToast("最多只能选择 ${currentState.maxSelectLimit} 张照片"))
                }
                return
            }
            currentSelected.add(currentItem.id)
        }

        _uiState.update { it.copy(selectedItemIds = currentSelected) }
    }

    fun toggleSystemUi() {
        _uiState.update { it.copy(isSystemUiVisible = !it.isSystemUiVisible) }
    }

    fun confirmSelection(isConfirm: Boolean) {
        val selectedIds = _uiState.value.selectedItemIds.toLongArray()
        viewModelScope.launch {
            _uiEffect.emit(PreviewUiEffect.FinishWithPickerResult(isConfirm, selectedIds))
        }
    }

    /**
     * 删除当前正在预览的图片(表单模式专用)
     */
    fun deleteCurrentItem() {
        val currentState = _uiState.value
        val list = currentState.mediaList.toMutableList()
        val pos = currentState.currentPosition

        if (pos !in list.indices) return

        // 移除当前元素
        list.removeAt(pos)

        //如果全删光了，直接发射退出事件带回空列表
        if (list.isEmpty()) {
            viewModelScope.launch {
                _uiEffect.emit(PreviewUiEffect.FinishWithFormResult(emptyList()))
            }
            return
        }

        //计算删除后的新定位下标（如果是删掉最后一张，自动往前滑一张）
        val nextPos = if (pos >= list.size) list.size - 1 else pos

        _uiState.update {
            it.copy(
                mediaList = list,
                currentPosition = nextPos
            )
        }
    }

    /**
     * 用户按返回键：根据模式分流返回
     */
    fun handleBackPress() {
        val state = _uiState.value
        viewModelScope.launch {
            when (state.previewMode) {
                // 模式 1：带回勾选的 IDs 给相册
                PreviewMode.PICKER -> {
                    val ids = state.selectedItemIds.toLongArray()
                    _uiEffect.emit(PreviewUiEffect.FinishWithPickerResult(isConfirm = false, ids))
                }
                // 模式 2：带回删减后剩余的 Uris 给发起页面
                PreviewMode.FORM -> {
                    val remainingUris = state.mediaList.map { it.uri }
                    _uiEffect.emit(PreviewUiEffect.FinishWithFormResult(remainingUris))
                }
                // 模式 3：纯预览模式
                PreviewMode.READ -> {
                    _uiEffect.emit(PreviewUiEffect.FinishDirectly)
                }
            }
        }
    }


    override fun onCleared() {
        super.onCleared()
        // 彻底切断静态数据引用，防止 Activity 销毁后残留内存
        PreviewDataHolder.clear()
    }
}