package com.timelapse.camera.camera

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 相机互斥锁 —— 串行化进程内所有「绑定/解绑 CameraX 用例」的操作。
 *
 * 为什么需要？
 * ProcessCameraProvider 是进程级单例，拍摄服务（CaptureService 的
 * CameraXController）与预览页（PreviewFragment 的 Preview 用例）共用它。
 * 任何一方的 unbindAll() 都会解绑对方正在使用的用例：
 * - 服务拍照时用户进入预览页 → 预览绑定打断拍照 → 拍摄失败
 * - 预览运行中服务拍照 → 拍完 release() 解绑预览 → 预览画面冻结
 *
 * 所有绑定/解绑入口（CameraXController.capture、PreviewFragment 的
 * 预览启停）都必须持有此锁，将冲突从「互相打断」降级为「排队等待」。
 */
object CameraMutex {

    private val mutex = Mutex()

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}
