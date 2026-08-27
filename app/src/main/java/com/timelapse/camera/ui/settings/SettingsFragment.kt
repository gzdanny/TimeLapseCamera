package com.timelapse.camera.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import com.timelapse.camera.R
import com.timelapse.camera.camera.CameraEnumerator
import com.timelapse.camera.config.CaptureConfig
import com.timelapse.camera.config.StorageLocation
import com.timelapse.camera.databinding.FragmentSettingsBinding
import com.timelapse.camera.storage.PhotoStorageFactory
import com.timelapse.camera.util.PermissionChecker
import android.os.Environment
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.timelapse.camera.config.RemoteConfigFetcher
import kotlinx.coroutines.launch
import java.io.File

/**
 * 设置页 Fragment —— 所有可调参数集中管理。
 *
 * - 分组：
 * 1. 拍摄设置：间隔、摄像头方向
 * 2. 存储设置：存储位置选择（App 私有 / DCIM / SD 卡）
 * 3. 水印设置：自定义文字、电量/存储/温度开关
 * 4. 权限与保活：相机、通知、精确闹钟、电池优化（可检测的显示状态，点击跳转）
 * 5. 远程配置：URL 输入
 * 6. 关于：版本号（非分组，底部展示）
 *
 * 设计原则：
 * - 能检测状态的权限，显示"已授权/未授权"，点击跳转对应设置页
 * - 检测不了的（如国产 ROM 自启动），只做跳转入口，不显示假状态
 * - 修改设置后立即保存，不需要"保存"按钮
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var config: CaptureConfig
    /** 枚举到的所有摄像头信息，用于填充下拉列表 */
    private var cameraList: List<CameraEnumerator.CameraInfo> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = CaptureConfig.load(requireContext())
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupCameraSpinner()
        setupShotRotationSpinner()
        setupResolutionSpinner()
        setupStorageLocationSpinner()
        loadConfigToUI()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStatus()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ──────────── 加载配置到 UI ────────────

    private fun loadConfigToUI() {
        binding.etInterval.setText(config.intervalSeconds.toString())
        binding.etWatermarkText.setText(config.watermarkText ?: "")
        binding.switchBattery.isChecked = config.watermarkShowBattery
        binding.switchStorage.isChecked = config.watermarkShowStorage
        binding.switchTemperature.isChecked = config.watermarkShowTemperature
        binding.etRemoteUrl.setText(config.remoteConfigUrl ?: "")
        binding.etStorageThreshold.setText(config.storageThresholdGb.toString())
        binding.etStorageSafeLine.setText(config.storageSafeLineGb.toString())

        // 摄像头选择
        val cameraIndex = cameraList.indexOfFirst { it.cameraId == config.cameraId }
        if (cameraIndex >= 0) {
            binding.spinnerCamera.setSelection(cameraIndex)
        }

        // 拍摄方向（0/90/180/270 对应 4 个选项）
        val rotationIndex = listOf(0, 90, 180, 270).indexOf(config.shotRotation).takeIf { it >= 0 } ?: 1
        binding.spinnerShotRotation.setSelection(rotationIndex)

        // 拍摄分辨率：用已保存的分辨率选中的项，没有则默认第一项
        refreshResolutionSpinner()
        if (config.resolution != null && resolutionOptions.contains(config.resolution)) {
            binding.spinnerResolution.setSelection(resolutionOptions.indexOf(config.resolution))
        } else if (resolutionOptions.isNotEmpty()) {
            binding.spinnerResolution.setSelection(0)
        }

        // 存储位置
        val storagePosition = config.storageLocation.ordinal.coerceAtMost(
            binding.spinnerStorageLocation.count - 1
        )
        binding.spinnerStorageLocation.setSelection(storagePosition)
        updateStoragePathText(config.storageLocation)
    }

    /**
     * 摄像头选择下拉：枚举设备所有摄像头，显示方向/像素/焦距，用户精确选择。
     *
     * 为什么不用 CameraX 的 BACK/FRONT？
     * - 多镜头手机（主摄+超广角+长焦+微距）粗粒度选择不可靠
     * - 直接按 cameraId 选，选到的就是实际使用的，不会错配
     */
    private fun setupCameraSpinner() {
        cameraList = CameraEnumerator.enumerate(requireContext())
        val displayNames = cameraList.map { info ->
            "${info.facingName} · ${info.megapixels} · ${info.focalLengthText} [${info.cameraId}]"
        }
        val adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_item, displayNames
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerCamera.adapter = adapter
    }

    /**
     * 初始化分辨率下拉：默认选中第一个选项（最高分辨率）。
     * 切换摄像头时通过 refreshResolutionSpinner() 重建列表。
     */
    private fun setupResolutionSpinner() {
        resolutionAdapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_item, listOf("加载中…")
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spinnerResolution.adapter = resolutionAdapter
    }

    /**
     * 根据当前选中的摄像头，重建分辨率下拉列表（取 jpegSizes 前 4 项，按面积降序）。
     */
    private fun refreshResolutionSpinner() {
        val currentCameraId = config.cameraId
        val currentInfo = cameraList.find { it.cameraId == currentCameraId }
        val sizes = currentInfo?.jpegSizes?.take(4) ?: emptyList()
        resolutionOptions = sizes.map { "${it.width}x${it.height}" }
        if (resolutionOptions.isEmpty()) {
            resolutionOptions = listOf("${currentInfo?.megapixels ?: "未知"} 最高")
        }
        resolutionAdapter.clear()
        resolutionAdapter.addAll(resolutionOptions)
        resolutionAdapter.notifyDataSetChanged()
    }

    /**
     * 存储位置下拉选项：App 私有 / DCIM / SD 卡（仅插入时显示）。
     */
    private fun setupStorageLocationSpinner() {
        val hasSdCard = PhotoStorageFactory.getSdCardBaseDir(requireContext()) != null
        val options = mutableListOf(
            getString(R.string.storage_app_private),
            getString(R.string.storage_dcim)
        )
        if (hasSdCard) options.add(getString(R.string.storage_sd_card))

        val adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_item, options
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerStorageLocation.adapter = adapter
    }

    /**
     * 显示当前存储位置的实际文件路径预览，让用户知道照片存在哪里。
     */
    private fun updateStoragePathText(location: StorageLocation) {
        val path = when (location) {
            StorageLocation.APP_PRIVATE -> {
                File(
                    requireContext().getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                    "TimeLapse"
                ).absolutePath
            }
            StorageLocation.DCIM -> {
                File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                    "TimeLapse"
                ).absolutePath
            }
            StorageLocation.SD_CARD -> {
                PhotoStorageFactory.getSdCardBaseDir(requireContext())?.let {
                    File(it, "TimeLapse").absolutePath
                } ?: getString(R.string.storage_sd_not_available)
            }
        }
        binding.tvStoragePath.text = path
    }

    // ──────────── 事件监听 ────────────

    private fun setupListeners() {
        // 拍摄间隔
        binding.etInterval.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveInterval()
        }

        // 摄像头选择
        binding.spinnerCamera.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    val selectedId = cameraList.getOrNull(pos)?.cameraId ?: return
                    if (config.cameraId != selectedId) {
                        config = config.copy(cameraId = selectedId)
                        config.save(requireContext())
                    }
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }

        // 拍摄方向
        binding.spinnerShotRotation.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    val rotation = listOf(0, 90, 180, 270)[pos]
                    if (config.shotRotation != rotation) {
                        config = config.copy(shotRotation = rotation)
                        config.save(requireContext())
                    }
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }

        // 拍摄分辨率
        binding.spinnerResolution.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    val selected = resolutionOptions.getOrNull(pos) ?: return
                    if (config.resolution != selected) {
                        config = config.copy(resolution = selected)
                        config.save(requireContext())
                    }
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }

        // 存储位置
        binding.spinnerStorageLocation.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    // pos: 0=APP_PRIVATE, 1=DCIM, 2=SD_CARD（仅插入时存在）
                    val newLocation = when (pos) {
                        0 -> StorageLocation.APP_PRIVATE
                        1 -> StorageLocation.DCIM
                        else -> StorageLocation.SD_CARD
                    }
                    if (config.storageLocation != newLocation) {
                        config = config.copy(storageLocation = newLocation)
                        config.save(requireContext())
                        updateStoragePathText(newLocation)
                    }
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }

        // 水印文字
        binding.etWatermarkText.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val text = binding.etWatermarkText.text?.toString()?.trim()
                val newText = if (text.isNullOrEmpty()) null else text
                if (config.watermarkText != newText) {
                    config = config.copy(watermarkText = newText)
                    config.save(requireContext())
                }
            }
        }

        // 水印开关
        binding.switchBattery.setOnCheckedChangeListener { _, checked ->
            config = config.copy(watermarkShowBattery = checked)
            config.save(requireContext())
        }
        binding.switchStorage.setOnCheckedChangeListener { _, checked ->
            config = config.copy(watermarkShowStorage = checked)
            config.save(requireContext())
        }
        binding.switchTemperature.setOnCheckedChangeListener { _, checked ->
            config = config.copy(watermarkShowTemperature = checked)
            config.save(requireContext())
        }

        // 远程配置 URL：保存前先做一次 test fetch 验证
        binding.etRemoteUrl.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val url = binding.etRemoteUrl.text?.toString()?.trim()
                if (url.isNullOrEmpty()) {
                    // 空值 = 禁用远程配置，允许保存
                    if (config.remoteConfigUrl != null) {
                        config = config.copy(remoteConfigUrl = null)
                        config.save(requireContext())
                    }
                } else if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    // 格式校验：必须是 http/https
                    Toast.makeText(requireContext(), R.string.validation_url_invalid, Toast.LENGTH_SHORT).show()
                    binding.etRemoteUrl.setText(config.remoteConfigUrl ?: "")
                } else if (config.remoteConfigUrl != url) {
                    // 格式正确 → 实际抓取验证
                    verifyAndSaveUrl(url)
                }
            }
        }

        // 存储清理阈值
        binding.etStorageThreshold.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveStorageThreshold()
        }
        binding.etStorageSafeLine.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveStorageSafeLine()
        }

        // 权限跳转
        binding.rowCameraPermission.setOnClickListener {
            startActivity(PermissionChecker.cameraPermissionIntent(requireContext()))
        }
        binding.rowNotificationPermission.setOnClickListener {
            startActivity(PermissionChecker.notificationSettingsIntent(requireContext()))
        }
        binding.rowExactAlarm.setOnClickListener {
            startActivity(PermissionChecker.exactAlarmSettingsIntent(requireContext()))
        }
        binding.rowBatteryOptimization.setOnClickListener {
            startActivity(PermissionChecker.batteryOptimizationIntent(requireContext()))
        }
        binding.rowAppDetails.setOnClickListener {
            startActivity(PermissionChecker.appDetailsIntent(requireContext()))
        }
    }

    /**
     * 保存拍摄间隔，带边界校验。
     * - 空输入或非法值 → 回退到默认 3600 秒
     * - 超出 15-86400 范围 → 自动修正到边界值并 Toast 提示
     */
    private fun saveInterval() {
        val input = binding.etInterval.text?.toString()?.toIntOrNull()
        if (input == null) {
            // 非数字输入，回退到默认值
            binding.etInterval.setText("3600")
            return
        }
        val interval = input.coerceIn(15, 86400)
        if (interval != input) {
            // 值被修正，提示用户
            Toast.makeText(requireContext(), R.string.validation_interval_range, Toast.LENGTH_SHORT).show()
        }
        if (config.intervalSeconds != interval) {
            config = config.copy(intervalSeconds = interval)
            config.save(requireContext())
        }
        // 修正显示值
        if (binding.etInterval.text?.toString()?.toIntOrNull() != interval) {
            binding.etInterval.setText(interval.toString())
        }
    }

    /**
     * 保存前先做一次 test fetch 验证 URL 可用性。
     * - 抓取成功且返回 15-3600 整数 → 保存 + Toast 显示返回值
     * - 抓取失败或返回无效 → 不保存 + Toast 提示 + 恢复原值
     *
     * 这比单纯格式校验更可靠：用户立刻知道 URL 是否真的能用，
     * 而不是等到 Service 几小时后拍摄时才发现超时。
     */
    private fun verifyAndSaveUrl(url: String) {
        // 禁用输入框 + 提示正在验证
        binding.etRemoteUrl.isEnabled = false
        Toast.makeText(requireContext(), R.string.validation_url_verifying, Toast.LENGTH_SHORT).show()

        viewLifecycleOwner.lifecycleScope.launch {
            val result = RemoteConfigFetcher().fetchNextInterval(url)

            if (result != null) {
                // 验证成功，保存
                config = config.copy(remoteConfigUrl = url, lastRemoteInterval = result)
                config.save(requireContext())
                Toast.makeText(
                    requireContext(),
                    getString(R.string.validation_url_verified, result),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                // 验证失败，不保存，恢复原值
                Toast.makeText(requireContext(), R.string.validation_url_fetch_failed, Toast.LENGTH_LONG).show()
                binding.etRemoteUrl.setText(config.remoteConfigUrl ?: "")
            }

            binding.etRemoteUrl.isEnabled = true
        }
    }

    /**
     * 保存存储清理阈值，带校验：必须 > 0 且 < 安全线。
     */
    private fun saveStorageThreshold() {
        val input = binding.etStorageThreshold.text?.toString()?.toFloatOrNull()
        if (input == null || input <= 0) {
            binding.etStorageThreshold.setText(config.storageThresholdGb.toString())
            return
        }
        if (input >= config.storageSafeLineGb) {
            Toast.makeText(requireContext(), R.string.validation_storage_range, Toast.LENGTH_SHORT).show()
            binding.etStorageThreshold.setText(config.storageThresholdGb.toString())
            return
        }
        if (config.storageThresholdGb != input) {
            config = config.copy(storageThresholdGb = input)
            config.save(requireContext())
        }
    }

    /**
     * 保存存储清理安全线，带校验：必须 > 阈值。
     */
    private fun saveStorageSafeLine() {
        val input = binding.etStorageSafeLine.text?.toString()?.toFloatOrNull()
        if (input == null || input <= 0) {
            binding.etStorageSafeLine.setText(config.storageSafeLineGb.toString())
            return
        }
        if (input <= config.storageThresholdGb) {
            Toast.makeText(requireContext(), R.string.validation_storage_range, Toast.LENGTH_SHORT).show()
            binding.etStorageSafeLine.setText(config.storageSafeLineGb.toString())
            return
        }
        if (config.storageSafeLineGb != input) {
            config = config.copy(storageSafeLineGb = input)
            config.save(requireContext())
        }
    }

    // ──────────── 权限状态刷新 ────────────

    private fun refreshPermissionStatus() {
        // 相机权限
        val cameraGranted = PermissionChecker.hasCameraPermission(requireContext())
        binding.tvCameraPermission.text = if (cameraGranted) getString(R.string.permission_granted)
        else getString(R.string.permission_denied)
        binding.tvCameraPermission.setTextColor(
            if (cameraGranted) resources.getColor(android.R.color.holo_green_dark)
            else resources.getColor(android.R.color.holo_red_dark)
        )

        // 通知权限
        val notifGranted = PermissionChecker.hasNotificationPermission(requireContext())
        binding.tvNotificationPermission.text = if (notifGranted) getString(R.string.permission_granted)
        else getString(R.string.permission_denied)
        binding.tvNotificationPermission.setTextColor(
            if (notifGranted) resources.getColor(android.R.color.holo_green_dark)
            else resources.getColor(android.R.color.holo_red_dark)
        )

        // 精确闹钟
        val exactAlarm = PermissionChecker.canScheduleExactAlarms(requireContext())
        binding.tvExactAlarm.text = if (exactAlarm) getString(R.string.permission_enabled)
        else getString(R.string.permission_disabled)
        binding.tvExactAlarm.setTextColor(
            if (exactAlarm) resources.getColor(android.R.color.holo_green_dark)
            else resources.getColor(android.R.color.holo_red_dark)
        )

        // 电池优化
        val ignoring = PermissionChecker.isIgnoringBatteryOptimizations(requireContext())
        binding.tvBatteryOptimization.text = if (ignoring) getString(R.string.permission_enabled)
        else getString(R.string.permission_disabled)
        binding.tvBatteryOptimization.setTextColor(
            if (ignoring) resources.getColor(android.R.color.holo_green_dark)
            else resources.getColor(android.R.color.holo_red_dark)
        )
    }

    companion object {
        fun newInstance() = SettingsFragment()
    }
}
