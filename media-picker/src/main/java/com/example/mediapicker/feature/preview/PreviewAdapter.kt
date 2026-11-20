package com.example.mediapicker.feature.preview

import com.example.mediapicker.engine.glide.FastThumbOptions
import com.example.mediapicker.databinding.ItemPreviewPageBinding
import android.content.Context
import android.graphics.Point
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.core.view.isVisible
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.example.mediapicker.R
import com.example.mediapicker.engine.media.ImageDimensionDetector
import com.example.mediapicker.feature.preview.player.PreviewPlayerController
import com.example.mediapicker.data.model.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class)
class PreviewAdapter
    (
    // 改动：把页面作用域的播放器控制器注入进 Adapter
    private val playerController: PreviewPlayerController,
    private val safeSize: Point,
    private val decodeFormat: DecodeFormat,
    private val onPhotoClick: () -> Unit
) : ListAdapter<MediaItem, PreviewAdapter.PreviewViewHolder>(DiffCallback) {


    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PreviewViewHolder {
        val binding = ItemPreviewPageBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return PreviewViewHolder(binding)
    }


    override fun onBindViewHolder(holder: PreviewViewHolder, position: Int) {
        Log.e(
            "PreviewAdapter",
            "onBindViewHolder():position=$position "
        )
        holder.bind(getItem(position))
    }

    override fun onViewDetachedFromWindow(holder: PreviewViewHolder) {
        super.onViewDetachedFromWindow(holder)
        Log.e("PreviewAdapter", "onViewDetachedFromWindow holder=${holder.bindingAdapterPosition}")
        //  离屏脱钩：只要脱离了屏幕视口，100% 立即释放高清大图显存与停止视频
        holder.recycleResource()
    }

    override fun onViewRecycled(holder: PreviewViewHolder) {
        super.onViewRecycled(holder)
        Log.e("PreviewAdapter", "onViewRecycled holder=${holder.bindingAdapterPosition}")

        // ：离开视口的页面，立刻解除大图资源占用，退还物理内存
        holder.recycleResource()
    }


    inner class PreviewViewHolder(
        val binding: ItemPreviewPageBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        // 每个 Holder 绑定独立协程 Job，防止复用串图
        private var renderJob: Job? = null
        private var currentItem: MediaItem? = null

        fun bind(item: MediaItem) {
            cancelJob()
            currentItem = item

            // 设置点击手势（单击退出沉浸式/返回）
            binding.photoView.setOnPhotoTapListener { _, _, _ -> onPhotoClick() }
            binding.subsamplingView.setOnClickListener { onPhotoClick() }

            // 1. 先把列表原有的微缩图展示出来（微缩图铺底，消除瞬时黑块）
            binding.ivPlaceholderThumb.isVisible = true
            Glide.with(binding.ivPlaceholderThumb.context).asBitmap().load(item.uri)
                // 开启网格通行证，瞬间从内存 LruCache 中把微缩图拿出来铺在底层
                .apply(RequestOptions().set(FastThumbOptions.ENABLE_FAST_THUMBNAIL, true))
                .into(binding.ivPlaceholderThumb)

            binding.loadingBar.isVisible = false
            //  初始化重置所有高清/切片图状态
            binding.photoView.isVisible = false
            binding.subsamplingView.isVisible = false

            // 根据media类型处理
            if (item.isVideo) {//视频模式处理
                //显示视频
                binding.videoFrameContainer.isVisible = true
                binding.ivPlayCenter.isVisible = true
                binding.ivPlayCenter.setImageResource(R.drawable.ic_video_play)

                // 点击播放按钮或点击视频区域：触发播放 / 暂停切换
                val togglePlayAction = { toggleVideoPlay(item) }

                binding.ivPlayCenter.setOnClickListener { togglePlayAction() }
                binding.videoTextureView.setOnClickListener {
                    // 轻触已在播放的视频，切换顶栏底栏显隐；或者再次点击暂停
                    if (playerController.isPlaying()) {
                        onPhotoClick()
                    } else {
                        togglePlayAction()
                    }
                }
            } else {
                // 图片模式：关掉视频层
                binding.videoFrameContainer.isVisible = false
                binding.ivPlayCenter.isVisible = false
            }
        }

        /**
         * 加载高清图
         *
         * 唯一激活入口：只在当前页面真正落定居中时，由 ViewPager2 精准唤醒
         * */
        fun onBecomeActive() {
            val item = currentItem ?: return
            Log.e("PreviewAdapter", "onBecomeActive 真正拉起大图: position=$bindingAdapterPosition")
            if (item.isVideo) {
                binding.ivPlayCenter.isVisible = true
            } else {
                if (isLoadedOrLoading()) return
                detectAndLoadImage(binding.root.context, item)
            }
        }

        private fun isLoadedOrLoading(): Boolean {
            val isJobActive = renderJob?.isActive == true
            val hasPhoto = binding.photoView.drawable != null
            val hasSubsampling = binding.subsamplingView.isReady
            return isJobActive || hasPhoto || hasSubsampling
        }

        private fun toggleVideoPlay(item: MediaItem) {
            if (playerController.isPlaying()) {
                playerController.pause()
                binding.ivPlayCenter.isVisible = true
                binding.ivPlayCenter.setImageResource(R.drawable.ic_video_play)
            } else {
                // 开始播放
                binding.loadingBar.isVisible = true
                binding.ivPlayCenter.isVisible = false // 隐藏播放图标

                // 防闪1：0ms 提前锁定容器比例！(防止闪屏：点击播放瞬间全屏到正常尺寸)
                // 直接读取正在显示的封面缩略图 Drawable 的真实物理比例
                val coverDrawable = binding.ivPlaceholderThumb.drawable
                if (coverDrawable != null && coverDrawable.intrinsicWidth > 0 && coverDrawable.intrinsicHeight > 0) {
                    val preRatio =
                        coverDrawable.intrinsicWidth.toFloat() / coverDrawable.intrinsicHeight.toFloat()
                    // 此时直接把比例锁死！容器一开始就是 16:9，绝对不可能撑满全屏！
                    binding.videoFrameContainer.setAspectRatio(preRatio)
                }
                playerController.play(
                    item.uri,
                    binding.videoTextureView,
                    onVideoSizeRatioChanged = { ratio ->
                        // 瞬间将容器尺寸约束在视频真实的尺寸，杜绝被撑满全屏拉伸！
                        binding.videoFrameContainer.setAspectRatio(ratio)
                    },
                    onFirstFrameRendered = {
                        // 防闪：视频首帧就绪，此时隐藏封面图与加载
                        binding.loadingBar.isVisible = false
                        binding.ivPlaceholderThumb.isVisible = false
                    },
                    onStateChanged = { isPlaying ->
                        if (!isPlaying && !playerController.isPlaying()) {
                            binding.ivPlayCenter.isVisible = true
                            binding.ivPlayCenter.setImageResource(R.drawable.ic_video_play)
                        }
                    })
            }
        }


        private fun detectAndLoadImage(context: Context, item: MediaItem) {
            cancelJob()
            //直接基于 ViewTree 获取宿主生命周期
            val lifecycleScope = binding.root.findViewTreeLifecycleOwner()?.lifecycleScope ?: return
            // 3. 异步分析尺寸，决定走哪条通道
            renderJob = lifecycleScope.launch {
                binding.loadingBar.isVisible = true

                // 异步分析长宽与 Exif 旋转
                val spec = withContext(Dispatchers.IO) {
                    ImageDimensionDetector.analyzeImage(context, item.uri, item.mimeType)
                }
                // 协程唤醒校验：如果当前 holder 在 I/O 期间被划走复用了，立即退出！
                // 如果 isActive 是 false，说明这个 ViewHolder 早就被划走或者被复用了！
                // 绝对不能再去拿刚才算出来的旧数据去更新 View，直接退出协程，丢弃脏数据！
                if (!this.isActive) return@launch

                //更新ui：真正的显示高清图
                if (spec.isHugeOrLongImage) {
                    // 通道 A：极端长图/巨型大图 -> 切片瓦片加载（BitmapRegionDecoder）
                    renderWithSubsampling(item, spec.orientation)
                } else {
                    // 通道 B：普通大图 -> PhotoView +  1080P 天花板防 OOM
                    renderWithPhotoView(context, item)
                }
            }
        }

        private fun renderWithPhotoView(context: Context, item: MediaItem) {
            binding.subsamplingView.isVisible = false
            binding.photoView.isVisible = true

            Glide.with(context).load(item.uri)
                // 核心防 OOM：设置尺寸基准框，绝不加载按原始高分辨率 ， 即使是 4K 屏，也锁死在安全尺寸内（1080p）
                // 核心：直接使用 Adapter 顶层 lazy 计算好的不变参数，消除每次重复计算屏幕指标
                .override(safeSize.x, safeSize.y)//图片按照此尺寸设置采样率
                //只缩小防 OOM，小图坚决不放大，省内存保画质
                .downsample(DownsampleStrategy.CENTER_INSIDE)
                // 等比居中，保全图片真实长宽比，绝不拉伸变形
                .fitCenter()
                // 核心：低端机/低版本动态降级色彩格式
                .format(decodeFormat).diskCacheStrategy(DiskCacheStrategy.NONE)
                .priority(Priority.IMMEDIATE).listener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(
                        e: GlideException?, model: Any?, target: Target<Drawable>, isFirst: Boolean
                    ): Boolean {
                        binding.loadingBar.isVisible = false
                        return false
                    }

                    override fun onResourceReady(
                        resource: Drawable,
                        model: Any,
                        target: Target<Drawable>?,
                        dataSource: DataSource,
                        isFirst: Boolean
                    ): Boolean {
                        binding.loadingBar.isVisible = false
                        // 高清大图成功就绪，隐藏微缩垫底层
                        binding.ivPlaceholderThumb.isVisible = false
                        return false
                    }
                }).into(binding.photoView)
        }

        private fun renderWithSubsampling(item: MediaItem, orientation: Int) {
            binding.photoView.isVisible = false
            binding.subsamplingView.isVisible = true

            // 启用切片加载器
            binding.subsamplingView.apply {
                // 限制最大瓦片尺寸，防止单片过大打满显存
                setMaxTileSize(2048)
                // 设定双击缩放级别
                setDoubleTapZoomScale(2.0f)
                // 解决图片倒躺问题
                setOrientation(
                    when (orientation) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> SubsamplingScaleImageView.ORIENTATION_90
                        ExifInterface.ORIENTATION_ROTATE_180 -> SubsamplingScaleImageView.ORIENTATION_180
                        ExifInterface.ORIENTATION_ROTATE_270 -> SubsamplingScaleImageView.ORIENTATION_270
                        else -> SubsamplingScaleImageView.ORIENTATION_0
                    }
                )
                // 绑定 Uri（底层走 BitmapRegionDecoder 异步分块，视口移动即解码）
                setImage(ImageSource.uri(item.uri))
                setOnImageEventListener(object :
                    SubsamplingScaleImageView.DefaultOnImageEventListener() {
                    override fun onReady() {
                        binding.loadingBar.isVisible = false
                        binding.ivPlaceholderThumb.isVisible = false
                    }

                    override fun onImageLoadError(e: Exception?) {
                        binding.loadingBar.isVisible = false
                        // 如果切片加载失败，优雅回退到 PhotoView
                        renderWithPhotoView(context, item)
                    }
                })
            }
        }

        private fun cancelJob() {
            renderJob?.cancel()
            renderJob = null
        }

        fun recycleResource() {
            // 滑出屏幕打断解析协程
            cancelJob()

            // 清空 PhotoView
            Glide.with(binding.root.context).clear(binding.photoView)
            binding.photoView.setImageDrawable(null)
            binding.photoView.isVisible = false

            // 回收瓦片解码器-重置与释放 SubsamplingView（释放底层 Bitmap 切片池）
            binding.subsamplingView.setOnImageEventListener(null) // 断开切片库监听
            if (binding.subsamplingView.isVisible) {
                binding.subsamplingView.recycle()
                binding.subsamplingView.isVisible = false

            }
            // 停止视频播放
            if (currentItem?.isVideo == true) {
                playerController.stopAndDetach()
                binding.ivPlayCenter.isVisible = true
                binding.ivPlayCenter.setImageResource(R.drawable.ic_video_play)
                // 彻底切断 TextureView 的渲染通道，释放底层硬件图层 FrameBuffer
                binding.videoTextureView.visibility =
                    View.GONE//extureView 内部会立刻触发destroyHardwareLayer(); //  摧毁硬件渲染图层！
                //底层的 Linux 驱动会瞬间释放绑定的 GraphicBuffer 帧缓冲区，退还显存，并且彻底擦除残留在屏幕上的最后一帧画面
                binding.videoTextureView.visibility = View.VISIBLE
                //TextureView 被重新标记为可见，但此时它的内部是一个全新的、干净的、空的初始状态；
            }

            // 重新显示微缩垫底图，避免快速回滑时瞬时黑块
            binding.ivPlaceholderThumb.isVisible = true
            binding.loadingBar.isVisible = false
        }
    }

    companion object {
        // 定义激活态 Payload 标识
        const val PAYLOAD_ACTIVE_STATE = "PAYLOAD_ACTIVE_STATE"
        private val DiffCallback = object : DiffUtil.ItemCallback<MediaItem>() {
            override fun areItemsTheSame(oldItem: MediaItem, newItem: MediaItem): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: MediaItem, newItem: MediaItem): Boolean =
                oldItem == newItem
        }
    }
}