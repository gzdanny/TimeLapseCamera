package com.timelapse.camera.service

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.MainScope
import com.timelapse.camera.R
import com.timelapse.camera.scheduler.CaptureScheduler
import com.timelapse.camera.util.LogBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 独立进程的守护服务，用于在主服务被杀后自动重启。
 *
 * 设计原理：
 * - 运行在 :watchdog 进程，与主服务完全隔离
 * - 每 60s 检查一次主服务进程是否存活
 * - 不存活时立即重新注册拍摄闹钟以触发服务重启
 * - 持有 WakeLock 防止息屏时守护进程本身被杀
 *
 * 这是一个简单但有效的进程级保活方案：
 * 即使系统杀掉了主服务进程，只要 Watchdog 进程存活，就能自动恢复。
 */
class WatchdogService : Service() {

    companion object {
        private const val TAG = "WatchdogService"
        private const val CHANNEL_ID = "timelapse_watchdog"
        private const val NOTIFICATION_ID = 99
        private const val CHECK_INTERVAL_MS = 60_000L // 每 60s 检查一次
    }

    private val serviceScope = MainScope()
    private var checkJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        acquireWakeLock()
        startCheckLoop()
        LogBuffer.log("I", TAG, "守护服务启动")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    /**
     * 每 60s 检查一次主服务进程是否存活。
     * 如果主进程已死，重新注册拍摄闹钟（5秒后）以触发服务重启。
     */
    private fun startCheckLoop() {
        checkJob = serviceScope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(CHECK_INTERVAL_MS)
                if (!isMainServiceRunning()) {
                    LogBuffer.log("W", TAG, "主服务进程未运行，尝试重启")
                    // 设一个 5 秒后的闹钟，触发 CaptureReceiver → 重启主服务
                    CaptureScheduler.get(this@WatchdogService).scheduleNext(5)
                } else {
                    LogBuffer.log("I", TAG, "主服务运行正常")
                }
            }
        }
    }

    /**
     * 检查主服务（CaptureService）是否在运行中。
     */
    private fun isMainServiceRunning(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val running = am.getRunningServices(Int.MAX_VALUE)
        return running.any { it.service.className == CaptureService::class.java.name }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_camera)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentTitle("延时相机守护中")
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "守护服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "延时相机后台守护，请勿关闭" }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "${applicationContext.packageName}:watchdog"
        ).apply { acquire(10 * 60 * 1000L /* 10分钟 */) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        checkJob?.cancel()
        releaseWakeLock()
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
