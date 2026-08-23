package com.timelapse.camera.ui.preview

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import coil.load
import com.timelapse.camera.R
import com.timelapse.camera.config.CaptureConfig
import com.timelapse.camera.databinding.FragmentPreviewBinding
import com.timelapse.camera.storage.IPhotoStorage
import com.timelapse.camera.storage.PhotoStorageFactory
import com.timelapse.camera.util.BatteryMonitor
import com.timelapse.camera.watermark.WatermarkOptions
import com.timelapse.camera.watermark.WatermarkProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 预览页 Fragment —— 构图对齐 + 试拍验证。
 *
 * 功能：
 * - CameraX Preview 用例实时预览画面
 * - 「立即拍一张」试拍：拍一张 + 加水印 + 弹窗显示效果
 * - 试拍照片保存在缓存目录，用户确认效果后可删除
 *
 * 功耗设计：
 * - 只在页面可见时绑定预览用例
 * - 切换到其他 Tab 时 onStop() 自动解绑，摄像头完全释放
 * - 这也是为什么用 viewLifecycleOwner.lifecycle 绑定 CameraX
 *
 * 教学要点：
 * - ActivityResultContracts.RequestPermission 申请相机权限（现代写法）
 * - CameraX Preview + ImageCapture 双用例绑定
 */
class PreviewFragment : Fragment() {

    private var _binding: FragmentPreviewBinding? = null
    private val binding get() = _binding!!

    private lateinit var config: CaptureConfig
    private lateinit var storage: IPhotoStorage
    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null

    private val watermarkProcessor = WatermarkProcessor()

    /**
     * 相机权限申请 launcher。
     *
     * 为什么用 ActivityResultContracts 而不是 requestPermissions()？
     * - 官方推荐的现代 API，类型安全，不需要手动管理 requestCode
     * - 在字段声明处调用即可：内部会延迟到 Fragment onAttach 之后再完成注册
     * - 注意：launch() 必须在 Fragment 可见之后调用（onStart 及以后）
     */
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            Toast.makeText(
                requireContext(), R.string.perm_camera_denied, Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = CaptureConfig.load(requireContext())
        storage = PhotoStorageFactory.create(requireContext(), config)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPreviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnCapture.setOnClickListener {
            takeTestPhoto()
        }

        if (hasCameraPermission()) {
            startCamera()
        } else {
            requestCameraPermission()
        }
    }

    override fun onResume() {
        super.onResume()
        // 单一数据源：config 和 storage 来自同一次磁盘读取
        reloadFromDisk()
    }

    /**
     * 从磁盘重新加载 config，并基于同一份 config 创建 storage。
     * 保证 config 和 storage 永远来自同一次读取，消除不同步风险。
     */
    private fun reloadFromDisk() {
        config = CaptureConfig.load(requireContext())
        storage = PhotoStorageFactory.create(requireContext(), config)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cameraProvider?.unbindAll()
        cameraProvider = null
        imageCapture = null
        _binding = null
    }

    // ──────────── 相机权限 ────────────

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

    private fun requestCameraPermission() {
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // ──────────── 相机预览 ────────────

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()
            cameraProvider = provider

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setJpegQuality(85)
                .build()

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(config.cameraFacing)
                .build()

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    viewLifecycleOwner, cameraSelector, preview, imageCapture
                )
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "启动预览失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    // ──────────── 试拍 ────────────

    private fun takeTestPhoto() {
        val capture = imageCapture ?: run {
            Toast.makeText(requireContext(), R.string.preview_no_camera_permission, Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnCapture.isEnabled = false

        val outputFile = File(
            requireContext().cacheDir,
            "test_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.jpg"
        )

        val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(requireContext()),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    // 加水印后显示
                    processAndShowTestPhoto(outputFile)
                }

                override fun onError(exception: ImageCaptureException) {
                    binding.btnCapture.isEnabled = true
                    Toast.makeText(
                        requireContext(),
                        "${getString(R.string.preview_test_fail)}: ${exception.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }

    private fun processAndShowTestPhoto(file: File) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                // 解码为 mutable Bitmap（inMutable=true，水印模块可直接绘制，零额外内存）
                val options = android.graphics.BitmapFactory.Options().apply {
                    inMutable = true
                }
                val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
                    ?: throw RuntimeException("试拍照片解码失败")

                // 构建水印选项
                val watermarkOptions = WatermarkOptions(
                    customText = config.watermarkText,
                    showBattery = config.watermarkShowBattery,
                    showStorage = config.watermarkShowStorage,
                    showTemperature = config.watermarkShowTemperature,
                    batteryPercent = BatteryMonitor.getBatteryPercent(requireContext()),
                    storageRemainingGb = BatteryMonitor.getStorageRemainingGb(storage.getPhotoDir()),
                    temperatureCelsius = BatteryMonitor.getBatteryTemperature(requireContext())
                )

                // 绘制水印
                watermarkProcessor.apply(bitmap, System.currentTimeMillis(), watermarkOptions)

                // 保存到缓存
                val outputFile = File(requireContext().cacheDir, "test_result.jpg")
                outputFile.outputStream().use { out ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
                }
                bitmap.recycle()

                // 删除原始试拍文件
                file.delete()

                outputFile
            }.onSuccess { resultFile ->
                withContext(Dispatchers.Main) {
                    binding.btnCapture.isEnabled = true
                    binding.cardTestResult.visibility = View.VISIBLE
                    binding.ivTestResult.load(resultFile)
                    binding.tvTestResult.text = getString(R.string.preview_test_ok)

                    // 3 秒后自动隐藏
                    binding.cardTestResult.postDelayed({
                        binding.cardTestResult.visibility = View.GONE
                    }, 3000)
                }
            }.onFailure {
                withContext(Dispatchers.Main) {
                    binding.btnCapture.isEnabled = true
                    Toast.makeText(
                        requireContext(),
                        "${getString(R.string.preview_test_fail)}: ${it.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    companion object {
        fun newInstance() = PreviewFragment()
    }
}
