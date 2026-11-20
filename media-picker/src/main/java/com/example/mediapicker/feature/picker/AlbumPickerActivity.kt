package com.example.mediapicker.feature.picker

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.ListPreloader
import com.bumptech.glide.Priority
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.integration.recyclerview.RecyclerViewPreloader
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.util.FixedPreloadSizeProvider
import com.example.mediapicker.MediaPickerConfig
import com.example.mediapicker.MediaPickerContract
import com.example.mediapicker.MediaPreview
import com.example.mediapicker.core.extension.showToast
import com.example.mediapicker.data.model.MediaItem
import com.example.mediapicker.engine.glide.FastThumbOptions
import com.example.mediapicker.feature.preview.ImagePreviewActivity
import com.example.mediapicker.feature.preview.holder.PreviewDataHolder
import com.example.mediapicker.monitor.AlbumHudTracker
import com.example.mediapicker.databinding.ActivityAlbumPickerBinding
import com.example.mediapicker.feature.crop.CropRequest
import com.example.mediapicker.feature.crop.ImageCropActivity
import com.example.mediapicker.feature.crop.ImageCropContract
import com.example.mediapicker.feature.preview.PreviewMode
import com.example.mediapicker.maxLimit
import com.example.mediapicker.selectedIds
import kotlinx.coroutines.launch

/**
 * 相册选择主页
 *
 * 单选模式核心逻辑：点中照片时通过契约（ImageCropContract）拦截跳转到裁剪页；
 * 裁剪成功才真正交付退出，若用户在裁剪页点击‘取消’，则安全停留在相册让用户重新挑图
 * */
class AlbumPickerActivity : AppCompatActivity() {
    //相册配置（单选/多选、最大上限、是否开 HUD）
    private lateinit var config: MediaPickerConfig
    private lateinit var binding: ActivityAlbumPickerBinding
    private val viewModel: AlbumViewModel by viewModels()
    private lateinit var adapter: AlbumAdapter
    private lateinit var folderAdapter: AlbumFolderAdapter
    private var isFolderOpen = false

    private val backPressedCallback = object : OnBackPressedCallback(false) {//true代表一进入页面就强制拦截系统的物理返回键。
        override fun handleOnBackPressed() {
            toggleFolderDropdown(false)
        }
    }

    // 适配 Android 14+ 细粒度权限
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Android 14 哪怕只授予了部分权限 (READ_MEDIA_VISUAL_USER_SELECTED)，也视为成功，加载允许的图片
        val isGranted = if (Build.VERSION.SDK_INT >= 34) {
            permissions[Manifest.permission.READ_MEDIA_IMAGES] == true ||
                    permissions[Manifest.permission.READ_MEDIA_VIDEO] == true ||
                    permissions[Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED] == true
        } else {
            permissions.values.any { it }
        }

        if (isGranted) {
            viewModel.loadImages()
        } else {
            Toast.makeText(this, "需要相册及视频读取权限", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    // 注册预览返回的结果监听器
    private val previewLauncher = MediaPreview.registerPicker(this) { pickerResult ->
        // 1. 同步大图预览页对勾选状态的修改
        viewModel.syncSelectedIds(pickerResult.updatedSelectedIds)

        // 2. 如果用户在预览页直接点了“完成”，当前相册也直接宣告选择完毕，退出并交付外部
        if (pickerResult.isConfirm) {
            viewModel.submitSelection()
        }
    }

    //注册裁剪契约监听器
    private val cropLauncher = registerForActivityResult(
        ImageCropContract()
    ) { croppedUri: Uri? ->
        if (croppedUri != null) {
            //  裁剪确认成功：携带最终裁好的图片 Uri，退出相册交付给发起方（如 UserHeadActivity）
            confirmSelection(listOf(croppedUri))
        } else {
            // 用户在裁剪页点击了“取消”或返回：
            // 什么都不做，停留在当前相册，用户可以继续挑选其他照片
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 核心优化 1：清空 Window 背景，消除全屏 Overdraw
        window.setBackgroundDrawable(null)
        super.onCreate(savedInstanceState)
        binding = ActivityAlbumPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initial()
        setupRecyclerView()
        setupListeners()
        observeViewModel()
        checkPermissions()
        setupFolderDropdown()
    }

    private fun initial() {
        // 1. 读取宿主传进来的配置
        config =
            intent.getSerializableExtra(MediaPickerContract.EXTRA_CONFIG) as? MediaPickerConfig
                ?: MediaPickerConfig()
        // 如果是单选，隐藏底部的“完成/预览”操作栏
        binding.layoutBottomBar.isVisible = !config.isSingleMode

        // 2. 将最大选择数同步给 ViewModel
        viewModel.initConfig(config.maxSelectCount)

        // 3. 根据宿主设置决定是否开启 HUD 调试面板
        if (config.isShowHud) {
            binding.btnToggleHud.visibility = View.VISIBLE
            binding.btnToggleHud.visibility = View.VISIBLE
            // 初始化 HUD（只要传入 ViewBinding，生命周期自动全托管）
            AlbumHudTracker(binding.layoutMemoryHud)
        } else {
            binding.layoutMemoryHud.root.visibility = View.GONE
            binding.btnToggleHud.visibility = View.GONE
        }
    }

    private fun setupFolderDropdown() {
        folderAdapter = AlbumFolderAdapter { selectedFolder ->
            // 1. 用户点击了某个相册目录
            viewModel.selectFolder(selectedFolder)
            // 2. 收起下拉弹窗
            toggleFolderDropdown(false)
            // 3. 将网格平滑滚回到顶部
            binding.recyclerView.scrollToPosition(0)
        }

        binding.rvFolderList.apply {
            layoutManager = LinearLayoutManager(this@AlbumPickerActivity)
            adapter = folderAdapter
        }

        // 点击遮罩收起
        binding.viewMaskFolder.setOnClickListener {
            toggleFolderDropdown(false)
        }

        // 点击顶部标题栏切换展开/收起
        binding.tvAlbumTitle.setOnClickListener {
            toggleFolderDropdown(!isFolderOpen)
        }
    }

    private var isFolderAnimating = false // 防止狂点快速连击导致动画错乱
    private fun toggleFolderDropdown(open: Boolean) {

        if (isFolderOpen == open || isFolderAnimating) return
        isFolderOpen = open
        isFolderAnimating = true

        // 展开时激活拦截；收起时关闭拦截，让系统原生返回逻辑自由生效！
        backPressedCallback.isEnabled = open

        // 箭头顺滑旋转 180 度动效（微信同款细节）
//        binding.ivArrow.animate()
//            .rotation(if (open) 180f else 0f)
//            .setDuration(250)
//            .start()

        val dropdown = binding.layoutFolderDropdown
        if (open) {//展开流程
            dropdown.alpha = 0f
            dropdown.visibility = View.VISIBLE

            // 抽屉平移动效：必须等到 View 布局测量完毕拿到真实的 height 后，再执行位移！
            binding.rvFolderList.post {
                val folderHeight = binding.rvFolderList.height.toFloat()
                // 先把列表瞬间拉到顶部以外看不见的地方
                binding.rvFolderList.translationY = -folderHeight
                // 父容器整体淡入（遮罩跟着自然淡入）,必须在rvFolderList移动位置后，不然闪屏 v_g_v
                dropdown.animate().alpha(1f).setDuration(300).start()

                // 像抽屉一样向下滑落下来（配合减速插值器，手感非常弹糯）
                binding.rvFolderList.animate().translationY(0f).setDuration(300)
                    .setInterpolator(DecelerateInterpolator(1.5f))
                    .withEndAction {
                        isFolderAnimating = false
                    }.start()
            }
        } else {//收起流程
            // 收起时整个容器一起淡出
            dropdown.animate().alpha(0f).setDuration(250).withEndAction {
                dropdown.visibility = View.GONE
            }.start()
            val folderHeight = binding.rvFolderList.height.toFloat()
            // 列表向上缩回顶部
            binding.rvFolderList.animate().translationY(-folderHeight).setDuration(250)
                .setInterpolator(AccelerateInterpolator(1.5f))
                .withEndAction {
                    // 还原 translationY，避免下次直接打开时位置错乱
                    binding.rvFolderList.translationY = 0f
                    isFolderAnimating = false
                }.start()
        }
    }

    private fun setupRecyclerView() {
        val spanCount = 4
        val screenWidth = resources.displayMetrics.widthPixels
        val spacingPx = (2 * resources.displayMetrics.density).toInt()
        val itemPixelSize = (screenWidth - (spanCount - 1) * spacingPx) / spanCount

        // 黄金性能 RequestOptions
        val requestOptions = RequestOptions()
            .override(itemPixelSize, itemPixelSize)
            .centerCrop()
            .format(DecodeFormat.PREFER_RGB_565)
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .set(FastThumbOptions.ENABLE_FAST_THUMBNAIL, true) // 设置加载系统缩略图，激活 1ms 系统缩略图通道
            .dontAnimate()

        adapter = AlbumAdapter(
            config.isSingleMode, requestOptions, itemPixelSize,
            onToggleSelect = { targetItem, position ->
                viewModel.toggleItemSelection(targetItem, position)
            },
            onItemClick = onItemClick@{ targetItem, position ->

                if (config.isSingleMode) {
                    if (targetItem.isVideo) {
                        showToast("该场景下无法选择视频")
                        return@onItemClick
                    }
                    if (config.cropConfig.isEnabled) {//开启了裁剪
                        cropLauncher.launch(
                            CropRequest(
                                rawUri = targetItem.uri,
                                cropConfig = config.cropConfig
                            )
                        )

                    } else {
                        confirmSelection(listOf(targetItem.uri))
                    }
                } else {
                    // 点击图片本身：把整个相册列表送入预览，从当前位置开始看
                    openPreviewPage(adapter.currentList, position)
                }
            })

        // 采用 Glide 官方预加载器，替代手写 OnScrollListener
        val preloadModelProvider = object : ListPreloader.PreloadModelProvider<MediaItem> {
            override fun getPreloadItems(position: Int): List<MediaItem> {
                val currentList = adapter.currentList
                if (position !in currentList.indices) return emptyList()
                return listOf(currentList[position])
            }

            override fun getPreloadRequestBuilder(item: MediaItem): RequestBuilder<*> {
                return Glide.with(this@AlbumPickerActivity).asBitmap().load(item.uri)
                    .apply(requestOptions.priority(Priority.LOW))
            }
        }

        val sizeProvider = FixedPreloadSizeProvider<MediaItem>(itemPixelSize, itemPixelSize)
        // 提前预取 15 张图片，既保证狂滑时 0ms 命中内存，又绝不把内存打满
        val preloader = RecyclerViewPreloader(
            Glide.with(this), preloadModelProvider, sizeProvider, 15
        )

        binding.recyclerView.apply {
            layoutManager = GridLayoutManager(this@AlbumPickerActivity, spanCount)
            adapter = this@AlbumPickerActivity.adapter
            setHasFixedSize(true)
            setItemViewCacheSize(10) // 经测算，10 个视图缓存能平衡瞬滑吞吐与内存占用
            itemAnimator = null      // 彻底关掉默认动画，消除布局耗时
            addItemDecoration(GridSpaceItemDecoration(spanCount, spacingPx))
            addOnScrollListener(preloader)
        }
    }

    /**
     * 统一的预览拉起器（零 TransactionTooLargeException 隐患）
     */
    private fun openPreviewPage(listToPreview: List<MediaItem>, position: Int) {
        val currentSelectedIds =
            viewModel.uiState.value.selectedItems.map { it.id }.toSet()
        MediaPreview.previewPicker(this, previewLauncher, listToPreview)
            .initPosition(position)
            .selectedIds(currentSelectedIds)
            .maxLimit(viewModel.uiState.value.maxSelectLimit)
            .launch()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // 1. 响应 UI 状态变化
                launch {
                    viewModel.uiState.collect { state ->
                        //  同步数据源（仅在相册切换/初次加载时真正有开销）
                        adapter.submitList(state.mediaList)

                        // 同步选中状态表
                        adapter.updateSelectedItems(state.selectedItems)
                        // 刷新顶部标题为当前相册名（如：所有图片 / 相机 / 截屏）
                        binding.tvAlbumTitle.text = state.currentFolderName
                        //  刷新分类列表
                        folderAdapter.submitList(state.folders)

                        updateBottomBar(state.selectedItems.size, state.maxSelectLimit)
                    }
                }
                // 2. 响应一次性 Side Effect 事件
                launch {
                    viewModel.uiEffect.collect { effect ->
                        when (effect) {
                            is AlbumUiEffect.NotifyItemsChanged -> {
                                if (effect.positions.isEmpty()) {
                                    // 如果传空，说明是从大图预览同步回来的，直接轻量通知全屏刷新 Payload
                                    adapter.notifyItemRangeChanged(
                                        0,
                                        adapter.itemCount,
                                        AlbumAdapter.PAYLOAD_SELECT_STATUS
                                    )
                                } else {
                                    // 如果传了具体位置，只对这几个位置发送 PAYLOAD！触发 Adapter 的 onBindViewHolder(payloads)
                                    effect.positions.forEach { pos ->
                                        adapter.notifyItemChanged(
                                            pos,
                                            AlbumAdapter.PAYLOAD_SELECT_STATUS
                                        )
                                    }
                                }
                            }

                            is AlbumUiEffect.ShowToast -> {
                                showToast(effect.message)
                            }

                            is AlbumUiEffect.ConfirmSelection -> {
                                confirmSelection(effect.selectedUris)
                            }
                        }
                    }
                }
            }
        }
    }

    // 确认提交结果
    private fun confirmSelection(selectedUris: List<Uri>) {
        val resultIntent = Intent().apply {
            putParcelableArrayListExtra(
                MediaPickerContract.EXTRA_SELECTED_MEDIA,
                ArrayList(selectedUris)
            )
        }
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    private fun updateBottomBar(selectedCount: Int, maxLimit: Int) {
        if (selectedCount > 0) {
            binding.btnSend.isEnabled = true
            binding.btnSend.text = "完成($selectedCount/$maxLimit)"
            binding.btnPreview.isEnabled = true
            binding.btnPreview.setTextColor(Color.WHITE)
        } else {
            binding.btnSend.isEnabled = false
            binding.btnSend.text = "完成"
            binding.btnPreview.isEnabled = false
            binding.btnPreview.setTextColor(0xFF888888.toInt())
        }
    }

    private fun setupListeners() {
        onBackPressedDispatcher.addCallback(this, backPressedCallback)

        binding.btnBack.setOnClickListener {
            if (isFolderOpen) {
                toggleFolderDropdown(false)

            } else {
                finish()
            }
        }
        binding.btnCancel.setOnClickListener { finish() }
        binding.btnSend.setOnClickListener { viewModel.submitSelection() }
        // 底部“预览”按钮点击（仅预览当前勾选的图片集合）
        binding.btnPreview.setOnClickListener {
            val selectedList = viewModel.uiState.value.selectedItems
            if (selectedList.isNotEmpty()) {
                openPreviewPage(selectedList, 0) // 从勾选的第 1 张开始看
            }
        }
    }


    private fun checkPermissions() {
        val permissions = when {
            Build.VERSION.SDK_INT >= 34 -> {
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                )
            }

            Build.VERSION.SDK_INT >= 33 -> {
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO
                )
            }

            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val hasPermission = if (Build.VERSION.SDK_INT >= 34) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_MEDIA_IMAGES
            ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_MEDIA_VIDEO
            ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            permissions.all {
                ContextCompat.checkSelfPermission(this, it) ==
                        PackageManager.PERMISSION_GRANTED
            }
        }

        if (hasPermission) {
            viewModel.loadImages()
        } else {
            permissionLauncher.launch(permissions)
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            Glide.get(this).clearMemory() // 系统低内存告警时，自愿清退内存 LRU，防止进程被杀
        }
    }

    private class GridSpaceItemDecoration(
        private val spanCount: Int, private val spacing: Int
    ) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(
            outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State
        ) {
            val position = parent.getChildAdapterPosition(view)
            if (position == RecyclerView.NO_POSITION) return
            val column = position % spanCount
            outRect.left = column * spacing / spanCount
            outRect.right = spacing - (column + 1) * spacing / spanCount
            if (position >= spanCount) {
                outRect.top = spacing
            }
        }
    }
}