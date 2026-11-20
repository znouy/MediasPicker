package com.example.app

import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.request.RequestOptions
import com.example.app.databinding.ActivityUserHeadBinding
import com.example.mediapicker.MediaPicker
import com.example.mediapicker.MediaPreview
import com.example.mediapicker.core.extension.showToast

class UserHeadActivity : AppCompatActivity() {
    private lateinit var binding: ActivityUserHeadBinding

    // 内存中缓存当前头像的 URI
    private var currentAvatarUri: Uri? = null

    private val avatarPickerLauncher = MediaPicker.register(this) { uris ->
        val pickedUri = uris.firstOrNull()
        if (pickedUri != null) {
            currentAvatarUri = pickedUri
            displayAvatar(pickedUri)
            showToast("头像更换成功")
        }
    }

    private fun displayAvatar(uri: Uri) {
        Glide.with(this)
            .load(uri)
            .apply(RequestOptions().transform(CenterCrop()))
            .into(binding.ivAvatar)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUserHeadBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initViews()
        initData()
    }

    private fun initData() {
    }

    private fun initViews() {
        binding.ivAvatar.setOnClickListener {
            val uri = currentAvatarUri
            if (uri != null) {
                // 启动纯只读大图，无右上角删除/勾选
                MediaPreview.previewRead(this, listOf(uri))
                    .initPosition(0)
                    .launch()
            } else {
                // 如果当前没有头像，点击直接调起相册去选
                openAvatarPicker()
            }
        }
        binding.btnChangeAvatar.setOnClickListener {
            openAvatarPicker()
        }
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    /**
     * 单选模式带裁剪启动相册
     */
    private fun openAvatarPicker() {
        MediaPicker.pickSingle(avatarPickerLauncher)
            .crop()
            .showDebugHud(true)
            .launch()
    }
}