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
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import coil.load
import com.timelapse.camera.R
import com.timelapse.camera.camera.CameraXController
import com.timelapse.camera.config.CaptureConfig
import com.timelapse.camera.databinding.FragmentPreviewBinding
import com.timelapse.camera.model.CaptureResult
import com.timelapse.camera.storage.IPhotoStorage
import com.timelapse.camera.storage.PhotoStorageFactory
import com.timelapse.camera.util.BatteryMonitor
import com.timelapse.camera.watermark.WatermarkOptions
import com.timelapse.camera.watermark.WatermarkProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 预览页 Fragment —— 构图对齐 + 试拍验证。
 *
 * 功能：
 * - CameraX Preview 用例实时预览画面
 * - 「立即拍一张」试拍：走与正常拍摄完全相同的管线（CameraXController → 水印 → 存储）
 *   唯一区别：不循环、不等待、用固定文件名 Test.jpg
 *
 * 功耗设计：
 * - 只在页面可见时绑定预览用例
 * - 切换到其他 Tab 时 onStop() 自动解绑，摄像头完全释放
 *
 * 教学要点：
 * - 试拍代码复用 CaptureService 的拍摄管线，确保验证的是真实流程
 * - 试拍照片用固定文件名，方便用户在相册中快速定位检查
 */
class PreviewFragment : Fragment() {

    private var _binding: FragmentPreviewBinding? = null
    private val binding get() = _binding!!

    private lateinit var config: CaptureConfig
    private lateinit var storage: IPhotoStorage
    private var cameraProvider: ProcessCameraProvider? = null

    private val watermarkProcessor = WatermarkProcessor()

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
        reloadFromDisk()
    }

    private fun reloadFromDisk() {
        config = CaptureConfig.load(requireContext())
        storage = PhotoStorageFactory.create(requireContext(), config)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cameraProvider?.unbindAll()
        cameraProvider = null
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

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(config.cameraFacing)
                .build()

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    viewLifecycleOwner, cameraSelector, preview
                )
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "启动预览失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    // ──────────── 试拍（与 CaptureService 相同的管线）────────────

    /**
     * 试拍流程与 CaptureService.captureLoop() 的单次拍摄完全一致：
     * 1. 释放预览（CameraXController 内部会 unbindAll）
     * 2. CameraXController.capture() → CaptureResult
     * 3. WatermarkProcessor.apply() 加水印（或 createErrorBitmap 生成黑图）
     * 4. storage.saveTestPhoto() 保存到用户配置的存储位置（固定文件名 Test.jpg）
     * 5. 重新绑定预览
     *
     * 唯一区别：不循环、不等待、不设闹钟、不更新通知
     */
    private fun takeTestPhoto() {
        binding.btnCapture.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            // 先释放预览，避免 CameraX 用例冲突
            withContext(Dispatchers.Main) {
                cameraProvider?.unbindAll()
            }

            val timestamp = System.currentTimeMillis()

            // ── 1. 拍摄（与 CaptureService 相同：CameraXController）──
            val camera = CameraXController(requireContext(), config.cameraFacing)
            val result = camera.capture()

            // ── 2. 水印（与 CaptureService 相同：WatermarkProcessor）──
            val watermarkOptions = WatermarkOptions(
                customText = config.watermarkText,
                showBattery = config.watermarkShowBattery,
                showStorage = config.watermarkShowStorage,
                showTemperature = config.watermarkShowTemperature,
                batteryPercent = BatteryMonitor.getBatteryPercent(requireContext()),
                storageRemainingGb = BatteryMonitor.getStorageRemainingGb(storage.getPhotoDir()),
                temperatureCelsius = BatteryMonitor.getBatteryTemperature(requireContext())
            )

            val bitmapToSave = when (result) {
                is CaptureResult.Success -> {
                    watermarkProcessor.apply(result.bitmap, result.timestamp, watermarkOptions)
                }
                is CaptureResult.Failure -> {
                    watermarkProcessor.createErrorBitmap(timestamp)
                }
            }

            // ── 3. 保存（与 CaptureService 相同的存储路径，固定文件名）──
            val savedPath = try {
                storage.saveTestPhoto(bitmapToSave)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                bitmapToSave.recycle()
                withContext(Dispatchers.Main) {
                    _binding?.let { b ->
                        b.btnCapture.isEnabled = true
                        Toast.makeText(
                            requireContext(),
                            "${getString(R.string.preview_test_fail)}: ${e.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                return@launch
            }

            // ── 4. 重新绑定预览 ──
            withContext(Dispatchers.Main) {
                val b = _binding ?: return@withContext
                b.btnCapture.isEnabled = true
                startCamera()

                b.cardTestResult.visibility = View.VISIBLE
                b.ivTestResult.load(savedPath)
                b.tvTestResult.text = if (result is CaptureResult.Success) {
                    getString(R.string.preview_test_saved)
                } else {
                    getString(R.string.preview_test_fail)
                }
            }
        }
    }

    companion object {
        fun newInstance() = PreviewFragment()
    }
}
