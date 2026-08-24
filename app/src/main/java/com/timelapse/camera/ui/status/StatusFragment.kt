package com.timelapse.camera.ui.status

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import coil.load
import com.timelapse.camera.R
import com.timelapse.camera.config.CaptureConfig
import com.timelapse.camera.databinding.FragmentStatusBinding
import com.timelapse.camera.service.CaptureService
import com.timelapse.camera.storage.IPhotoStorage
import com.timelapse.camera.storage.PhotoStorageFactory
import com.timelapse.camera.util.BatteryMonitor
import com.timelapse.camera.util.LogBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 状态页 Fragment —— 默认首页。
 *
 * 核心信息：
 * - 运行状态 + 大倒计时
 * - 最近一张照片缩略图
 * - 统计：拍摄数量、电量、存储、温度
 * - 开始/停止按钮
 *
 * 设计要点：
 * - 倒计时和状态数据每 1 秒刷新一次（足够精准，又不耗电）
 * - 用 lifecycleScope，页面不可见时自动停止刷新
 * - 最近一张照片从文件系统读，不做内存缓存（照片不多时完全够用）
 */
class StatusFragment : Fragment() {

    private var _binding: FragmentStatusBinding? = null
    private val binding get() = _binding!!

    private lateinit var config: CaptureConfig
    private lateinit var storage: IPhotoStorage

    private var refreshJob: Job? = null
    private var nextCaptureTime: Long = 0L

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
        binding.btnToggle.setOnClickListener {
            toggleCapture()
        }
    }

    override fun onResume() {
        super.onResume()
        // 单一数据源：一次磁盘读取，config 和 storage 同源
        reloadFromDisk()
        if (config.isRunning) {
            nextCaptureTime = calculateNextCaptureTime(config)
        }
        updateUI()
        startRefreshLoop()
    }

    override fun onPause() {
        super.onPause()
        stopRefreshLoop()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /**
     * 从磁盘重新加载 config，并基于同一份 config 创建 storage。
     * 保证 config 和 storage 永远来自同一次读取，消除不同步风险。
     */
    private fun reloadFromDisk() {
        config = CaptureConfig.load(requireContext())
        storage = PhotoStorageFactory.create(requireContext(), config)
    }

    // ──────────── 状态刷新循环 ────────────

    private fun startRefreshLoop() {
        stopRefreshLoop()
        refreshJob = viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                updateUI()
                delay(1000)
            }
        }
    }

    private fun stopRefreshLoop() {
        refreshJob?.cancel()
        refreshJob = null
    }

    private fun updateUI() {
        val isRunning = config.isRunning

        // 运行状态
        binding.tvStatus.text = if (isRunning) getString(R.string.status_running)
        else getString(R.string.status_stopped)
        binding.tvStatus.setTextColor(
            if (isRunning) resources.getColor(android.R.color.holo_green_dark)
            else resources.getColor(android.R.color.darker_gray)
        )

        // 倒计时
        if (isRunning && nextCaptureTime > System.currentTimeMillis()) {
            val remaining = nextCaptureTime - System.currentTimeMillis()
            binding.tvCountdown.text = formatDuration(remaining)
        } else {
            binding.tvCountdown.text = "--:--:--"
        }

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

        // 运行日志
        val logs = LogBuffer.getFormattedLogs()
        binding.tvLog.text = logs.ifEmpty { getString(R.string.status_log_empty) }

        // 最近一张照片：读最新一张文件很快，直接刷新即可
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val latest = storage.getLatestPhoto()
            withContext(Dispatchers.Main) {
                if (latest != null) {
                    binding.ivLatest.load(latest) {
                        placeholder(android.R.color.darker_gray)
                    }
                    val timeStr = SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss", Locale.getDefault()
                    ).format(Date(latest.lastModified()))
                    binding.tvLatestTime.text = timeStr
                } else {
                    binding.ivLatest.setImageResource(android.R.color.darker_gray)
                    binding.tvLatestTime.text = getString(R.string.status_no_photo_yet)
                }
            }
        }
    }

    // ──────────── 开始/停止 ────────────

    private fun toggleCapture() {
        val context = requireContext()
        config = if (config.isRunning) {
            // 停止：通过 startService 发 ACTION_STOP
            val intent = Intent(context, CaptureService::class.java).apply {
                action = CaptureService.ACTION_STOP
            }
            context.startService(intent)
            config.copy(isRunning = false)
        } else {
            // 开始
            val now = System.currentTimeMillis()
            val intent = Intent(context, CaptureService::class.java).apply {
                action = CaptureService.ACTION_START
            }
            context.startForegroundService(intent)
            nextCaptureTime = now + config.intervalSeconds * 1000L
            // 立刻写入 lastCaptureTime，保证 onResume 重新计算倒计时有基准
            config.copy(isRunning = true, lastCaptureTime = now)
        }
        config.save(context)
        updateUI()
    }

    /**
     * 根据 lastCaptureTime 推算下次拍摄时间。
     *
     * 逻辑：
     * - 有 lastCaptureTime → lastCaptureTime + 间隔 = 下次时间
     * - 没有 lastCaptureTime（刚启动还没拍过）→ 当前时间 + 间隔
     * - 如果下次时间已经过了（比如服务被杀刚恢复）→ 仍然显示下次时间，
     *   倒计时会显示 00:00:00，不显示负数
     */
    private fun calculateNextCaptureTime(config: CaptureConfig): Long {
        val intervalMs = config.intervalSeconds * 1000L
        return if (config.lastCaptureTime > 0) {
            // 从上次拍摄时间推算
            (config.lastCaptureTime + intervalMs)
                .coerceAtLeast(System.currentTimeMillis()) // 防止显示负数倒计时
        } else {
            // 刚启动还没拍过，从当前时间开始算
            System.currentTimeMillis() + intervalMs
        }
    }

    // ──────────── 工具方法 ────────────

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return "%02d:%02d:%02d".format(hours, minutes, seconds)
    }

    companion object {
        fun newInstance() = StatusFragment()
    }
}
