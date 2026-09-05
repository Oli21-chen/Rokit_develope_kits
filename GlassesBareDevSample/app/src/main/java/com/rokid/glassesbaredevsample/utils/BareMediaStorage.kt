package com.rokid.glassesbaredevsample.utils

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * 媒体落盘目录：优先 `/sdcard`（与 CXRSSDKSamples 一致，便于 adb pull），
 * 不可写时回退到应用专属目录。
 */
object BareMediaStorage {
    fun photoDir(context: Context): File = firstWritableDir(
        File("/sdcard/Pictures/bare_photo"),
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "bare_photo",
        ),
        File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "bare_photo"),
        File(context.filesDir, "bare_photo"),
    )

    fun videoDir(context: Context): File = firstWritableDir(
        File("/sdcard/Video/bare_video"),
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "bare_video",
        ),
        File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "bare_video"),
        File(context.filesDir, "bare_video"),
    )

    private fun firstWritableDir(vararg candidates: File?): File {
        for (raw in candidates) {
            val dir = raw ?: continue
            runCatching {
                if (!dir.exists()) dir.mkdirs()
                if (dir.isDirectory && dir.canWrite()) return dir
            }
        }
        return candidates.filterNotNull().last().also { it.mkdirs() }
    }
}
