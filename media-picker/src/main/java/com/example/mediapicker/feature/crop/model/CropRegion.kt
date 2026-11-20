package com.example.mediapicker.feature.crop.model

/**
 * 视口在原图物理像素上的切片区域坐标
 */
data class CropRegion(
    val srcX: Int,//裁剪起点
    val srcY: Int,//裁剪的宽度
    val width: Int,//
    val height: Int
)