package com.timelapse.camera.storage

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * DCIM 公共目录存储实现。
 *
 * 写入方式随 Android 版本演进（教学要点：Android 存储权限的历史变迁）：
 * - API 29+（Android 10+）：Scoped Storage 强制执行，必须用 MediaStore API 写入
 * - API 26-28（Android 8-9）：直接 File API + WRITE_EXTERNAL_STORAGE 权限
 *
 * 读取方式：全部用 File API。
 * Android 11+ 允许 App 直接用 File 路径访问自己通过 MediaStore 创建的文件。
 * Android 10 以下本来就支持直接访问 DCIM。
 *
 * 优势：卸载 App 后照片保留，系统相册可见。
 * 限制：App 卸载重装后，File API 可能无法访问旧文件（"创建者"标记丢失），
 * 但文件本身还在 DCIM 中，用户可用系统相册查看。
 */
class DcimPhotoStorage(private val context: Context) : IPhotoStorage {

    companion object {
        private const val SUB_DIR = "TimeLapse"
    }

    /** DCIM/TimeLapse 目录的 File 引用，用于读取操作 */
    private val baseDir = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
        SUB_DIR
    )

    init {
        if (!baseDir.exists()) baseDir.mkdirs()
    }

    override suspend fun save(bitmap: Bitmap, timestamp: Long): String = withContext(Dispatchers.IO) {
        val monthDir = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date(timestamp))
        val fileName = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            .format(Date(timestamp)) + ".jpg"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(bitmap, fileName, monthDir, timestamp)
        } else {
            saveViaFileApi(bitmap, fileName, monthDir)
        }
    }

    /**
     * API 29+：通过 MediaStore 写入 DCIM。
     *
     * 流程：ContentResolver.insert() → 得到 Uri → openOutputStream → compress → 更新 IS_PENDING
     * 无需任何存储权限。
     */
    private fun saveViaMediaStore(
        bitmap: Bitmap, fileName: String, monthDir: String, timestamp: Long
    ): String {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/$SUB_DIR/$monthDir")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val collection = MediaStore.Images.Media.getContentUri(
            MediaStore.VOLUME_EXTERNAL_PRIMARY
        )
        val uri = context.contentResolver.insert(collection, values)
            ?: throw IOException("MediaStore insert 失败")

        context.contentResolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        } ?: throw IOException("打开输出流失败")
        bitmap.recycle()

        // 写入完成，更新 IS_PENDING 为 0，让文件对其他 App 可见
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        context.contentResolver.update(uri, values, null, null)

        // 从 Uri 获取 File 路径（用于返回值和后续 File API 读取）
        return getFilePathFromUri(uri) ?: uri.toString()
    }

    /**
     * API 26-28：直接用 File API 写入 DCIM。
     * 需要 WRITE_EXTERNAL_STORAGE 权限（在 PermissionChecker 中检查）。
     */
    private fun saveViaFileApi(
        bitmap: Bitmap, fileName: String, monthDir: String
    ): String {
        val dir = File(baseDir, monthDir).apply { mkdirs() }
        val file = File(dir, fileName)

        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        bitmap.recycle()

        return file.absolutePath
    }

    /**
     * 从 MediaStore Uri 获取文件路径。
     * DATA 列在 Android 10+ 标记为废弃但仍可返回路径（对自己创建的文件有效）。
     */
    private fun getFilePathFromUri(uri: Uri): String? {
        val projection = arrayOf(MediaStore.Images.Media.DATA)
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getString(0)
            }
        }
        return null
    }

    // ──────────── 读取操作：全部用 File API ────────────
    // Android 11+ 允许 App 用 File 路径访问自己创建的文件。
    // Android 10 以下本来就支持直接访问 DCIM。

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
