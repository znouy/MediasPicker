package com.example.mediapicker.feature.preview

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.example.mediapicker.R
import com.example.mediapicker.databinding.ActivityImagePreviewBinding
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.load.DecodeFormat
import com.example.mediapicker.core.extension.showToast
import com.example.mediapicker.feature.preview.holder.PreviewDataHolder
import com.example.mediapicker.feature.preview.player.PreviewPlayerController
import com.example.mediapicker.core.util.SafeViewportCalculator
import kotlinx.coroutines.launch

/**
 * 大图/视频预览页
 * */
class ImagePreviewActivity : AppCompatActivity() {
    private val viewModel: ImagePreviewViewModel by viewModels()
    private lateinit var binding: ActivityImagePreviewBinding
    private lateinit var previewAdapter: PreviewAdapter

    // 创建预览视频播放器，把当前 Activity 作为 LifecycleOwner 传进去！
    private val playerController by lazy {
        PreviewPlayerController(this, this)
    }

    companion object {
        const val EXTRA_PREVIEW_MODE = "EXTRA_PREVIEW_MODE"
        const val EXTRA_INIT_POSITION = "EXTRA_INIT_POSITION"//点击进入预览时的角标
        const val EXTRA_MAX_LIMIT = "EXTRA_MAX_LIMIT"
        const val EXTRA_SELECTED_IDS = "EXTRA_SELECTED_IDS"

        const val RESULT_SELECTED_IDS = "RESULT_SELECTED_IDS"
        const val RESULT_IS_CONFIRM = "RESULT_IS_CONFIRM"
        const val RESULT_REMAINING_URIS = "RESULT_REMAINING_URIS"
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ：系统回收重建时，静态内存已丢失，优雅退出，杜绝白屏/NPE
        if (!PreviewDataHolder.hasData()) {
            Toast.makeText(this, "图片数据已失效，请重新选择", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        //沉浸式查看大图 防抖动：强制窗口自始至终占领屏幕两端的短边（包含挖孔刘海区域）
        if (Build.VERSION.SDK_INT >= 29) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        //为false时会铺满全屏，视图坐标直接从物理屏幕的最顶端 (0, 0) 绘制到最底端 (screenWidth, screenHeight)
        //为ture视图坐标只占中间的内容区域（Android 屏幕有三个层级：状态栏（时间/电量）、中间的内容区、底部的导航栏（手势小白条））
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT



        binding = ActivityImagePreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 解决layoutTopBar和bottomBar和状态栏导航栏挤压和错位
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val navBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            if (statusBarHeight > 0) {
                // 顶栏往下推状态栏高度，彻底离开时间/电量区域
                binding.layoutTopBar.setPadding(
                    binding.layoutTopBar.paddingLeft,
                    statusBarHeight, // 👈 动态给上内边距
                    binding.layoutTopBar.paddingRight,
                    binding.layoutTopBar.paddingBottom
                )

                // 底栏往上垫手势条高度，完成按钮饱满露出
                binding.layoutBottomBar.setPadding(
                    binding.layoutBottomBar.paddingLeft,
                    binding.layoutBottomBar.paddingTop,
                    binding.layoutBottomBar.paddingRight,
                    navBarHeight + 10 // 👈 动态给下内边距
                )
            }

            insets
        }

        initViews()
        setupViewPager()
        observeViewModel()

        if (savedInstanceState == null) {
            val initPos = intent.getIntExtra(EXTRA_INIT_POSITION, 0)
            val maxLimit = intent.getIntExtra(EXTRA_MAX_LIMIT, 9)
            val selectedIds = intent.getLongArrayExtra(EXTRA_SELECTED_IDS)
            // 解析预览模式，默认是 PICKER 模式
            val mode = if (Build.VERSION.SDK_INT >= 33) {
                intent.getSerializableExtra(EXTRA_PREVIEW_MODE, PreviewMode::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getSerializableExtra(EXTRA_PREVIEW_MODE) as? PreviewMode
            } ?: PreviewMode.PICKER

            viewModel.initData(initPos, maxLimit, selectedIds, mode)
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.uiState.collect { renderState(it) } }
                launch { viewModel.uiEffect.collect { handleEffect(it) } }
            }
        }
    }

    private fun renderState(state: PreviewUiState) {
        if (state.mediaList.isEmpty()) {
            // TODO:  
            finish()
            return
        }
        // 三种预览模式 UI 分流控制
        when (state.previewMode) {
            // 模式 1：相册挑选
            PreviewMode.PICKER -> {
                binding.layoutSelectHotspot.isVisible = true
                binding.layoutDeleteHotspot.isVisible = false
                binding.layoutBottomBar.isVisible = true

                // 更新角标数字与完成按钮
                val selectIndex = state.currentSelectIndex
                if (selectIndex != -1) {
                    binding.tvSelectBadge.setBackgroundResource(R.drawable.bg_select_badge_selected)
                    binding.tvSelectBadge.text = selectIndex.toString()
                } else {
                    binding.tvSelectBadge.setBackgroundResource(R.drawable.bg_select_badge_unselected)
                    binding.tvSelectBadge.text = ""
                }

                if (state.selectedCount > 0) {
                    binding.btnSend.isEnabled = true
                    binding.btnSend.text = "完成(${state.selectedCount}/${state.maxSelectLimit})"
                } else {
                    binding.btnSend.isEnabled = false
                    binding.btnSend.text = "完成"
                }
            }

            // 模式 2：表单带删除预览
            PreviewMode.FORM -> {
                binding.layoutSelectHotspot.isVisible = false
                binding.layoutDeleteHotspot.isVisible = true
                binding.layoutBottomBar.isVisible = false
            }

            //模式 3：纯只读大图预览（右上角两套控件全部隐藏，底栏隐藏）
            PreviewMode.READ -> {
                binding.layoutSelectHotspot.isVisible = false
                binding.layoutDeleteHotspot.isVisible = false
                binding.layoutBottomBar.isVisible = false
            }
        }

        // 数据装载
        if (previewAdapter.currentList != state.mediaList) {
            previewAdapter.submitList(state.mediaList) {
                binding.viewPager.setCurrentItem(state.currentPosition, false)
            }
        }

        // 标题页码：1 / 4（所有模式均展示）
        binding.tvTitleIndex.text = "${state.currentPosition + 1} / ${state.totalCount}"

        // 控制沉浸式显隐
        binding.layoutTopBar.isVisible = state.isSystemUiVisible
        if (state.previewMode == PreviewMode.PICKER) {
            binding.layoutBottomBar.isVisible = state.isSystemUiVisible
        }
        if (state.isSystemUiVisible) {
            // 显示顶栏时：把时间、电量显示出来
            insetsController.show(WindowInsetsCompat.Type.statusBars())
        } else {
            // 单击屏幕隐藏顶栏时：彻底隐藏状态栏，进入 100% 极致纯全屏！
            insetsController.hide(WindowInsetsCompat.Type.statusBars())
        }

    }


    // 1. 获取官方系统窗口控制器
    private val insetsController by lazy {
        WindowCompat.getInsetsController(window, window.decorView)
    }

    private fun handleEffect(effect: PreviewUiEffect) {
        when (effect) {
            is PreviewUiEffect.ShowToast -> {
                showToast(effect.message)
            }
            //相册内浏览模式
            is PreviewUiEffect.FinishWithPickerResult -> {
                val resultIntent = Intent().apply {
                    putExtra(RESULT_IS_CONFIRM, effect.isConfirm)
                    putExtra(RESULT_SELECTED_IDS, effect.selectedIds)
                }
                setResult(RESULT_OK, resultIntent)
                finish()
            }
            //表单删除浏览模式
            is PreviewUiEffect.FinishWithFormResult -> {
                val resultIntent = Intent().apply {
                    putParcelableArrayListExtra(
                        RESULT_REMAINING_URIS,
                        ArrayList(effect.remainingUris)
                    )
                }
                setResult(RESULT_OK, resultIntent)
                finish()
            }
            // 纯预览直接退出
            is PreviewUiEffect.FinishDirectly -> {
                finish()
            }
        }
    }

    private fun initViews() {
        binding.btnBack.setOnClickListener { viewModel.handleBackPress() }
        // 点击右上角圆圈：切换当前图片的选中状态
        binding.layoutSelectHotspot.setOnClickListener { viewModel.toggleCurrentSelect() }
        // 点击右下角“完成”按钮：直接携带结果退回调用方
        binding.btnSend.setOnClickListener { viewModel.confirmSelection(isConfirm = true) }
        // 表单浏览删除模式：点击右上角垃圾桶直接删除当前图片！
        binding.layoutDeleteHotspot.setOnClickListener { viewModel.deleteCurrentItem() }
        // 系统物理返回键接管
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                viewModel.handleBackPress()
            }
        })
    }

    private fun setupViewPager() {
        val safeSize = SafeViewportCalculator.calculateSafeTargetSize(applicationContext)
        val decodeFormat = if (SafeViewportCalculator.shouldPreferRgb565(applicationContext)) {
            DecodeFormat.PREFER_RGB_565
        } else {
            DecodeFormat.PREFER_ARGB_8888
        }
        previewAdapter = PreviewAdapter(playerController, safeSize, decodeFormat) {
            // 切换顶部/底部操作栏的显隐（沉浸式切换）
            viewModel.toggleSystemUi()
        }

        binding.viewPager.apply {
            adapter = previewAdapter
            offscreenPageLimit = 1//会缓存前后一页

            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    viewModel.onPageChanged(position)
                    // 页面选定后，触发当前正在屏幕中央的 ViewHolder
                    triggerActivePage(position)
                }
            })
        }
    }

    /**
     * 核心激活函数：直接获取当前屏幕正中正在展示的 View，通知其开始解码
     */
    private fun triggerActivePage(position: Int) {
        val innerRv = binding.viewPager.getChildAt(0) as? RecyclerView ?: return
        val holder =
            innerRv.findViewHolderForAdapterPosition(position) as? PreviewAdapter.PreviewViewHolder
        if (holder != null) {
            holder.onBecomeActive()
        } else {
            // 如果初次排版还没完成，下一帧排版完毕立即触发
            innerRv.post {
                val retryHolder =
                    innerRv.findViewHolderForAdapterPosition(position) as? PreviewAdapter.PreviewViewHolder
                retryHolder?.onBecomeActive()
            }
        }
    }

    /**
     * 低内存自愿退让
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            // 内存极度紧张时，主动清空 Glide 内存缓存，避免整个进程被系统杀掉
            Glide.get(this).clearMemory()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        //不能直接调用 PreviewDataHolder.clear()
        // 当大图预览界面发生屏幕旋转（横竖屏切换）或系统暗色模式切换时，Activity 会触发重建
        // onDestroy 执行时直接将单例 PreviewDataHolder 清空了！重建后的 onCreate 检查到 !hasData()，
        // 用户会被瞬间弹回相册，并收到“数据已失效”的报错。
        if (isFinishing) {//只有在 Activity 真正退出而不是配置变更重建时才清空
            PreviewDataHolder.clear()
        }
    }
}