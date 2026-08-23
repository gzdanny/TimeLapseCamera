package com.timelapse.camera.storage

import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本地照片存储实现（App 私有目录方式）。
 *
 * 目录结构：
 *   {app外部文件目录}/Pictures/TimeLapse/
 *     ├── 2026-03/
 *     │   ├── 20260301_080000.jpg
 *     │   └── ...
 *     └── 2026-04/
 *
 * 教学要点：
 * - 用 getExternalFilesDir() 存储，无需申请存储权限（API 26+ 友好）
 * - 按年月分目录，方便后续 FIFO 清理或导出
 * - 保存操作在 IO 线程执行，避免阻塞主线程
 * - save() 是 Bitmap 内存责任链的终点：compress 后立即 recycle
 *
 * 复用设计：SD 卡存储也用本类，只需传入 SD 卡的私有目录作为 baseDir。
 * 两者都是 App 私有目录，权限和写入方式完全一样，无需 MediaStore。
 */
class LocalPhotoStorage(
    context: Context,
    private val customBaseDir: File? = null
) : IPhotoStorage {

    private val baseDir = (customBaseDir ?: File(
        context.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
        "TimeLapse"
    )).also { if (!it.exists()) it.mkdirs() }

    override suspend fun save(bitmap: Bitmap, timestamp: Long): String = withContext(Dispatchers.IO) {
        val monthDir = SimpleDateFormat("yyyy-MM", Locale.getDefault())
            .format(Date(timestamp))
        val fileName = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            .format(Date(timestamp)) + ".jpg"

        val dir = File(baseDir, monthDir).apply { mkdirs() }
        val file = File(dir, fileName)

        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        bitmap.recycle()

        file.absolutePath
    }

    override fun getPhotoCount(): Int =
        baseDir.walkTopDown()
            .filter { it.isFile && it.extension.equals("jpg", ignoreCase = true) }
            .count()

    override fun getPhotoDir(): File = baseDir

    override fun getLatestPhoto(): File? = allPhotosSortedDesc().firstOrNull()

    override fun getAllPhotos(): List<File> = allPhotosSortedDesc()

    private fun allPhotosSortedDesc(): List<File> =
        baseDir.walkTopDown()
            .filter { it.isFile && it.extension.equals("jpg", ignoreCase = true) }
            .sortedByDescending { it.name }
            .toList()
}
