package com.timelapse.camera.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.timelapse.camera.model.CaptureResult
import com.timelapse.camera.util.LogBuffer
import kotlinx.coroutines.Dispatchers
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
 * - 按 cameraId 选摄像头，而非粗粒度的 facing（多镜头设备精确到具体镜头）
 */
@OptIn(ExperimentalCamera2Interop::class)
class CameraXController(
    private val context: Context,
    private val cameraId: String
) : ICameraController {

    companion object {
        private const val TAG = "CameraXController"
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var lifecycleRegistry: LifecycleRegistry? = null
    private var lifecycleOwner: LifecycleOwner? = null

    override suspend fun capture(): CaptureResult {
        LogBuffer.log("I", TAG, "开始拍摄, cameraId=$cameraId")
        val result = captureWithCameraId(cameraId)
        if (result is CaptureResult.Success) return result

        // 失败了尝试同方向其他摄像头作为备用
        val fallbackId = findFallbackCameraId(cameraId)
        if (fallbackId != null) {
            LogBuffer.log("W", TAG, "主镜头失败，切换备用镜头 cameraId=$fallbackId")
            val fallbackResult = captureWithCameraId(fallbackId)
            if (fallbackResult is CaptureResult.Success) return fallbackResult

            val firstMsg = (result as? CaptureResult.Failure)?.message ?: "未知错误"
            val secondMsg = (fallbackResult as? CaptureResult.Failure)?.message ?: "未知错误"
            return CaptureResult.Failure(
                "主镜头失败: $firstMsg；备用镜头失败: $secondMsg"
            )
        }

        return result
    }

    /**
     * 使用指定 ID 的摄像头执行一次完整拍摄。
     * 内部完成 "初始化 CameraX → 绑定 ImageCapture → 拍照 → 释放" 全流程。
     *
     * 线程安全：CameraX 的 unbindAll/bindToLifecycle 必须在主线程执行。
     * 无论调用者在哪个线程，内部用 withContext(Dispatchers.Main) 保证。
     */
    private suspend fun captureWithCameraId(id: String): CaptureResult {
        return try {
            LogBuffer.log("I", TAG, "captureWithCameraId 开始, id=$id")

            val bitmap = withContext(Dispatchers.Main) {
                val provider = ProcessCameraProvider.getInstance(context).await()
                cameraProvider = provider
                LogBuffer.log("I", TAG, "Provider 获取成功")

                val owner = object : LifecycleOwner {
                    override val lifecycle: Lifecycle get() = lifecycleRegistry!!
                }
                lifecycleRegistry = LifecycleRegistry(owner).apply { currentState = Lifecycle.State.RESUMED }
                lifecycleOwner = owner

                // 获取当前设备旋转角（用于 setTargetRotation，确保 EXIF 方向正确）
                val (adjustedSize, currentRotation) = getAdjustedSizeAndRotation(id)
                LogBuffer.log("I", TAG, "目标分辨率: ${adjustedSize.width}x${adjustedSize.height}, 旋转=${currentRotation}")

                // 指定目标分辨率 + FALLBACK_RULE_NONE：
                // 精确锁定 adjustedSize，当该尺寸不可用时直接报错，拒绝自动降级到次一档。
                // 配合 PREFER_HIGHER_RESOLUTION 确保帧率不会牺牲分辨率。
                val resolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(adjustedSize, ResolutionStrategy.FALLBACK_RULE_NONE)
                    )
                    .setAllowedResolutionMode(
                        ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE
                    )
                    .build()

                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setResolutionSelector(resolutionSelector)
                    .setJpegQuality(90)
                    .setTargetRotation(currentRotation)
                    .build()
                imageCapture = capture

                // 按 cameraId 精确选择摄像头
                val cameraSelector = CameraSelector.Builder()
                    .addCameraFilter { cameraInfos ->
                        val allIds = cameraInfos.map {
                            runCatching { Camera2CameraInfo.from(it).cameraId }.getOrNull() ?: "?"
                        }
                        LogBuffer.log("I", TAG, "CameraX 可用摄像头: $allIds, 目标=$id")

                        val filtered = cameraInfos.filter { info ->
                            val camera2Info = Camera2CameraInfo.from(info)
                            val matchId = camera2Info.cameraId == id
                            LogBuffer.log("I", TAG, "  摄像头 ${camera2Info.cameraId} 匹配=$matchId")
                            matchId
                        }

                        if (filtered.isEmpty()) {
                            LogBuffer.log("W", TAG, "CameraFilter 未匹配到 cameraId=$id，使用全部可用摄像头")
                            cameraInfos
                        } else {
                            filtered
                        }
                    }
                    .build()

                provider.unbindAll()
                val boundCamera = provider.bindToLifecycle(lifecycleOwner!!, cameraSelector, capture)
                val actualId = runCatching {
                    Camera2CameraInfo.from(boundCamera.cameraInfo).cameraId
                }.getOrNull() ?: "?"
                LogBuffer.log("I", TAG, "ImageCapture 绑定成功, 实际摄像头=$actualId")

                val bmp = takePictureAndDecode()
                LogBuffer.log("I", TAG, "拍照解码成功 ${bmp.width}x${bmp.height}")
                bmp
            }

            CaptureResult.Success(bitmap, System.currentTimeMillis())
        } catch (e: Throwable) {
            LogBuffer.log("E", TAG, "拍摄失败 id=$id: ${e.javaClass.simpleName}: ${e.message}")
            CaptureResult.Failure("拍摄失败: ${e.message}", e as? Exception ?: RuntimeException(e))
        } finally {
            withContext(Dispatchers.Main) { release() }
            LogBuffer.log("I", TAG, "资源释放完成")
        }
    }

    /**
     * 执行拍照并解码为 mutable Bitmap。
     * ImageCapture.takePicture 是回调 API，转为挂起函数。
     *
     * 不做像素旋转：JPEG 的 EXIF 方向信息由 CameraX 写入，系统相册会自动旋转显示。
     * 这样节省内存（不需要创建第二个 Bitmap），也保证方向一致性。
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
     *
     * 注意：不做像素旋转。EXIF 方向信息已包含在 JPEG 中，系统相册自动旋转显示。
     * 水印画在原始方向上，随整张图一起被系统旋转，用户看到的水印也是正的。
     */
    private fun imageToBitmap(image: ImageProxy): Bitmap {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        val options = BitmapFactory.Options().apply {
            inMutable = true
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw RuntimeException("JPEG 解码失败")
    }

    /**
     * 获取指定摄像头支持的最高 JPEG 拍照分辨率。
     *
     * 为什么不用 CameraX 自带的？
     * - CameraX 默认不选传感器最高分辨率
     * - 需要从底层 Camera2 API 查询真实能力，再回设给 CameraX
     */
    private fun getMaxJpegSize(cameraId: String): Size {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return runCatching {
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            val configs = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = configs?.getOutputSizes(ImageFormat.JPEG)
            if (!sizes.isNullOrEmpty()) {
                sizes.maxByOrNull { it.width * it.height }!!
            } else {
                Size(4032, 3024)
            }
        }.getOrElse {
            LogBuffer.log("W", TAG, "getMaxJpegSize 失败: ${it.message}，使用兜底分辨率 4032x3024")
            Size(4032, 3024)
        }
    }

    /**
     * 获取调整后的目标分辨率和当前设备旋转角。
     *
     * 为什么需要动态计算？Android 的设计坑：
     * - getOutputSizes(JPEG) 返回的是传感器自然方向的尺寸（后置通常是横向，前置通常是纵向）
     * - 如果直接传入 SensorSize(width=w, height=h)，但 targetRotation 是 ROTATION_90（竖屏），
     *   CameraX 会将图片以 90° 旋转输出，导致实际宽高对调，出现裁切或拉伸
     * - 正确做法：根据当前旋转角，将长宽对调，使 "指定尺寸" 与 "实际输出方向" 匹配
     *
     * 返回值：Pair<Size, Int>，Size 是已调整的尺寸，Int 是当前 surface 旋转角
     */
    private fun getAdjustedSizeAndRotation(cameraId: String): Pair<Size, Int> {
        val rawSize = getMaxJpegSize(cameraId)

        // 获取当前屏幕旋转角（0/90/180/270）
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val rotation = wm.defaultDisplay.rotation

        // 竖屏时（90° 或 270°），传感器原图的"宽"变成实际的"高"，"高"变成实际的"宽"
        val adjustedSize = if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            Size(rawSize.height, rawSize.width)
        } else {
            rawSize
        }

        LogBuffer.log("I", TAG, "设备旋转角=${rotation}, 原始=${rawSize.width}x${rawSize.height}, 调整后=${adjustedSize.width}x${adjustedSize.height}")
        return Pair(adjustedSize, rotation)
    }

    /**
     * 找同方向的备用摄像头（主摄像头失败时切换）。
     * 返回 null 表示没有备用。
     */
    private fun findFallbackCameraId(primaryId: String): String? {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val primaryFacing = runCatching {
            cameraManager.getCameraCharacteristics(primaryId)
                .get(CameraCharacteristics.LENS_FACING)
        }.getOrNull() ?: return null

        return cameraManager.cameraIdList.firstOrNull { id ->
            id != primaryId && runCatching {
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == primaryFacing
            }.getOrDefault(false)
        }
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
