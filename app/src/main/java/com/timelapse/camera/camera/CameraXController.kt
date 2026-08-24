package com.timelapse.camera.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.timelapse.camera.model.CaptureResult
import com.timelapse.camera.util.LogBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * CameraX 实现的拍摄控制器。
 *
 * 为什么从 Camera2 迁移到 CameraX？
 * - CameraX 是 Google 官方推荐的现代相机库，底层封装了 Camera2
 * - 代码量比手写 Camera2 少 60%+，更适合教学
 * - 自动处理设备兼容性，不用手动处理各种厂商差异
 * - 预览（Preview）和拍照（ImageCapture）是独立用例，可按需组合
 *
 * 功耗策略：每次 capture() 内部完成 "绑定用例 → 拍照 → 解绑释放" 全流程，
 * 拍完后摄像头完全释放，间隔期间零硬件功耗。
 *
 * 教学要点：
 * - ProcessCameraProvider 是单例，需要 Future 异步获取
 * - ImageCapture.takePicture() 是回调 API，用 suspendCancellableCoroutine 转挂起函数
 * - CameraX 内部有自己的线程池，不需要手动管理 HandlerThread
 * - LifecycleRegistry 手动管理生命周期（Service 不是 LifecycleOwner，需要自己造一个）
 */
class CameraXController(
    private val context: Context,
    private val cameraFacing: Int
) : ICameraController {

    companion object {
        private const val TAG = "CameraXController"
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var lifecycleRegistry: LifecycleRegistry? = null
    private var lifecycleOwner: LifecycleOwner? = null

    override suspend fun capture(): CaptureResult {
        LogBuffer.log("I", TAG, "开始拍摄, facing=${cameraFacing}")
        // 主镜头先试
        val firstResult = captureWithFacing(cameraFacing)
        if (firstResult is CaptureResult.Success) return firstResult

        // 失败了切换备用镜头重试一次
        val otherFacing = otherFacing(cameraFacing)
        if (otherFacing != null) {
            LogBuffer.log("W", TAG, "主镜头失败，切换备用镜头 facing=$otherFacing")
            val fallbackResult = captureWithFacing(otherFacing)
            if (fallbackResult is CaptureResult.Success) return fallbackResult

            val firstMsg = (firstResult as? CaptureResult.Failure)?.message ?: "未知错误"
            val secondMsg = (fallbackResult as? CaptureResult.Failure)?.message ?: "未知错误"
            return CaptureResult.Failure(
                "主镜头失败: $firstMsg；备用镜头失败: $secondMsg"
            )
        }

        return firstResult
    }

    /**
     * 使用指定方向的摄像头执行一次完整拍摄。
     * 内部完成 "初始化 CameraX → 绑定 ImageCapture → 拍照 → 释放" 全流程。
     *
     * 线程安全：CameraX 的 unbindAll/bindToLifecycle 必须在主线程执行。
     * 无论调用者在哪个线程，内部用 withContext(Dispatchers.Main) 保证。
     */
    private suspend fun captureWithFacing(facing: Int): CaptureResult {
        return try {
            LogBuffer.log("I", TAG, "captureWithFacing 开始, facing=$facing")

            val bitmap = withContext(Dispatchers.Main) {
                val provider = ProcessCameraProvider.getInstance(context).await()
                cameraProvider = provider
                LogBuffer.log("I", TAG, "Provider 获取成功")

                val owner = object : LifecycleOwner {
                    override val lifecycle: Lifecycle get() = lifecycleRegistry!!
                }
                lifecycleRegistry = LifecycleRegistry(owner).apply { currentState = Lifecycle.State.RESUMED }
                lifecycleOwner = owner

                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setJpegQuality(85)
                    .setTargetRotation(android.view.Surface.ROTATION_0)
                    .build()
                imageCapture = capture

                val cameraSelector = CameraSelector.Builder()
                    .requireLensFacing(facing)
                    .build()

                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner!!, cameraSelector, capture)
                LogBuffer.log("I", TAG, "ImageCapture 绑定成功")

                val bmp = takePictureAndDecode()
                LogBuffer.log("I", TAG, "拍照解码成功 ${bmp.width}x${bmp.height}")
                bmp
            }

            CaptureResult.Success(bitmap, System.currentTimeMillis())
        } catch (e: Throwable) {
            LogBuffer.log("E", TAG, "拍摄失败 facing=$facing: ${e.javaClass.simpleName}: ${e.message}")
            CaptureResult.Failure("拍摄失败: ${e.message}", e as? Exception ?: RuntimeException(e))
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { release() }
            LogBuffer.log("I", TAG, "资源释放完成")
        }
    }

    /**
     * 执行拍照并解码为 mutable Bitmap。
     * ImageCapture.takePicture 是回调 API，转为挂起函数。
     */
    private suspend fun takePictureAndDecode(): Bitmap =
        suspendCancellableCoroutine { cont ->
            LogBuffer.log("I", TAG, "takePicture 调用")
            imageCapture?.takePicture(
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        LogBuffer.log("I", TAG, "onCaptureSuccess 回调")
                        if (!cont.isActive) {
                            image.close()
                            return
                        }
                        runCatching { imageToBitmap(image) }
                            .onSuccess { cont.resume(it) }
                            .onFailure { cont.cancel(it) }
                        image.close()
                    }

                    override fun onError(exception: ImageCaptureException) {
                        LogBuffer.log("E", TAG, "onError 回调: ${exception.message}")
                        if (cont.isActive) cont.cancel(exception)
                    }
                }
            ) ?: run {
                LogBuffer.log("E", TAG, "imageCapture 为 null，无法拍照")
                cont.cancel(IllegalStateException("imageCapture 未初始化"))
            }
        }

    /**
     * 将 ImageProxy 转为 mutable Bitmap。
     * inMutable=true 保证后续水印模块可直接在 Bitmap 上绘制，零额外内存。
     */
    private fun imageToBitmap(image: ImageProxy): Bitmap {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        val options = BitmapFactory.Options().apply {
            inMutable = true
        }
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw RuntimeException("JPEG 解码失败")

        // 旋转校正（和相机传感器方向对齐）
        val rotationDegrees = image.imageInfo.rotationDegrees
        return if (rotationDegrees != 0) {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true).also {
                if (it != raw) raw.recycle()
            }
        } else {
            raw
        }
    }

    private fun otherFacing(facing: Int): Int? = when (facing) {
        CameraCharacteristics.LENS_FACING_BACK -> CameraCharacteristics.LENS_FACING_FRONT
        CameraCharacteristics.LENS_FACING_FRONT -> CameraCharacteristics.LENS_FACING_BACK
        else -> null
    }

    override fun release() {
        LogBuffer.log("I", TAG, "release: unbind + 清理")
        cameraProvider?.unbindAll()
        cameraProvider = null
        imageCapture = null
        lifecycleRegistry?.currentState = Lifecycle.State.DESTROYED
        lifecycleRegistry = null
        lifecycleOwner = null
    }
}
