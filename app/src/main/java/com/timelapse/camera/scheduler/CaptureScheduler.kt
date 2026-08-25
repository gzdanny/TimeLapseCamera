package com.timelapse.camera.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.timelapse.camera.util.LogBuffer

/**
 * 拍摄调度器 —— 基于 AlarmManager 的精确定时唤醒。
 *
 * 角色：前台服务的备份机制。服务正常运行时由协程 delay 调度；
 * 服务被杀后，AlarmManager 把服务拉起来恢复拍摄。
 *
 * 功耗策略：
 * - 使用 setExactAndAllowWhileIdle 在 Doze 模式下也能精确唤醒
 * - 拍摄间隙手机深度休眠，CPU 和摄像头零功耗
 * - 仅在拍摄瞬间短暂唤醒（秒级），之后立即释放
 *
 * 教学要点：
 * - AlarmManager.RTC_WAKEUP 唤醒 CPU 但不点亮屏幕，省电
 * - PendingIntent.FLAG_IMMUTABLE 在 API 31+ 为强制要求（低版本建议但不崩）
 */
class CaptureScheduler private constructor(private val context: Context) {

    companion object {
        const val ACTION_TRIGGER_CAPTURE = "com.timelapse.camera.TRIGGER_CAPTURE"
        private const val REQUEST_CODE = 1001
        private const val TAG = "CaptureScheduler"

        @Volatile private var instance: CaptureScheduler? = null
        fun get(context: Context): CaptureScheduler =
            instance ?: synchronized(this) {
                instance ?: CaptureScheduler(context.applicationContext).also { instance = it }
            }
    }

    private val alarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /**
     * 安排下次拍摄。
     * @param delaySeconds 距下次拍摄的延迟秒数
     */
    fun scheduleNext(delaySeconds: Int) {
        val triggerAt = System.currentTimeMillis() + delaySeconds * 1000L
        val pendingIntent = buildPendingIntent()
        if (pendingIntent == null) {
            Log.e(TAG, "创建 PendingIntent 失败，无法安排拍摄")
            return
        }

        try {
            // 优先使用精确闹钟（可在 Doze 下唤醒）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                !alarmManager.canScheduleExactAlarms()
            ) {
                // API 31+ 未授予精确闹钟权限，退化为非精确
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent
                )
                LogBuffer.log("W", TAG, "精确闹钟权限未授予，退化为非精确闹钟")
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent
                )
            }
        } catch (e: SecurityException) {
            // 兜底：SecurityException 时再尝试非精确
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent
            )
            LogBuffer.log("W", TAG, "精确闹钟被拒绝，退化为非精确闹钟", e)
        }

        Log.i(TAG, "已安排下次拍摄：${delaySeconds}秒后")
    }

    /**
     * 取消已安排的备份闹钟。
     * 用户主动停止拍摄时调用，避免闹钟在后台无意义地唤醒服务。
     */
    fun cancel() {
        val pendingIntent = buildPendingIntent(PendingIntent.FLAG_NO_CREATE)
        pendingIntent?.let { alarmManager.cancel(it) }
        LogBuffer.log("I", TAG, "已取消拍摄计划")
    }

    private fun buildPendingIntent(flags: Int = PendingIntent.FLAG_UPDATE_CURRENT): PendingIntent? {
        val intent = Intent(context, CaptureReceiver::class.java).apply {
            action = ACTION_TRIGGER_CAPTURE
        }
        return PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            flags or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
