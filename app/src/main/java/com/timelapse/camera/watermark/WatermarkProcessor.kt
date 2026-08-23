package com.timelapse.camera.watermark

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 水印处理器 —— 负责所有在 Bitmap 上绘制文字的逻辑。
 *
 * 水印布局：
 *   ┌──────────────────────────────────┐
 *   │ 🔋85%  💾12.3GB  🌡28°C          │  ← 左上角：状态信息（可开关）
 *   │                                  │
 *   │                                  │
 *   │                                  │
 *   │                                  │
 *   │              （照片内容）          │
 *   │                                  │
 *   │                                  │
 *   │                                  │
 *   │              阳台番茄记录         │  ← 右下角：自定义文字 + 时间戳
 *   │          2026-08-23 14:00:00     │
 *   └──────────────────────────────────┘
 *
 * 内存优化设计（教学要点）：
 * - 直接在输入 Bitmap 上绘制，零额外内存（前提：bitmap 是 mutable 的）
 * - CameraXController 中 inMutable=true 保证了这一点
 * - 整条责任链只有 1 个 Bitmap 对象，峰值内存 = 单张图大小
 */
class WatermarkProcessor {

    companion object {
        private const val TEXT_SIZE_RATIO = 0.022f  // 文字高度 ≈ 图片宽度的 2.2%
        private const val MARGIN_RATIO = 0.025f    // 边距 ≈ 图片宽度的 2.5%
        private const val PADDING_RATIO = 0.01f    // 文字周围内边距

        /** 错误黑图尺寸：足够清晰，又尽量省内存 */
        private const val ERROR_BITMAP_WIDTH = 1280
        private const val ERROR_BITMAP_HEIGHT = 720
    }

    /**
     * 直接在 bitmap 上绘制水印，返回同一个 bitmap 对象。
     *
     * @param bitmap 可变的原始照片（mutable！）
     * @param timestamp 拍摄时间戳
     * @param options 水印配置（显示哪些信息、各状态的数值）
     * @return 同一个 bitmap 对象（已绘制水印），调用方负责 recycle
     */
    fun apply(bitmap: Bitmap, timestamp: Long, options: WatermarkOptions): Bitmap {
        check(bitmap.isMutable) { "WatermarkProcessor 需要 mutable Bitmap" }

        val canvas = Canvas(bitmap)
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()

        // 左上角状态信息（电量/存储/温度）
        val statusText = buildStatusText(options)
        if (statusText.isNotEmpty()) {
            drawTopLeftWatermark(canvas, w, statusText)
        }

        // 右下角时间戳 + 自定义文字
        val timeText = buildTimeText(timestamp, options.customText)
        drawBottomRightWatermark(canvas, w, h, timeText)

        return bitmap
    }

    /**
     * 创建一张纯黑的错误占位图，并在上面绘制时间戳 + "拍摄失败"提示。
     *
     * 使用场景：摄像头完全不可用时，生成一张占位图存入相册。
     * 这样用户翻照片时能看到 "App 正常唤醒了，但摄像头出问题了"。
     */
    fun createErrorBitmap(timestamp: Long): Bitmap {
        val bitmap = Bitmap.createBitmap(
            ERROR_BITMAP_WIDTH, ERROR_BITMAP_HEIGHT, Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap).apply { drawColor(Color.BLACK) }

        val timeStr = formatTime(timestamp)
        val content = "拍摄失败\n$timeStr"
        drawBottomRightWatermark(
            canvas, ERROR_BITMAP_WIDTH.toFloat(), ERROR_BITMAP_HEIGHT.toFloat(), content
        )

        return bitmap
    }

    // ────────────────── 文字构建 ──────────────────

    private fun buildStatusText(options: WatermarkOptions): String {
        val parts = mutableListOf<String>()
        if (options.showBattery) {
            parts.add("${batteryIcon(options.batteryPercent)} ${options.batteryPercent}%")
        }
        if (options.showStorage) {
            parts.add("💾 %.1fGB".format(Locale.US, options.storageRemainingGb))
        }
        if (options.showTemperature) {
            parts.add("🌡 %.0f°C".format(Locale.US, options.temperatureCelsius))
        }
        return parts.joinToString("  ")
    }

    private fun buildTimeText(timestamp: Long, customText: String?): String {
        val timeStr = formatTime(timestamp)
        return if (!customText.isNullOrBlank()) "$customText\n$timeStr" else timeStr
    }

    private fun formatTime(timestamp: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(Date(timestamp))

    /** 根据电量百分比返回不同的电池图标，直观展示电量状态 */
    private fun batteryIcon(percent: Int): String = when {
        percent >= 100 -> "🔋"
        percent >= 80 -> "🟢"
        percent >= 30 -> "🔌"
        else -> "🪫"
    }

    // ────────────────── 绘制 ──────────────────

    /** 左上角绘制状态信息（电量/存储/温度） */
    private fun drawTopLeftWatermark(canvas: Canvas, width: Float, content: String) {
        val textSize = width * TEXT_SIZE_RATIO
        val margin = width * MARGIN_RATIO
        val padding = width * PADDING_RATIO

        val textPaint = TextPaint().apply {
            color = Color.WHITE
            this.textSize = textSize
            isAntiAlias = true
            setShadowLayer(textSize * 0.2f, 0f, 0f, Color.argb(180, 0, 0, 0))
        }

        val layout = StaticLayout.Builder
            .obtain(content, 0, content.length, textPaint, (width - margin * 2 - padding * 2).toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1f)
            .build()

        // 左上角定位
        val bgLeft = margin
        val bgTop = margin
        val bgRight = margin + layout.width + padding * 2
        val bgBottom = margin + layout.height + padding * 2

        val bgPaint = Paint().apply { color = Color.argb(100, 0, 0, 0) }
        canvas.drawRoundRect(
            bgLeft, bgTop, bgRight, bgBottom,
            padding, padding, bgPaint
        )

        canvas.save()
        canvas.translate(bgLeft + padding, bgTop + padding)
        layout.draw(canvas)
        canvas.restore()
    }

    /** 右下角绘制时间戳 + 自定义文字 */
    private fun drawBottomRightWatermark(
        canvas: Canvas, width: Float, height: Float, content: String
    ) {
        val textSize = width * TEXT_SIZE_RATIO
        val margin = width * MARGIN_RATIO
        val padding = width * PADDING_RATIO

        val textPaint = TextPaint().apply {
            color = Color.WHITE
            this.textSize = textSize
            isAntiAlias = true
            setShadowLayer(textSize * 0.2f, 0f, 0f, Color.argb(180, 0, 0, 0))
        }

        val layout = StaticLayout.Builder
            .obtain(content, 0, content.length, textPaint, (width - margin * 2 - padding * 2).toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1f)
            .build()

        // 右下角定位
        val bgLeft = width - margin - layout.width - padding * 2
        val bgTop = height - margin - layout.height - padding * 2
        val bgRight = width - margin
        val bgBottom = height - margin

        val bgPaint = Paint().apply { color = Color.argb(100, 0, 0, 0) }
        canvas.drawRoundRect(
            bgLeft, bgTop, bgRight, bgBottom,
            padding, padding, bgPaint
        )

        canvas.save()
        canvas.translate(bgLeft + padding, bgTop + padding)
        layout.draw(canvas)
        canvas.restore()
    }
}
