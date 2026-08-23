package com.timelapse.camera.storage

import android.content.Context
import android.os.Environment
import com.timelapse.camera.config.CaptureConfig
import com.timelapse.camera.config.StorageLocation
import java.io.File

/**
 * 照片存储工厂 —— 根据配置创建对应的 IPhotoStorage 实现。
 *
 * 教学要点：
 * - 工厂模式：调用方只依赖 IPhotoStorage 接口，不关心具体实现
 * - 切换存储位置时只需重新调用 create()，无需改其他代码
 * - SD 卡检测：getExternalFilesDirs() 返回数组，[0] 是内部存储，后续是 SD 卡
 */
object PhotoStorageFactory {

    fun create(context: Context, config: CaptureConfig): IPhotoStorage {
        return when (config.storageLocation) {
            StorageLocation.APP_PRIVATE -> LocalPhotoStorage(context)

            StorageLocation.DCIM -> DcimPhotoStorage(context)

            StorageLocation.SD_CARD -> {
                val sdCardBase = getSdCardBaseDir(context)
                if (sdCardBase != null) {
                    LocalPhotoStorage(context, File(sdCardBase, "TimeLapse"))
                } else {
                    // SD 卡被拔出或不存在，回退到内部存储
                    LocalPhotoStorage(context)
                }
            }
        }
    }

    /**
     * 检测是否有 SD 卡，返回其 App 私有图片目录。
     *
     * getExternalFilesDirs() 返回所有可用存储卷的 App 私有目录：
     * - [0]：内部存储（始终存在）
     * - [1]+：SD 卡或其他可移动存储（可能不存在）
     */
    fun getSdCardBaseDir(context: Context): File? {
        val dirs = context.getExternalFilesDirs(Environment.DIRECTORY_PICTURES)
        return dirs.firstOrNull { it != null && !isInternalStorage(it) }
    }

    /**
     * 判断目录是否在内部存储上。
     * 内部存储路径以 /storage/emulated/0 开头。
     */
    private fun isInternalStorage(dir: File): Boolean {
        val internal = Environment.getExternalStorageDirectory().absolutePath
        return dir.absolutePath.startsWith(internal)
    }
}
