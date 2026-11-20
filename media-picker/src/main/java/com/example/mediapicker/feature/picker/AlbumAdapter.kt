package com.example.mediapicker.feature.picker

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.example.mediapicker.R
import com.example.mediapicker.monitor.AlbumHudTracker
import com.example.mediapicker.core.util.formatVideoDuration
import com.example.mediapicker.data.model.MediaItem
import com.example.mediapicker.databinding.ItemAlbumPhotoBinding
import kotlin.collections.mapIndexed

class AlbumAdapter(
    private val isSingleMode: Boolean,
    private val requestOptions: RequestOptions,
    private val itemPixelSize: Int,
    private val onToggleSelect: (MediaItem, Int) -> Unit,
    private val onItemClick: (MediaItem, Int) -> Unit
) : ListAdapter<MediaItem, AlbumAdapter.PhotoViewHolder>(DiffCallback) {
    // 外部同步进来的轻量选中映射表：Key = MediaId, Value = 选中的序号(1-based)
    private var selectedMap: Map<Long, Int> = emptyMap()
    fun updateSelectedItems(selectedList: List<MediaItem>) {
        selectedMap = selectedList.mapIndexed { index, item -> item.id to (index + 1) }.toMap()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
        val binding = ItemAlbumPhotoBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        // 关键性能：锁死尺寸，消灭滑动排版重绘
        binding.root.layoutParams.height = itemPixelSize
        return PhotoViewHolder(binding, requestOptions, onToggleSelect, isSingleMode)
    }

    override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
        val item = getItem(position)
        val selectIndex = selectedMap[item.id] ?: -1
        holder.bind(item, selectIndex)
        // 在 AlbumAdapter.kt 中：
        holder.itemView.setOnClickListener {
            onItemClick.invoke(item, position)
        }
    }

    override fun onBindViewHolder(
        holder: PhotoViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.isNotEmpty() && payloads.contains(PAYLOAD_SELECT_STATUS)) {
            // 纯角标与遮罩更新，绝不打扰 ImageView，图片完全不闪烁！
            val item = getItem(position)
            val selectIndex = selectedMap[item.id] ?: -1
            holder.updateSelectUi(selectIndex)
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    class PhotoViewHolder(
        private val binding: ItemAlbumPhotoBinding,
        private val requestOptions: RequestOptions,
        private val onToggleSelect: (MediaItem, Int) -> Unit,
        private val isSingleMode: Boolean
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: MediaItem, selectIndex: Int) {
            // hud: 累计请求总数
            AlbumHudTracker.totalRequests.incrementAndGet()
            //hud
            val requestListener = object : RequestListener<Bitmap> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Bitmap>,
                    isFirst: Boolean
                ): Boolean = false

                override fun onResourceReady(
                    resource: Bitmap,
                    model: Any,
                    target: Target<Bitmap>?,
                    dataSource: DataSource,
                    isFirst: Boolean
                ): Boolean {
                    if (dataSource == DataSource.MEMORY_CACHE) {
                        //如果是从内存缓存（LruCache）直接返回的，累计命中数
                        AlbumHudTracker.hitRequests.incrementAndGet()
                    }
                    return false
                }
            }

            // 核心：asBitmap 直通系统缩略图
            Glide.with(binding.ivThumb.context)
                .asBitmap()
                .load(item.uri)
                .apply(requestOptions.priority(Priority.IMMEDIATE))
                .listener(requestListener)
                .into(binding.ivThumb)

            if (isSingleMode) {
                // 单选模式
                binding.layoutSelectHotspot.isVisible = false
                binding.viewMask.isVisible = false
            } else {
                binding.layoutSelectHotspot.isVisible = true
                updateSelectUi(selectIndex)
                //点击右上角圆圈，把当前的绝对 position 传回给 ViewModel
                binding.layoutSelectHotspot.setOnClickListener {
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        onToggleSelect(item, pos)
                    }
                }
            }

            //  判断是否为视频并设置时长展示
            if (item.isVideo) {
                binding.tvVideoDuration.isVisible = true
                binding.tvVideoDuration.text = item.duration.formatVideoDuration()
                binding.tvGifTag.isVisible = false
            } else {
                binding.tvVideoDuration.isVisible = false
                binding.tvGifTag.isVisible = item.mimeType.contains("gif", ignoreCase = true)
            }

        }

        fun updateSelectUi(selectIndex: Int) {
            val isSelected = selectIndex != -1
            binding.viewMask.isVisible = isSelected
            if (isSelected) {
                binding.tvSelectBadge.setBackgroundResource(R.drawable.bg_select_badge_selected)
                binding.tvSelectBadge.text = selectIndex.toString()
            } else {
                binding.tvSelectBadge.setBackgroundResource(R.drawable.bg_select_badge_unselected)
                binding.tvSelectBadge.text = ""
            }
        }
    }

    override fun onViewRecycled(holder: PhotoViewHolder) {
        super.onViewRecycled(holder)
        val binding = ItemAlbumPhotoBinding.bind(holder.itemView)
        Glide.with(binding.ivThumb.context).clear(binding.ivThumb)
    }

    companion object {
        const val PAYLOAD_SELECT_STATUS = "PAYLOAD_SELECT_STATUS"

        private val DiffCallback = object : DiffUtil.ItemCallback<MediaItem>() {
            override fun areItemsTheSame(oldItem: MediaItem, newItem: MediaItem): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(oldItem: MediaItem, newItem: MediaItem): Boolean {
                return oldItem == newItem
            }
        }
    }
}