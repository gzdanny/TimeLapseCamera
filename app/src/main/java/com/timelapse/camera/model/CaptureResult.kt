package com.timelapse.camera.model

import android.graphics.Bitmap

/**
 * 拍摄操作的结果，用于模块间传递，调用方通过 sealed class 做穷举分支处理。
 */
sealed class CaptureResult {
    data class Success(
        val bitmap: Bitmap,
        val timestamp: Long
    ) : CaptureResult()

    data class Failure(
        val message: String,
        val cause: Throwable? = null
    ) : CaptureResult()
}
