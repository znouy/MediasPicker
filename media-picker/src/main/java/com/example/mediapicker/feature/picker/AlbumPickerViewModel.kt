package com.example.mediapicker.feature.picker


import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.mediapicker.data.model.AlbumFolder
import com.example.mediapicker.data.model.MediaItem
import com.example.mediapicker.data.repository.AlbumRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.collections.map

sealed interface AlbumUiEffect {
    data class ShowToast(val message: String) : AlbumUiEffect
    data class ConfirmSelection(val selectedUris: List<Uri>) : AlbumUiEffect

    // 通知外部 Adapter 精准局部刷新某些下标（消灭全量刷新）
    data class NotifyItemsChanged(val positions: List<Int>) : AlbumUiEffect
}

data class AlbumUiState(
    val mediaList: List<MediaItem> = emptyList(),
    val selectedItems: List<MediaItem> = emptyList(),
    val maxSelectLimit: Int = DEFAULT_MAX_SELECT_LIMIT,//动态注入
    val isLoading: Boolean = false,

    val folders: List<AlbumFolder> = emptyList(), //  全部文件夹列表
    val currentFolderName: String = "图片和视频",     //  当前选中的文件夹名字
){
    companion object {
        const val DEFAULT_MAX_SELECT_LIMIT = 9 // 默认多选上限
    }
}

class AlbumViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = AlbumRepository(application.applicationContext)

    private val _uiState = MutableStateFlow(AlbumUiState())
    val uiState: StateFlow<AlbumUiState> = _uiState.asStateFlow()

    private val _uiEffect = MutableSharedFlow<AlbumUiEffect>()
    val uiEffect: SharedFlow<AlbumUiEffect> = _uiEffect.asSharedFlow()

    /**
     * 由 Activity 在 onCreate 传入宿主的真实配置（动态上限）
     */
    fun initConfig(maxLimit: Int) {
        _uiState.update { it.copy(maxSelectLimit = maxLimit.coerceAtLeast(1)) }
    }
    fun loadImages() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val scanResult = repository.queryMediaImages()
            _uiState.update {
                it.copy(
                    mediaList = scanResult.currentDisplayList,
                    isLoading = false,
                    folders = scanResult.allFolders,
                    currentFolderName = "图片和视频",
                )
            }
        }
    }

    fun toggleItemSelection(targetItem: MediaItem, targetPosition: Int) {
        val currentSelected = _uiState.value.selectedItems.toMutableList()
        val indexInSelected = currentSelected.indexOfFirst { it.id == targetItem.id }

        val positionsToRefresh = mutableListOf<Int>()
        positionsToRefresh.add(targetPosition)

        if (indexInSelected != -1) {
            // 已选 -> 取消勾选
            currentSelected.removeAt(indexInSelected)
            // 取消后，排在它后面的那些图片的角标数字全部发生了变化，需要把它们的位置也加入刷新列表
            // 由于最多只选 9 张，这里循环最多 9 次
            val mediaList = _uiState.value.mediaList
            for (i in indexInSelected until currentSelected.size) {
                val itemToUpdate = currentSelected[i]
                val pos = mediaList.indexOfFirst { it.id == itemToUpdate.id }
                if (pos != -1) positionsToRefresh.add(pos)
            }
        } else {
            // 未选 -> 添加勾选
            if (currentSelected.size >= _uiState.value.maxSelectLimit) {
                viewModelScope.launch {
                    _uiEffect.emit(AlbumUiEffect.ShowToast("最多只能选择 ${_uiState.value.maxSelectLimit} 张照片"))
                }
                return
            }
            currentSelected.add(targetItem)
        }

        // 仅更新已选集合，mediaList 保持完全不变！
        _uiState.update { it.copy(selectedItems = currentSelected) }

        // 通知 UI 只刷新受影响的这几个格子
        viewModelScope.launch {
            _uiEffect.emit(AlbumUiEffect.NotifyItemsChanged(positionsToRefresh))
        }
    }

    fun submitSelection() {
        val uris = _uiState.value.selectedItems.map { it.uri }
        viewModelScope.launch {
            _uiEffect.emit(AlbumUiEffect.ConfirmSelection(uris))
        }
    }


    fun syncSelectedIds(selectedIds: Set<Long>) {
        // 按照原来的顺序保留选中的项目
        val currentMediaMap = _uiState.value.mediaList.associateBy { it.id }
        val newSelectedItems = selectedIds.mapNotNull { currentMediaMap[it] }

        _uiState.update {
            it.copy(
                selectedItems = newSelectedItems
            )
        }
        // 全局刷新角标（大图返回时最多刷新整屏 20 个可见项）
        viewModelScope.launch {
            _uiEffect.emit(AlbumUiEffect.NotifyItemsChanged(emptyList())) // emptyList 代表让 UI 整体轻量局部刷一遍可见区域
        }
    }

    /**
     * 用户在下拉弹窗里点击选中了某个相册目录
     */
    fun selectFolder(targetFolder: AlbumFolder) {
        // 1. 更新相册文件夹内部的勾选状态
        val updatedFolders = _uiState.value.folders.map { folder ->
            folder.copy(isSelected = (folder.bucketId == targetFolder.bucketId))
        }

        _uiState.update {
            it.copy(
                mediaList = targetFolder.mediaList,
                folders = updatedFolders,
                currentFolderName = targetFolder.name
            )
        }
    }
}