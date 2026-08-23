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
 */
class RemoteConfigFetcher {

    suspend fun fetchNextInterval(url: String): Int? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                requestMethod = "GET"
            }
            connection.inputStream.bufferedReader().use { reader ->
                val raw = reader.readText().trim()
                val value = raw.toIntOrNull()
                if (value != null && value in 15..3600) value else null
            }
        }.getOrNull()
    }
}
