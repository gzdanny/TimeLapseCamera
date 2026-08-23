package com.timelapse.camera.camera

import com.timelapse.camera.model.CaptureResult

/**
 * 相机控制器接口 —— 拍摄能力抽象。
 *
 * 设计意图（模块插拔）：
 * - 当前使用 CameraX 实现（CameraXController），最低 API 26
 * - 后续兼容更老的设备（API 21-25）可新增 Camera1Controller 实现此接口
 * - CaptureService 只依赖接口，不关心底层 CameraX/Camera1 切换
 */
interface ICameraController {

    /**
     * 执行一次拍摄，返回成功(含 Bitmap + 时间戳) 或失败。
     * 每次调用内部完成 "打开摄像头 → 拍摄 → 关闭摄像头" 全流程，
     * 调用结束后摄像头已释放，不持续占用硬件资源。
     */
    suspend fun capture(): CaptureResult

    /** 释放底层资源（CameraXController 在 capture 内部已自动释放，显式调用用于异常兜底） */
    fun release()
}
