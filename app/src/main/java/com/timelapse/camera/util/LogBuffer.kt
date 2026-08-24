package com.timelapse.camera.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 内存日志环形缓冲 —— 服务端写，UI 端读。
 *
 * 为什么不用 logcat？
 * - logcat 需要 READ_LOGS 权限（Android 4.1+ 普通应用无法获取）
 * - 教学项目要让用户在 UI 上直接看到运行日志，方便 debug
 *
 * 设计要点：
 * - 环形缓冲：超过 maxSize 自动丢弃最旧条目，内存恒定
 * - synchronized：CaptureService（IO 线程）写、StatusFragment（主线程）读，需同步
 * - 只保留最近 50 条：足够回溯问题，不占内存
 */
object LogBuffer {

    private const val MAX_SIZE = 50
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val logs = mutableListOf<Entry>()

    fun log(level: String, tag: String, message: String) {
        synchronized(logs) {
            logs.add(Entry(System.currentTimeMillis(), level, tag, message))
            while (logs.size > MAX_SIZE) logs.removeAt(0)
        }
    }

    fun getFormattedLogs(): String = synchronized(logs) {
        if (logs.isEmpty()) return ""
        logs.joinToString("\n") { entry ->
            val time = timeFormat.format(Date(entry.timestamp))
            "[$time] ${entry.level}/${entry.tag}: ${entry.message}"
        }
    }

    fun clear() = synchronized(logs) { logs.clear() }

    data class Entry(
        val timestamp: Long,
        val level: String,
        val tag: String,
        val message: String
    )
}
