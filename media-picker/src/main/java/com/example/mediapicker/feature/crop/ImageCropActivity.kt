package com.example.mediapicker.feature.crop

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.mediapicker.databinding.ActivityImageCropBinding
import kotlinx.coroutines.launch
import kotlin.jvm.java

class ImageCropActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_RAW_URI = "EXTRA_RAW_URI"
        const val EXTRA_CROP_CONFIG = "EXTRA_CROP_CONFIG"
        const val RESULT_CROPPED_URI = "RESULT_CROPPED_URI"
    }

    private lateinit var binding: ActivityImageCropBinding
    private val viewModel: ImageCropViewModel by viewModels()

    // 裁剪与压缩策略配置（输出尺寸、体积限制等）
    private lateinit var cropConfig: CropConfig

    override fun onCreate(savedInstanceState: Bundle?) {
        // 核心优化：消除系统窗口默认背景绘制，节省 Overdraw
        window.setBackgroundDrawable(null)
        super.onCreate(savedInstanceState)
        binding = ActivityImageCropBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 1. 安全解析入参数据（兼容 Android 13+ 强类型 Parcelable）
        val rawUri = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RAW_URI, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RAW_URI)
        }
        // TODO:  Serializable Parcelable
        cropConfig = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_CROP_CONFIG, CropConfig::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_CROP_CONFIG)
        } ?: CropConfig()

        // 容错校验：若无有效输入 Uri，直接退出防止白屏
        if (rawUri == null) {
            Toast.makeText(this, "图片载入失败：无效的路径", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupWindowInsets()
        setupListeners()
        observeViewModel()

        // 2. 委托 ViewModel 在 IO 线程执行防 OOM 降采样读取
        viewModel.loadSourceBitmap(rawUri)
    }

    /**
     * 适配全面屏手势与挖孔摄像头：
     * 避免顶部标题撞上挖孔摄像头，避免底部“取消/确定”被底部手势小白条遮挡
     */
    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            // 顶部边距避开挖孔屏
//            binding.tvTitle.updatePadding(top = insets.top)
            // 底部边距避开手势小白条或三键虚拟按键
//            binding.layoutBottom.updatePadding(bottom = insets.bottom)

            WindowInsetsCompat.CONSUMED
        }
    }

    private fun setupListeners() {
        // 取消裁剪
        binding.btnCancel.setOnClickListener {
            finish()
        }

        // 确定裁剪
        binding.btnConfirm.setOnClickListener {
            // 1. 从自定义 View 中获取经过矩阵映射后的原图像素切片区域坐标（耗时 < 1 微秒，零内存分配）
            val region = binding.cropView.calculateCropRegion()
            if (region != null) {
                // 2. 交付给 ViewModel 在子线程中执行物理切割与阶梯压缩落盘
                viewModel.executeCrop(region, cropConfig)
            } else {
                Toast.makeText(this, "裁剪坐标计算失败，请稍后重试", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 响应式观察 ViewModel 的状态流与单次副作用事件（UDF 单向数据流标准）
     */
    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // 1. 订阅 UI 渲染状态
                launch {
                    viewModel.uiState.collect { state ->
                        renderState(state)
                    }
                }

                // 2. 订阅单次事件（Toast、携带结果退出）
                launch {
                    viewModel.uiEffect.collect { effect ->
                        handleEffect(effect)
                    }
                }
            }
        }
    }

    /**
     * 纯粹的 UI 渲染层
     */
    private fun renderState(state: CropUiState) {
        // 控制居中加载条显隐（解码与保存压缩时展示）
        binding.progressBar.isVisible = state.isLoading

        // 加载/保存中禁用确定按钮，防止用户连续快速狂点导致重复保存
        binding.btnConfirm.isEnabled = !state.isLoading

        // 位图就绪后，直接喂给CropView 初始化矩阵与手势
        state.sourceBitmap?.let { bitmap ->
            binding.cropView.setImageBitmap(bitmap)
        }
    }

    /**
     * 单次动作事件消费
     */
    private fun handleEffect(effect: CropUiEffect) {
        when (effect) {
            is CropUiEffect.ShowToast -> {
                Toast.makeText(this, effect.message, Toast.LENGTH_SHORT).show()
            }
            is CropUiEffect.FinishWithResult -> {
                Log.e("","effect.croppedUri=${effect.croppedUri}")
                // 裁剪成功：携带 1:1、已压制在安全体积内的 Uri 优雅退出
                val resultIntent = Intent().apply {
                    putExtra(RESULT_CROPPED_URI, effect.croppedUri)
                }
                setResult(Activity.RESULT_OK, resultIntent)
                finish()
            }
        }
    }
}