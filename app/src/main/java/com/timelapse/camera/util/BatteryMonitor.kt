package com.timelapse.camera.util

import android.content.Context
import android.os.BatteryManager
import android.os.StatFs
import java.io.File

/**
 * 电池与存储状态读取工具。
 *
 * 所有读取都是即时的、同步的，不需要权限（API 26+ 直接可用）。
 * 设计为 object 单例，因为这些都是系统级只读信息，不需要维护状态。
 */
object BatteryMonitor {

    /** 读取当前电量百分比（0-100） */
    fun getBatteryPercent(context: Context): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    /** 读取电池温度（摄氏度）。BatteryManager 返回的是 0.1°C 单位，需除以 10。 */
    fun getBatteryTemperature(context: Context): Float {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        // BATTERY_PROPERTY_TEMPERATURE 在 API 26+ 可用，返回值单位为 0.1°C
        val raw = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_TEMPERATURE)
        return raw / 10f
    }

    /**
     * 读取指定存储目录所在分区的剩余空间（GB）。
     *
     * 设计要点：
     * - 接收 storageDir 参数而非 Context，避免工具类对存储路径做假设
     * - 调用方传入实际的照片存储目录，确保读取的分区与写入一致
     * - StatFs 读取的是整个分区的可用空间，不是单个目录的配额
     */
    fun getStorageRemainingGb(storageDir: File): Float {
        val statFs = StatFs(storageDir.absolutePath)
        val availableBytes = statFs.availableBytes
        return availableBytes / (1024f * 1024f * 1024f)
    }
}
