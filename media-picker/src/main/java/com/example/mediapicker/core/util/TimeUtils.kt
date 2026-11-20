package com.example.mediapicker.core.util

import java.util.Locale

/**
 * 将毫秒时长格式化为微信风格的时间字符（如 00:15 / 03:45 / 01:12:05）
 */
fun Long.formatVideoDuration(): String {
    if (this <= 0) return "00:00"
    val totalSeconds = (this + 500) / 1000 // 四舍五入到整秒
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600

    return if (hours > 0) {
        String.format(Locale.CHINA, "%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.CHINA, "%02d:%02d", minutes, seconds)
    }
}