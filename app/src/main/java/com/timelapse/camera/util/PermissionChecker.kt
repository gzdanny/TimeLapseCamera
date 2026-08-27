package com.timelapse.camera.util

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * 权限检查与跳转工具。
 *
 * 设计原则：
 * - 能用标准 API 检测真实状态的，就做状态展示 + 跳转
 * - 检测不了的（如国产 ROM 的自启动权限），诚实说明，只跳应用详情页
 * - 每个权限都有 check() 和 intent() 两个方法，调用方按需使用
 *
 * 教学要点：
 * - 不同权限的检测方式不同：有的是 checkSelfPermission，有的是 Manager 类的专用方法
 * - 跳转到不同设置页用不同的 Settings Action
 * - 部分权限（如电池优化）需要特殊的 Intent 格式
 */
object PermissionChecker {

    // ──────────── 相机权限 ────────────

    fun hasCameraPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    fun cameraPermissionIntent(context: Context): Intent =
        appDetailsIntent(context)

    // ──────────── 通知权限 ────────────

    fun hasNotificationPermission(context: Context): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.areNotificationsEnabled()
    }

    fun notificationSettingsIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
        } else {
            appDetailsIntent(context)
        }
    }

    // ──────────── 精确闹钟权限 ────────────

    fun canScheduleExactAlarms(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.canScheduleExactAlarms()
        } else {
            // Android 12 以下不需要申请精确闹钟权限
            true
        }
    }

    fun exactAlarmSettingsIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.fromParts("package", context.packageName, null)
            }
        } else {
            appDetailsIntent(context)
        }
    }

    // ──────────── 忽略电池优化 ────────────

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun batteryOptimizationIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    // ──────────── 应用详情页（兜底跳转） ────────────

    /**
     * 跳转到应用详情页。
     * 对于无法精确跳转的设置项（如国产 ROM 的自启动），统一跳这里，用户自己找。
     */
    fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
}
