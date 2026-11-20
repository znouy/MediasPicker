package com.example.mediapicker.core.extension


import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat

/**
 * 像素转换：dp 转 px
 */
fun Context.dp2px(dpValue: Float): Int {
    val scale = resources.displayMetrics.density
    return (dpValue * scale + 0.5f).toInt()
}

/**
 * 屏幕宽度像素
 */
val Context.screenWidthPx: Int
    get() = resources.displayMetrics.widthPixels

/**
 * 屏幕高度像素
 */
val Context.screenHeightPx: Int
    get() = resources.displayMetrics.heightPixels

/**
 * 安全获取资源颜色
 */
fun Context.getCompatColor(@ColorRes id: Int): Int {
    return ContextCompat.getColor(this, id)
}

/**
 * 轻量 Toast 提示
 */
fun Context.showToast(message: CharSequence, duration: Int = Toast.LENGTH_SHORT) {
    Toast.makeText(this, message, duration).show()
}

fun Context.showToast(@StringRes resId: Int, duration: Int = Toast.LENGTH_SHORT) {
    Toast.makeText(this, resId, duration).show()
}

/**
 * 针对相册库定制的“全版本媒体权限检查”
 * 完美覆盖 Android 14 (API 34) 的“选择性访问”与 Android 13 细粒度媒体权限
 */
fun Context.hasMediaPermissions(): Boolean {
    return when {
        // Android 14+：如果被授予了选择性照片权限，也算拥有读取媒体的能力
        Build.VERSION.SDK_INT >= 34 -> {
            val hasImages = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
            val hasVideo = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
            val hasPartial = ContextCompat.checkSelfPermission(this, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED") == PackageManager.PERMISSION_GRANTED
            hasImages || hasVideo || hasPartial
        }
        // Android 13 (Tiramisu)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
            val hasImages = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
            val hasVideo = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
            hasImages || hasVideo
        }
        // Android 12 及以下
        else -> {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }
}