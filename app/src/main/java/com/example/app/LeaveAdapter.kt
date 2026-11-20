package com.example.app


import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.example.app.databinding.ItemLeaveAddBinding
import com.example.app.databinding.ItemLeaveImageBinding
import com.example.mediapicker.engine.glide.FastThumbOptions

sealed interface LeaveItem {
    data class Image(val uri: Uri) : LeaveItem
    data object Add : LeaveItem // “+”号占位项
}

class LeaveAdapter(
    private val itemPixelSize: Int, //按屏幕宽度精确计算好的正方形尺寸
    private val maxCount: Int,
    private val onAddClick: () -> Unit,
    private val onDeleteClick: (Int) -> Unit,
    private val onItemClick: (Int, Uri) -> Unit
) : ListAdapter<LeaveItem, RecyclerView.ViewHolder>(DiffCallback) {

    val requestOptions = RequestOptions()
        .override(itemPixelSize, itemPixelSize)
        .centerCrop()
        .format(DecodeFormat.PREFER_RGB_565)
        .diskCacheStrategy(DiskCacheStrategy.NONE)
        .set(FastThumbOptions.ENABLE_FAST_THUMBNAIL, true) // 设置加载系统缩略图，激活 1ms 系统缩略图通道

    fun submitData(uris: List<Uri>?) {
        val safeUris = uris ?: emptyList()
        val items = mutableListOf<LeaveItem>()
        // 1. 添加真实的图片
        items.addAll(safeUris.map { LeaveItem.Image(it) })
        // 2. 如果未满最大数量，自动在末尾追加一个“+”号
        if (safeUris.size < maxCount) {
            items.add(LeaveItem.Add)
        }
        // 3. 提交给 ListAdapter，底层 DiffUtil 会完美处理动画和时序，绝不报错
        submitList(items)
    }


    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is LeaveItem.Image -> TYPE_IMAGE
            is LeaveItem.Add -> TYPE_ADD
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_ADD -> {
                val binding = ItemLeaveAddBinding.inflate(inflater, parent, false)
                binding.root.layoutParams.width = itemPixelSize
                binding.root.layoutParams.height = itemPixelSize
                AddViewHolder(binding, onAddClick)
            }

            else -> {
                val binding = ItemLeaveImageBinding.inflate(inflater, parent, false)
                binding.root.layoutParams.width = itemPixelSize
                binding.root.layoutParams.height = itemPixelSize
                ImageViewHolder(binding, requestOptions, onItemClick, onDeleteClick)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is ImageViewHolder) {
            holder.bind((getItem(position) as LeaveItem.Image).uri)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        if (holder is ImageViewHolder) {
            holder.onViewRecycled()
        }
    }

    //真实的图片展示 ViewHolder
    class ImageViewHolder(
        private val binding: ItemLeaveImageBinding,
        private val options: RequestOptions,
        private val onItemClick: (Int, Uri) -> Unit,
        private val onDeleteClick: (Int) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(uri: Uri) {
            Glide.with(binding.ivThumbnail.context)
                .asBitmap()//系统缩略图
                .load(uri)
//                .apply(options)
                .into(binding.ivThumbnail)

            // 点击图片看大图
            binding.cardImage.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onItemClick(pos, uri)
                }
            }

            // 点击删除
            binding.btnDelete.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onDeleteClick(pos)
                }
            }
        }

        fun onViewRecycled() {
            Glide.with(binding.ivThumbnail).clear(binding.ivThumbnail)
        }
    }

    // 2. “+号”添加按钮 ViewHolder
    class AddViewHolder(
        binding: ItemLeaveAddBinding,
        onAddClick: () -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener { onAddClick() }
        }
    }


    companion object {
        private const val TYPE_IMAGE = 1
        private const val TYPE_ADD = 2

        private val DiffCallback = object : DiffUtil.ItemCallback<LeaveItem>() {
            override fun areItemsTheSame(oldItem: LeaveItem, newItem: LeaveItem): Boolean {
                return when (oldItem) {
                    is LeaveItem.Image if newItem is LeaveItem.Image -> oldItem.uri == newItem.uri
                    is LeaveItem.Add if newItem is LeaveItem.Add -> true
                    else -> false
                }
            }

            override fun areContentsTheSame(oldItem: LeaveItem, newItem: LeaveItem): Boolean {
                return oldItem == newItem
            }
        }
    }
}