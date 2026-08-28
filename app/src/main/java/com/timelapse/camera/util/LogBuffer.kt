package com.timelapse.camera.util

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
 * - 内存缓冲最多保留 500 条，文件超过 ~100KB 自动截断（保留最近条目）
 *
 * 线程安全：
 * - 内存缓冲和文件追加共用 logs 锁，多协程并发写不会交错
 * - SimpleDateFormat 非线程安全，log() 中每次局部创建实例使用
 */
object LogBuffer {

    private const val MAX_SIZE = 500

    private val logs = mutableListOf<String>()
    private var logFile: File? = null
    @Volatile private var initialized = false

    /**
     * 初始化日志文件路径，并从文件加载历史日志。
     * 应在 CaptureService.onCreate 和 StatusFragment.onCreate 中调用。
     *
     * 支持目录变更：存储位置切换后传入新目录，会切换到新日志文件
     * （旧文件保留，内存缓冲改为加载新目录下的历史日志）。
     *
     * @param logFileDir 日志文件所在目录（通常传入 storage.getPhotoDir()，与照片同目录）
     */
    fun init(logFileDir: File) {
        synchronized(logs) {
            val newFile = File(logFileDir, "timelapse_log.txt")
            if (initialized && newFile.absolutePath == logFile?.absolutePath) return

            // 首次初始化，或目录变更：加载新目录下的历史日志
            logs.clear()
            if (newFile.exists()) {
                runCatching {
                    newFile.readLines().takeLast(MAX_SIZE).forEach { logs.add(it) }
                }
            }
            logFile = newFile
            initialized = true
        }
    }

    fun log(level: String, tag: String, message: String) {
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val line = "[${timeFormat.format(Date())}] $level/$tag: $message"
        synchronized(logs) {
            logs.add(line)
            while (logs.size > MAX_SIZE) logs.removeAt(0)
            appendToFileLocked(line)
        }
    }

    fun getFormattedLogs(): String = synchronized(logs) {
        if (logs.isEmpty()) "" else logs.joinToString("\n")
    }

    fun clear() {
        synchronized(logs) {
            logs.clear()
            logFile?.let { runCatching { it.writeText("") } }
        }
    }

    /** 追加一行到日志文件。调用方必须已持有 logs 锁（文件写入与内存缓冲保持一致顺序）。 */
    private fun appendToFileLocked(line: String) {
        val file = logFile ?: return
        runCatching {
            FileOutputStream(file, true).use { it.write((line + "\n").toByteArray()) }
            // 200 = 每行平均字节数的估计值（时间戳+级别+标签+消息 ≈ 100-300 字节），
            // 故截断阈值 ≈ 500 行 × 200B = 100KB
            if (file.length() > MAX_SIZE * 200L) {
                file.readLines().takeLast(MAX_SIZE).let { lines ->
                    file.writeText(lines.joinToString("\n", postfix = "\n"))
                }
            }
        }
    }
}
