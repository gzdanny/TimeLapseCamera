package com.timelapse.camera.config

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 远程配置获取器 —— 从用户提供的 URL 拉取下次拍摄延迟（秒）。
 *
 * 协议约定：
 * - URL 返回内容应为纯整数字符串，取值范围 15-3600
 * - 返回无效或网络失败时返回 null，调用方回退到上次有效值
 *
 * 教学要点：
 * - 使用 withContext(Dispatchers.IO) 切到 IO 线程，避免阻塞主线程
 * - 超时设置为 10 秒，避免网络不通时长时间阻塞拍摄服务
 *
 * 安全说明：
 * - 强烈建议使用 HTTPS URL：HTTP 明文传输存在中间人篡改风险（攻击者可改写间隔值）
 * - Android 9+ 默认禁用明文 HTTP（cleartextTrafficPermitted=false），
 *   除非 Manifest 配置 networkSecurityConfig，否则 http:// URL 会直接连接失败
 * - 纵深防御：即使被篡改，返回值仍被 15-3600 范围校验拦截，攻击面有限
 */
class RemoteConfigFetcher {

    suspend fun fetchNextInterval(url: String): Int? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                requestMethod = "GET"
            }
            try {
                connection.inputStream.bufferedReader().use { reader ->
                    val raw = reader.readText().trim()
                    val value = raw.toIntOrNull()
                    if (value != null && value in 15..3600) value else null
                }
            } finally {
                // disconnect() 释放底层 socket，避免连接滞留在 keep-alive 池
                connection.disconnect()
            }
        }.getOrNull()
    }
}
