package com.example.mediapicker.core.util

import android.app.ActivityManager
import android.content.Context
import android.graphics.Point
import android.os.Build

/**
 *  图片安全采样边界与 RGB_565 决策计算器
 *
 *  高分辨率图片限制在2k以内
 * */
object SafeViewportCalculator {

    // 微信未放大时的真实物理天花板：长边绝对不超过 1920，短边不超过 1080
    private const val WECHAT_MAX_LONG_EDGE = 1920
    private const val WECHAT_MAX_SHORT_EDGE = 1080

    /**
     * 计算绝对安全的大图降采样边界
     * 即使在 4K 屏下，也能把单张大图的内存开销死死焊在 8~10MB 以内
     */
    fun calculateSafeTargetSize(context: Context): Point {
        val dm = context.resources.displayMetrics
        var screenW = dm.widthPixels
        var screenH = dm.heightPixels

        val isPortrait = screenH >= screenW
        val screenLongEdge = if (isPortrait) screenH else screenW
        val screenShortEdge = if (isPortrait) screenW else screenH

        // 计算缩放比：确保长边 <= 1920 且 短边 <= 1080 （3000，1440）
        val longRatio = screenLongEdge.toFloat() / WECHAT_MAX_LONG_EDGE
        val shortRatio = screenShortEdge.toFloat() / WECHAT_MAX_SHORT_EDGE
        val maxRatio = maxOf(longRatio, shortRatio, 1.0f) // 至少为 1.0，小屏不放大

        val targetW = (screenW / maxRatio).toInt()
        val targetH = (screenH / maxRatio).toInt()
        return Point(targetW, targetH)
    }

    /**
     * 判断当前设备是否应该将色深降级为 RGB_565
     */
    fun shouldPreferRgb565(context: Context): Boolean {
        // Android 8.0 之前，或者系统明确标定为 Low-RAM 的低端机，必须降级
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        return am?.isLowRamDevice == true
    }
}