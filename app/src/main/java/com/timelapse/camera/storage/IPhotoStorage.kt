package com.timelapse.camera.storage

import android.graphics.Bitmap
import java.io.File

/**
 * 照片存储接口 —— 存储策略抽象。
 *
 * 设计意图（模块插拔）：
 * - LocalPhotoStorage: 第一版本地存储实现
 * - 后续可新增 FifoCleaner（自动删除旧照片释放空间）
 * - 后续可新增 CloudUploader（加密上传专用服务器）
 * 调用方（CaptureService）只依赖此接口，不关心底层存储细节。
 */
interface IPhotoStorage {

    /**
     * 保存照片到存储。
     * @param bitmap 带水印的照片
     * @param timestamp 拍摄时间戳，用于命名和归档
     * @return 保存后的文件路径
     */
    suspend fun save(bitmap: Bitmap, timestamp: Long): String

    /**
     * 保存试拍照片（固定文件名 Test.jpg，每次覆盖）。
     * 与 save() 走相同的存储路径，但不按时间归档，方便用户快速定位检查。
     * @param bitmap 带水印的试拍照片
     * @return 保存后的文件路径
     */
    suspend fun saveTestPhoto(bitmap: Bitmap): String

    /** 已存储照片数量 */
    fun getPhotoCount(): Int

    /** 照片根目录（便于后续 FIFO 清理或导出） */
    fun getPhotoDir(): File

    /** 获取最近一张照片（按文件名排序，最新的在前），没有则返回 null */
    fun getLatestPhoto(): File?

    /** 获取所有照片列表（按时间倒序） */
    fun getAllPhotos(): List<File>
}
