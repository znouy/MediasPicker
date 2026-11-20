package com.example.mediapicker.feature.picker

import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.example.mediapicker.data.model.AlbumFolder
import com.example.mediapicker.engine.glide.FastThumbOptions
import com.example.mediapicker.databinding.ItemAlbumFolderBinding

class AlbumFolderAdapter(
    private val onFolderClicked: (AlbumFolder) -> Unit
) : ListAdapter<AlbumFolder, AlbumFolderAdapter.FolderViewHolder>(FolderDiffCallback) {
    private var requestOptions: RequestOptions? = null
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FolderViewHolder {
        if (requestOptions == null) {
            val density = parent.resources.displayMetrics.density
            val coverPx = (56 * density).toInt()
            // ：目录列表封面同样严格走 1ms 缩略图直通通道 + 尺寸严格锁死 56dp
            requestOptions = RequestOptions()
                .override(coverPx, coverPx)
                .centerCrop()
                .format(DecodeFormat.PREFER_RGB_565)
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .set(FastThumbOptions.ENABLE_FAST_THUMBNAIL, true)
        }

        val binding = ItemAlbumFolderBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        Log.e("AlbumFolderAdapter", "-------------onCreateViewHolder-------")
        return FolderViewHolder(binding, requestOptions, onFolderClicked)
    }

    override fun onBindViewHolder(holder: FolderViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class FolderViewHolder(
        private val binding: ItemAlbumFolderBinding,
        private val requestOptions: RequestOptions?,
        private val onFolderClicked: (AlbumFolder) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(folder: AlbumFolder) {
            Log.e("FolderViewHolder", "-------------bind-------")
            binding.tvFolderName.text = folder.name
            binding.tvPhotoCount.text = "(${folder.count})"
            binding.ivCheck.isVisible = folder.isSelected

            requestOptions?.let {
                Glide.with(binding.ivCover.context)
                    .asBitmap()
                    .load(folder.coverUri)
                    .apply(it)
                    .into(binding.ivCover)
            }

            binding.root.setOnClickListener {
                onFolderClicked(folder)
            }
        }
    }

    companion object {
        private val FolderDiffCallback = object : DiffUtil.ItemCallback<AlbumFolder>() {
            override fun areItemsTheSame(oldItem: AlbumFolder, newItem: AlbumFolder): Boolean =
                oldItem.bucketId == newItem.bucketId

            override fun areContentsTheSame(oldItem: AlbumFolder, newItem: AlbumFolder): Boolean =
                oldItem.isSelected == newItem.isSelected && oldItem.count == newItem.count
        }
    }
}

