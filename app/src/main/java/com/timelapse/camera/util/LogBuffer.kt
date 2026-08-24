package com.timelapse.camera.util

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 内存日志环形缓冲 —— 服务端写，UI 端读，崩溃后可恢复。
 *
 * 为什么不用 logcat？
 * - logcat 需要 READ_LOGS 权限（Android 4.1+ 普通应用无法获取）
 * - 教学项目要让用户在 UI 上直接看到运行日志，方便 debug
 *
 * 持久化设计：
 * - 每条日志同时写入内存缓冲和文件（append 模式）
 * - 崩溃后重启，init() 从文件加载历史日志到内存
 * - 文件超过 MAX_SIZE 行时自动截断（保留最近条目）
 */
object LogBuffer {

    private const val MAX_SIZE = 50
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val logs = mutableListOf<String>()
    private var logFile: File? = null
    @Volatile private var initialized = false

    /**
     * 初始化日志文件路径，并从文件加载历史日志。
     * 应在 CaptureService.onCreate 和 StatusFragment.onCreate 中调用。
     * 重复调用安全（仅第一次生效）。
     */
    fun init(context: Context) {
        if (initialized) return
        synchronized(logs) {
            if (initialized) return
            // 写在外部文件目录，用户可通过文件管理器访问（无需 Root）
            val dir = context.getExternalFilesDir(null)
                ?: throw IllegalStateException("无法获取外部文件目录")
            logFile = File(dir, "timelapse_log.txt")
            if (logFile!!.exists()) {
                runCatching {
                    logFile!!.readLines().takeLast(MAX_SIZE).forEach { logs.add(it) }
                }
            }
            initialized = true
        }
    }

    fun log(level: String, tag: String, message: String) {
        val time = timeFormat.format(Date())
        val line = "[$time] $level/$tag: $message"
        synchronized(logs) {
            logs.add(line)
            while (logs.size > MAX_SIZE) logs.removeAt(0)
        }
        appendToFile(line)
    }

    fun getFormattedLogs(): String = synchronized(logs) {
        if (logs.isEmpty()) "" else logs.joinToString("\n")
    }

    fun clear() {
        synchronized(logs) { logs.clear() }
        logFile?.let { it.writeText("") }
    }

    private fun appendToFile(line: String) {
        val file = logFile ?: return
        runCatching {
            FileOutputStream(file, true).use { it.write((line + "\n").toByteArray()) }
            if (file.length() > MAX_SIZE * 200L) {
                file.readLines().takeLast(MAX_SIZE).let { lines ->
                    file.writeText(lines.joinToString("\n", postfix = "\n"))
                }
            }
        }
    }
}
