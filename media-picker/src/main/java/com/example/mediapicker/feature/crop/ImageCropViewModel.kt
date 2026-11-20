package com.example.mediapicker.feature.crop

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.mediapicker.engine.compress.ImageCompressEngine
import com.example.mediapicker.engine.crop.BitmapCropProcessor
import com.example.mediapicker.feature.crop.model.CropRegion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 裁剪界面 UI 状态流载体
 */
data class CropUiState(
    /** 是否正在异步加载原图或正在压缩保存 */
    val isLoading: Boolean = false,

    /** 降采样解码后用于展示在裁剪框中的位图 */
    val sourceBitmap: Bitmap? = null
)

/**
 * 裁剪界面单次事件（如 Toast 提示、关闭页面等）
 */
sealed interface CropUiEffect {
    /** 弹出轻量提示 */
    data class ShowToast(val message: String) : CropUiEffect

    /** 裁剪成功：携带最终 1:1、已压制在安全体积内的目标文件 Uri 退出 */
    data class FinishWithResult(val croppedUri: Uri) : CropUiEffect
}

class ImageCropViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(CropUiState())
    val uiState: StateFlow<CropUiState> = _uiState.asStateFlow()

    private val _uiEffect = MutableSharedFlow<CropUiEffect>()
    val uiEffect: SharedFlow<CropUiEffect> = _uiEffect.asSharedFlow()

    /**
     * 异步加载待裁剪的原图 Bitmap
     * 委托给底层 ImageCompressEngine：自动执行 Exif 旋转扶正与 2048px 安全降采样，绝不 OOM
     */
    fun loadSourceBitmap(rawUri: Uri) {
        // 如果已经加载过或正在加载，直接拦截重复调用
        if (_uiState.value.isLoading || _uiState.value.sourceBitmap != null) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            // 调度到 IO 线程：即使原图是 1 亿像素大图，也只会安全读出不超过 2048px 的小图
            val bitmap = withContext(Dispatchers.IO) {
                ImageCompressEngine.decodeSampledBitmap(
                    context = getApplication(),
                    uri = rawUri,
                    maxDimension = 2048
                )
            }

            if (bitmap != null) {
                _uiState.update {
                    it.copy(isLoading = false, sourceBitmap = bitmap)
                }
            } else {
                _uiState.update { it.copy(isLoading = false) }
                _uiEffect.emit(CropUiEffect.ShowToast("图片加载失败，可能文件已损坏"))
            }
        }
    }

    /**
     * 执行物理像素切片与文件保存
     * @param region View 层手势计算出的原图像素物理坐标 (srcX, srcY, width, height)
     * @param config 裁剪策略参数（最大输出分辨率、文件体积上限）
     */
    fun executeCrop(region: CropRegion, config: CropConfig) {
        val srcBitmap = _uiState.value.sourceBitmap ?: return
        if (srcBitmap.isRecycled) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            val resultUri = withContext(Dispatchers.IO) {
                //裁剪
                val croppedBmp = BitmapCropProcessor.crop(
                    srcBitmap, region, config.maxOutputSize
                )
                // 交给纯压缩引擎压制并写入私有缓存文件
                val fileUri = ImageCompressEngine.compressBitmap(
                    context = getApplication(),
                    bitmap = croppedBmp,
                    quality = 80,
                    prefix = "crop_avatar"
                )
                // 及时释放中间产生的切片位图
                if (croppedBmp != srcBitmap && !croppedBmp.isRecycled) {
                    croppedBmp.recycle()
                }
                fileUri
            }

            _uiState.update { it.copy(isLoading = false) }

            if (resultUri != null) {
                // 成功：发射退出事件通知 Activity
                _uiEffect.emit(CropUiEffect.FinishWithResult(resultUri))
            } else {
                // 失败：发射错误 Toast
                _uiEffect.emit(CropUiEffect.ShowToast("裁剪保存失败，请稍后重试"))
            }
        }
    }

    /**
     * 生命周期感知与内存清理：
     * 页面关闭销毁时，彻底释放解码出来的 Bitmap 原图物理显存
     */
    override fun onCleared() {
        super.onCleared()
        _uiState.value.sourceBitmap?.let { bitmap ->
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }
}