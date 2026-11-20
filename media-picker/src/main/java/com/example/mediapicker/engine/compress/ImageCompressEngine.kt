package com.example.mediapicker.engine.compress

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 工业级图像计算与压缩引擎（完整集成 Luban 2.0 自适应统一图像压缩算法）
 * 1. 高清基准 (1440p) 短边基准决策（彻底消灭 1200x1980 断崖式暴跌）
 * 2. 10.24MP长截图内存防护 与 10800px全景墙策略
 * 3. 自适应动态比特率质量曲线
 * 4. 旋转与缩放合并为单次 Matrix 渲染
 * 5. 膨胀回退保护（压缩后体积变大时自动退回原图）
 */
object ImageCompressEngine {

    // 批量压缩并发信号量：最多 3 个任务并行，保护 CPU 与显存
    private val compressSemaphore = Semaphore(3)

    // Luban 2.0 核心算法常数
    private const val LUBAN2_BASE_SHORT = 1440           // 高清短边基准线 (1440p)
    private const val LUBAN2_WALL_LONG = 10800           // 全景墙长边判定阈值
    private const val LUBAN2_CAP_PIXELS = 10_240_000L     // 长图像素上限 (10.24MP)
    private const val LUBAN2_MEGA_PIXEL_TRAP = 40_960_000L // 4096万像素粗采样陷阱

    // ========================================================
    // 1. Bitmap 纯压缩
    // ========================================================

    /**
     * 将已有位图（如刚刚裁好的切片）压缩写入文件
     */
    suspend fun compressBitmap(
        context: Context,
        bitmap: Bitmap,
        quality: Int = 80,//（采用 Luban 2.0 平衡质量 80%）
        prefix: String = "avatar"
    ): Uri? = withContext(Dispatchers.IO) {
        try {
            // 修复：对带有透明通道的切片位图自动铺垫纯白底，防止 JPEG 编码变黑底！
            val cleanBitmap = handleAlphaChannel(bitmap)

            val outFile = File(context.cacheDir, "${prefix}_${UUID.randomUUID()}.jpg")
            compressBitmapToFile(cleanBitmap, quality, outFile)
            if (cleanBitmap != bitmap && !cleanBitmap.isRecycled) {
                cleanBitmap.recycle()
            }
            Uri.fromFile(outFile)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // ========================================================
    // 2. 表单多图并发批量压缩
    // ========================================================

    suspend fun compressBatch(
        context: Context, uris: List<Uri>
    ): List<Uri> = withContext(Dispatchers.IO) {
        uris.map { uri ->
            async {
                compressSemaphore.withPermit {
                    compressSingleImage(context, uri)
                }
            }
        }.awaitAll().filterNotNull()
    }

    // ========================================================
    // 3. 单图自压缩
    // ========================================================

    suspend fun compressSingleImage(context: Context, rawUri: Uri): Uri? =
        withContext(Dispatchers.IO) {
            try {
                val meta = inspectImageMetadata(context, rawUri)
                if (meta.width <= 0 || meta.height <= 0) return@withContext null

                // 根据 Exif 旋转标记确定视觉上的物理长宽
                val isSwapped =
                    meta.orientation == ExifInterface.ORIENTATION_ROTATE_90 || meta.orientation == ExifInterface.ORIENTATION_ROTATE_270
                val visualW = if (isSwapped) meta.height else meta.width
                val visualH = if (isSwapped) meta.width else meta.height

                // 1：Luban 2.0 智能分辨率与比特率决策（未解图前直接定死规格！）
                val spec = calculateLuban2Spec(visualW, visualH)
                // 2： 粗采样读取 防OOM
                val options = BitmapFactory.Options().apply {
                    inSampleSize = spec.inSampleSize
                    inJustDecodeBounds = false
                    inDither = true
                 }

                val coarseBmp = context.contentResolver.openInputStream(rawUri)?.use {
                    BitmapFactory.decodeStream(it, null, options)
                } ?: return@withContext null

                //  3：单次 Matrix 渲染（Exif 物理旋转 + Luban 2.0 浮点等比缩放合并为一次 Native 调用）
                val finalMatrix = Matrix()
                applyExifRotationToMatrix(meta.orientation, finalMatrix)

                val currentDecodedW = if (isSwapped) coarseBmp.height else coarseBmp.width
                val currentDecodedH = if (isSwapped) coarseBmp.width else coarseBmp.height

                if (currentDecodedW > spec.targetWidth || currentDecodedH > spec.targetHeight) {
                    val scale = min(
                        spec.targetWidth.toFloat() / currentDecodedW.toFloat(),
                        spec.targetHeight.toFloat() / currentDecodedH.toFloat()
                    )
                    finalMatrix.postScale(scale, scale)
                }

                val transformedBmp = Bitmap.createBitmap(
                    coarseBmp, 0, 0, coarseBmp.width, coarseBmp.height, finalMatrix, true
                )
                if (transformedBmp != coarseBmp && !coarseBmp.isRecycled) {
                    coarseBmp.recycle()
                }

                //4：透明通道转纯白底（防止透明印章变黑炭）
                val cleanBmp = handleAlphaChannel(transformedBmp)
                if (cleanBmp != transformedBmp && !transformedBmp.isRecycled) {
                    transformedBmp.recycle()
                }

                // 5：按 Luban 2.0 自适应比特率质量输出落盘
                val outFile = File(context.cacheDir, "compress_${UUID.randomUUID()}.jpg")
                compressBitmapToFile(cleanBmp, spec.targetQuality, outFile)

                if (!cleanBmp.isRecycled) {
                    cleanBmp.recycle()
                }

                // 6：Luban 2.0 膨胀回退机制！必须输出统一的 file:// 协议
                // 如果压缩出来的文件体积比原图还大，或者原图小且角度端正，坚决不负优化，直接返还原图！
                if (meta.fileSize > 0 && outFile.length() >= meta.fileSize && meta.orientation == ExifInterface.ORIENTATION_NORMAL) {
                   //把原图直接安全复制到输出文件
                    context.contentResolver.openInputStream(rawUri)?.use { input ->
                        FileOutputStream(outFile).use { output -> input.copyTo(output) }
                    }
                }
                Uri.fromFile(outFile)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

    /**
     * 供裁剪页（ImageCropViewModel）加载安全高清预览图使用
     * 修复：长图保短边，绝不把 1200 宽度的长图腰斩成 150 像素马赛克！
     */
    fun decodeSampledBitmap(context: Context, uri: Uri, maxDimension: Int = 2048): Bitmap? {
        val meta = inspectImageMetadata(context, uri)
        if (meta.width <= 0 || meta.height <= 0) return null

        val isSwapped =
            meta.orientation == ExifInterface.ORIENTATION_ROTATE_90 || meta.orientation == ExifInterface.ORIENTATION_ROTATE_270
        val visualW = if (isSwapped) meta.height else meta.width
        val visualH = if (isSwapped) meta.width else meta.height

        val longEdge = max(visualW, visualH)
        val shortEdge = min(visualW, visualH)
        val aspectRatio = longEdge.toFloat() / shortEdge.toFloat()

        // 长图与普通图分流计算 sampleSize
        val sampleSize = if (aspectRatio >= 2.5f) {
            // 【长截图保护通道】：严格按短边计算采样，确保宽度维持在 1080~1440 之间！
            // 1200 像素的宽度在此处 sampleSize 算出来严格为 1！宽 1200 像素完整保留！
            var s = 1
            while ((shortEdge / s) > 1440) {
                s *= 2
            }
            // 总像素安全兜底（防止遇到 10 万像素高的变态图片爆内存，限制总像素在 16MP 内）
            val totalPixels = visualW.toLong() * visualH.toLong()
            while ((totalPixels / (s * s)) > 16_000_000L) {
                s *= 2
            }
            s
        } else {
            // 【普通图通道】：长边卡在 maxDimension (2048) 以内
            var s = 1
            while ((longEdge / s) > maxDimension) {
                s *= 2
            }
            s
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inJustDecodeBounds = false
            inPreferredConfig = Bitmap.Config.RGB_565
        }

        val bmp = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null

        val matrix = Matrix()
        applyExifRotationToMatrix(meta.orientation, matrix)
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
        if (rotated != bmp && !bmp.isRecycled) {
            bmp.recycle()
        }
        return rotated
    }

    /**
     * 异步静默清理 3 天前的过期临时缓存
     */
    fun autoCleanExpiredCache(context: Context, maxAgeDays: Int = 3) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val cacheDir = context.cacheDir ?: return@launch
                val now = System.currentTimeMillis()
                val maxAgeMs = maxAgeDays * 24 * 60 * 60 * 1000L

                cacheDir.listFiles()?.forEach { file ->
                    if (file.name.startsWith("crop_") || file.name.startsWith("compress_")) {
                        if (now - file.lastModified() > maxAgeMs) {
                            file.delete()
                        }
                    }
                }
            } catch (ignored: Exception) {
            }
        }
    }

    // ========================================================
    // 🚨 Luban 2.0 算法核心实现 (CompressionCalculator)
    // ========================================================

    private data class Luban2Spec(
        val inSampleSize: Int, val targetWidth: Int, val targetHeight: Int, val targetQuality: Int
    )

    /**
     * Luban 2.0 自适应统一图像压缩决策公式
     */
    private fun calculateLuban2Spec(srcW: Int, srcH: Int): Luban2Spec {
        val shortSide = min(srcW, srcH)
        val longSide = max(srcW, srcH)
        val totalPixels = srcW.toLong() * srcH.toLong()
        val isLandscape = srcW > srcH
        val aspectRatio = longEdgeFloat(srcW, srcH) / shortEdgeFloat(srcW, srcH)

        val targetW: Int
        val targetH: Int

        when {
            // 修正点 1：超长竖向截图（长宽比 >= 2.5 且短边原本就在 1440 以内）
            // 比如 1200 x 11496：绝对不触发全景墙！走面积保护！
            aspectRatio >= 2.5f && shortSide <= LUBAN2_BASE_SHORT -> {
                if (totalPixels > LUBAN2_CAP_PIXELS) {
                    // 如果总像素超过 10.24MP，按面积平滑缩放，保住可读性！
                    val scale = sqrt(LUBAN2_CAP_PIXELS.toDouble() / totalPixels.toDouble())
                    targetW = (srcW * scale).toInt().coerceAtLeast(1)
                    targetH = (srcH * scale).toInt().coerceAtLeast(1)
                } else {
                    // 总像素没超标，100% 保持原图！
                    targetW = srcW
                    targetH = srcH
                }
            }

            // 策略 1：全景墙策略（真正的横向全景超大图（仅限横向且长边 > 10800））
            longSide > LUBAN2_WALL_LONG && isLandscape -> {
                val scale = LUBAN2_BASE_SHORT.toDouble() / longSide.toDouble()
                val newLong = LUBAN2_BASE_SHORT
                val newShort = (shortSide * scale).toInt().coerceAtLeast(1)
                targetW = newLong
                targetH = newShort
            }

            // 策略 2：长图总像素保护（超长截图总像素 > 10.24MP）
            totalPixels > LUBAN2_CAP_PIXELS -> {
                val scale = sqrt(LUBAN2_CAP_PIXELS.toDouble() / totalPixels.toDouble())
                targetW = (srcW * scale).toInt().coerceAtLeast(1)
                targetH = (srcH * scale).toInt().coerceAtLeast(1)
            }

            // 策略 3：高清短边基准（普通高质量拍照：短边 > 1440px）
            //覆盖范围：所有普通拍照（12MP、24MP、48MP、1亿像素拍照全部进入这里
            shortSide > LUBAN2_BASE_SHORT -> {
                val scale = LUBAN2_BASE_SHORT.toDouble() / shortSide.toDouble()
                val newShort = LUBAN2_BASE_SHORT
                val newLong = (longSide * scale).toInt()
                targetW = if (isLandscape) newLong else newShort
                targetH = if (isLandscape) newShort else newLong
            }

            // 策略 4：标准图/日常截图（短边 <= 1440 且像素 <= 10.24MP）
            // 100% 保持原始分辨率！1200x1980 完美命中此处！
            else -> {
                targetW = srcW
                targetH = srcH
            }
        }

        //超大像素陷阱（4096万像素）解码降采样拦截  计算防 OOM 粗采样比
        //针对 4800W / 6400W / 1亿像素超大照片
        var sampleSize = 1
        if (totalPixels > LUBAN2_MEGA_PIXEL_TRAP) {
            sampleSize = 2 // 4096万以上像素自动触发 1/4 降采样
        }
        // 确保解码出来的小图长宽不超过目标尺寸的 1.5 倍，防止内存溢出
        while ((srcW / sampleSize) > targetW * 1.5 || (srcH / sampleSize) > targetH * 1.5) {
            sampleSize *= 2
        }

        // Luban 2.0 自适应比特率画质曲线
        val targetPixels = targetW.toLong() * targetH.toLong()
        val quality = when {
            targetPixels < 500_000L -> 92      // 极小图 (<0.5MP)：高保真防伪影
            targetPixels < 1_000_000L -> 85    // 高频小图 (0.5-1MP)：补偿分辨率损失
            targetPixels < 3_000_000L -> 78    // 标准图 (1-3MP)：主流平衡体验 (1200x1980命中此处)
            else -> 68                        // 超大图/长图 (>3MP)：高压缩比
        }

        return Luban2Spec(sampleSize, targetW, targetH, quality)
    }

    // ========================================================
    // 基础辅助工具
    // ========================================================

    private fun compressBitmapToFile(bitmap: Bitmap, quality: Int, outputFile: File) {
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
        FileOutputStream(outputFile).use { fos ->
            baos.writeTo(fos)
        }
    }

    private data class ImageMeta(
        val width: Int, val height: Int, val orientation: Int, val fileSize: Long
    )

    private fun inspectImageMetadata(context: Context, uri: Uri): ImageMeta {
        val cr = context.contentResolver
        var w = 0
        var h = 0
        var orientation = ExifInterface.ORIENTATION_NORMAL
        var size = 0L

        try {
            cr.openFileDescriptor(uri, "r")?.use { size = it.statSize }
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            w = options.outWidth
            h = options.outHeight

            cr.openInputStream(uri)?.use {
                orientation = ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )
            }
        } catch (ignored: Exception) {
        }

        return ImageMeta(w, h, orientation, size)
    }

    private fun applyExifRotationToMatrix(orientation: Int, matrix: Matrix) {
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        }
    }

    private fun handleAlphaChannel(bitmap: Bitmap): Bitmap {
        if (!bitmap.hasAlpha()) return bitmap
        val newBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(newBitmap)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        return newBitmap
    }

    private fun longEdgeFloat(w: Int, h: Int) = max(w, h).toFloat()
    private fun shortEdgeFloat(w: Int, h: Int) = min(w, h).toFloat()
}