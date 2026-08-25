package com.timelapse.camera.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.timelapse.camera.config.CaptureConfig
import com.timelapse.camera.service.CaptureService
import com.timelapse.camera.service.WatchdogService

/**
 * 开机自启接收器 —— 手机重启后恢复拍摄服务。
 *
 * 教学要点：
 * - 旧手机可能因电量耗尽关机，充电重启后需自动恢复拍摄
 * - 直接启动持久化前台服务（不再通过 AlarmManager 间接调度）
 * - 读取 isRunning 标志判断是否应恢复
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        val config = CaptureConfig.load(context)
        if (!config.isRunning) return

        val serviceIntent = Intent(context, CaptureService::class.java).apply {
            action = CaptureService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
        // 守护服务独立进程，持续监控主服务并负责重启
        context.startService(Intent(context, WatchdogService::class.java))
    }
}
