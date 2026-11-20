package com.example.mediapicker.core.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * 现代媒体权限收敛工具：
 * 1. 集中管理全版本（Android 6.0 ~ Android 14+）的权限差异判定
 * 2. 彻底将繁琐的系统版本分支判断移出 Activity / ViewModel
 */
object MediaPermissionHelper {

    // 适配 Android 14 (API 34) 细粒度选择性照片访问权限
    private const val PERMISSION_READ_MEDIA_VISUAL_USER_SELECTED =
        "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"

    /**
     * 根据当前系统版本，获取需要向系统申请的权限清单
     * （直接供给 Activity 的 registerForActivityResult(RequestMultiplePermissions()) 使用）
     */
    fun getRequiredPermissions(): Array<String> {
        return when {
            // Android 14+ (API 34+)：必须包含视觉媒体选择权限
            Build.VERSION.SDK_INT >= 34 -> {
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    PERMISSION_READ_MEDIA_VISUAL_USER_SELECTED
                )
            }
            // Android 13 (API 33 - Tiramisu)：细分为图片和视频
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO
                )
            }
            // Android 12 及以下：传统外置存储读权限
            else -> {
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
    }

    /**
     * 判定是否具备读取相册图片/视频的能力
     */
    fun hasPermissions(context: Context): Boolean {
        return when {
            // Android 14+：如果用户只点击了“选择部分照片”，也视作有权读取！
            Build.VERSION.SDK_INT >= 34 -> {
                val hasImages = isGranted(context, Manifest.permission.READ_MEDIA_IMAGES)
                val hasVideo = isGranted(context, Manifest.permission.READ_MEDIA_VIDEO)
                val hasPartial = isGranted(context, PERMISSION_READ_MEDIA_VISUAL_USER_SELECTED)
                hasImages || hasVideo || hasPartial
            }
            // Android 13
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                isGranted(context, Manifest.permission.READ_MEDIA_IMAGES) ||
                        isGranted(context, Manifest.permission.READ_MEDIA_VIDEO)
            }
            // Android 12 及以下
            else -> {
                isGranted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
    }

    /**
     * 判定是否处于 Android 14 的“受限访问/部分授权”状态（Partial Access）
     * 业务如果想在顶部弹窗提示“您当前仅授权了部分照片，点击去授权全部”，直接调这个！
     */
    fun isPartialAccessGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 34) {
            val hasFullImages = isGranted(context, Manifest.permission.READ_MEDIA_IMAGES)
            val hasPartial = isGranted(context, PERMISSION_READ_MEDIA_VISUAL_USER_SELECTED)
            // 拥有部分照片权限，但没有授予完整相册权限
            return hasPartial && !hasFullImages
        }
        return false
    }

    /**
     * 解析 RequestMultiplePermissions 返回的 Map 结果，判定最终是否放行
     */
    fun isPermissionResultGranted(permissions: Map<String, Boolean>): Boolean {
        return if (Build.VERSION.SDK_INT >= 34) {
            permissions[Manifest.permission.READ_MEDIA_IMAGES] == true ||
                    permissions[Manifest.permission.READ_MEDIA_VIDEO] == true ||
                    permissions[PERMISSION_READ_MEDIA_VISUAL_USER_SELECTED] == true
        } else {
            permissions.values.any { it }
        }
    }

    /**
     * 跳转至当前 App 的系统权限设置页（用于用户勾选“不再询问”彻底拒绝后的挽回机制）
     */
    fun openAppSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun isGranted(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}