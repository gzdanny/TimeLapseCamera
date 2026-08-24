package com.timelapse.camera.ui.status

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.timelapse.camera.R
import com.timelapse.camera.config.CaptureConfig
import com.timelapse.camera.databinding.FragmentStatusBinding
import com.timelapse.camera.service.CaptureService
import com.timelapse.camera.storage.IPhotoStorage
import com.timelapse.camera.storage.PhotoStorageFactory
import com.timelapse.camera.util.BatteryMonitor
import com.timelapse.camera.util.LogBuffer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 状态页 Fragment —— 默认首页。
 *
 * 核心信息：
 * - 运行状态 + 统计（拍摄数量、电量、存储、温度）
 * - 开始/停止按钮
 * - 运行日志（实时滚动更新）
 *
 * 设计要点：
 * - 页面进入时加载一次完整状态，后续每秒只刷新日志（自动滚动到底部）
 * - 倒计时由系统通知栏的 Chronometer 渲染，UI 不重复计算
 * - 用 lifecycleScope，页面不可见时自动停止刷新
 */
class StatusFragment : Fragment() {

    private var _binding: FragmentStatusBinding? = null
    private val binding get() = _binding!!

    private lateinit var config: CaptureConfig
    private lateinit var storage: IPhotoStorage

    private var refreshJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = CaptureConfig.load(requireContext())
        storage = PhotoStorageFactory.create(requireContext(), config)
        LogBuffer.init(requireContext())
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStatusBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnToggle.setOnClickListener { toggleCapture() }
    }

    override fun onResume() {
        super.onResume()
        reloadFromDisk()
        updateUI()
        startLogRefresh()
    }

    override fun onPause() {
        super.onPause()
        stopLogRefresh()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /**
     * 从磁盘重新加载 config，并基于同一份 config 创建 storage。
     */
    private fun reloadFromDisk() {
        config = CaptureConfig.load(requireContext())
        storage = PhotoStorageFactory.create(requireContext(), config)
    }

    // ──────────── 日志刷新 ────────────

    private fun startLogRefresh() {
        stopLogRefresh()
        refreshJob = viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                delay(1000)
                // 日志每 1 秒滚动到底部（仅更新视图，不重新读文件）
                binding.tvLog.text = LogBuffer.getFormattedLogs()
                    .ifEmpty { getString(R.string.status_log_empty) }
                binding.svLog.post { binding.svLog.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    private fun stopLogRefresh() {
        refreshJob?.cancel()
        refreshJob = null
    }

    // ──────────── UI 更新（进入页面时加载一次）────────────

    private fun updateUI() {
        val isRunning = config.isRunning

        // 运行状态
        binding.tvStatus.text = if (isRunning) getString(R.string.status_running)
        else getString(R.string.status_stopped)
        binding.tvStatus.setTextColor(
            if (isRunning) resources.getColor(android.R.color.holo_green_dark)
            else resources.getColor(android.R.color.darker_gray)
        )

        // 按钮
        binding.btnToggle.text = if (isRunning) getString(R.string.btn_stop)
        else getString(R.string.btn_start)

        // 计数
        binding.tvCaptureCount.text = getString(R.string.status_count_format, config.captureCount)

        // 电量/存储/温度
        val battery = BatteryMonitor.getBatteryPercent(requireContext())
        val storageGb = BatteryMonitor.getStorageRemainingGb(storage.getPhotoDir())
        val temp = BatteryMonitor.getBatteryTemperature(requireContext())
        binding.tvBattery.text = getString(R.string.status_battery_format, battery)
        binding.tvStorage.text = getString(R.string.status_storage_format, storageGb)
        binding.tvTemperature.text = getString(R.string.status_temperature_format, temp)

        // 日志
        binding.tvLog.text = LogBuffer.getFormattedLogs()
            .ifEmpty { getString(R.string.status_log_empty) }
    }

    // ──────────── 开始/停止 ────────────

    private fun toggleCapture() {
        val context = requireContext()
        config = if (config.isRunning) {
            context.startService(Intent(context, CaptureService::class.java).apply {
                action = CaptureService.ACTION_STOP
            })
            config.copy(isRunning = false)
        } else {
            val now = System.currentTimeMillis()
            context.startForegroundService(Intent(context, CaptureService::class.java).apply {
                action = CaptureService.ACTION_START
            })
            config.copy(isRunning = true, lastCaptureTime = now)
        }
        config.save(context)
        updateUI()
    }

    companion object {
        fun newInstance() = StatusFragment()
    }
}
