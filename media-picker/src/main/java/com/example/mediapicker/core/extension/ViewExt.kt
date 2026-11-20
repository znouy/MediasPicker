package com.example.mediapicker.core.extension

import android.view.View
import androidx.core.view.isVisible

/**
 * 核心扩展 1：防暴力点击（防抖）
 * 规避用户疯狂快速点击“完成”、“发送”或“勾选热区”引发的并发状态错乱
 */
fun View.setOnDebounceClickListener(delayMs: Long = 500L, onClick: (View) -> Unit) {
    var lastClickTime = 0L
    setOnClickListener { v ->
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastClickTime > delayMs) {
            lastClickTime = currentTime
            onClick(v)
        }
    }
}

/**
 * 平滑淡入显示
 */
fun View.fadeIn(duration: Long = 250L, endAction: (() -> Unit)? = null) {
    if (isVisible && alpha == 1f) return
    alpha = 0f
    visibility = View.VISIBLE
    animate()
        .alpha(1f)
        .setDuration(duration)
        .withEndAction { endAction?.invoke() }
        .start()
}

/**
 * 平滑淡出隐藏
 */
fun View.fadeOut(duration: Long = 250L, endAction: (() -> Unit)? = null) {
    if (visibility != View.VISIBLE) return
    animate()
        .alpha(0f)
        .setDuration(duration)
        .withEndAction {
            visibility = View.GONE
            endAction?.invoke()
        }
        .start()
}

/**
 * 简化动态设置视图尺寸
 */
fun View.updateSize(width: Int? = null, height: Int? = null) {
    val lp = layoutParams ?: return
    width?.let { lp.width = it }
    height?.let { lp.height = it }
    layoutParams = lp
}