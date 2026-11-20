package com.example.app

import android.net.Uri
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.example.app.databinding.ActivityLeaveBinding
import com.example.mediapicker.MediaPicker
import com.example.mediapicker.MediaPreview
import com.example.mediapicker.core.extension.dp2px
import com.example.mediapicker.core.extension.screenWidthPx
import com.example.mediapicker.core.extension.showToast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.getValue

class LeaveActivity : AppCompatActivity() {

    private lateinit var leaveAdapter: LeaveAdapter
    private val viewModel: LeaveViewModel by viewModels()
    private lateinit var binding: ActivityLeaveBinding
    private val maxPicCount: Int = 9//最大图片上限

    private val selectedUris = mutableListOf<Uri>()
    private val pickImagesLauncher = MediaPicker.register(this) {
        viewModel.addImages(it, maxPicCount)
    }

    // 1. 注册表单预览删除监听
    private val previewLauncher = MediaPreview.registerForm(this) { remainingUris ->
        selectedUris.clear()
        selectedUris.addAll(remainingUris)
        leaveAdapter.submitData(selectedUris.toList())
    }

    //  点击“+”号时弹出选择对话框
    private fun openPhotoChoiceDialog() {
        val items = arrayOf("拍照", "从相册选择")
        MaterialAlertDialogBuilder(this).setItems(items) { _, which ->
            when (which) {
                0 -> { /* 调用系统相机拍照... */
                }

                1 -> {
                    // 动态计算当前“剩余可选额度”
                    val currentCount = viewModel.images.value.size
                    val remainingCount = maxPicCount - currentCount
                    if (remainingCount <= 0) {
                        showToast("最多只能上传 $maxPicCount 张图片")
                        return@setItems
                    }

                    // 启动相册选择器
                    MediaPicker.pickMulti(pickImagesLauncher, remainingCount)
                        .showDebugHud(true)
                        .launch()
                }
            }
        }.show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLeaveBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initView()
        initData()
    }

    private fun initData() {
        // 绑定图片集更新
        lifecycleScope.launch {
            viewModel.images.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
                .collectLatest { uris -> leaveAdapter.submitData(uris) }
        }
    }

    private fun setupRecyclerView() {
        val spanCount = 4 // 一行 4 张图
        val spacingPx = dp2px(8f) // 8dp 间距
        val screenWidth = screenWidthPx
        val paddingPx = dp2px(16f) * 2 // 左右各 16dp 边距

        // 动态计算每个格子精准的物理像素大小
        val itemPixelSize = (screenWidth - paddingPx - (spanCount - 1) * spacingPx) / spanCount

        leaveAdapter = LeaveAdapter(
            itemPixelSize = itemPixelSize,
            maxPicCount,
            onAddClick = { openPhotoChoiceDialog() },
            onDeleteClick = { index -> viewModel.removeImage(index) },
            onItemClick = { position, _ -> openPreviewPage(position) })
        // 配置九宫格图片列表
        binding.rvImages.apply {
            layoutManager = GridLayoutManager(this@LeaveActivity, 4)
            adapter = leaveAdapter
        }
    }

    private fun openPreviewPage(position: Int) {
        MediaPreview.previewForm(this, previewLauncher, viewModel.images.value)
            .initPosition(position)
            .launch()

    }

    private fun initView() {
        setupRecyclerView()
        binding.btnSubmit.setOnClickListener {
            viewModel.submitForm(this)
        }
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

}