package com.rokid.glassesbaredevsample.activities.video

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import com.rokid.glassesbaredevsample.utils.BareMediaStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VideoViewModel(application: Application) : AndroidViewModel(application) {
    private val _status = MutableStateFlow("未录制")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _lastSavedPath = MutableStateFlow<String?>(null)
    val lastSavedPath: StateFlow<String?> = _lastSavedPath.asStateFlow()

    private var pendingVideoPath: String? = null

    fun setStatus(msg: String) {
        _status.value = msg
    }

    fun onRecordingStart(path: String) {
        pendingVideoPath = path
        _status.value = "录制中"
        Log.i(TAG, "Recording started: $path")
    }

    fun onRecordingFinalize(hasError: Boolean, errorCode: Int = 0, cause: String? = null) {
        if (!hasError) {
            val path = pendingVideoPath
            if (path != null) {
                _status.value = "已保存"
                _lastSavedPath.value = path
                Log.i(TAG, "Video saved: $path")
            } else {
                _status.value = "保存失败(无路径)"
            }
        } else {
            val detail = cause?.takeIf { it.isNotBlank() } ?: "code=$errorCode"
            _status.value = "保存失败($detail)"
            _lastSavedPath.value = null
            Log.e(TAG, "Video finalize error: $detail")
        }
        pendingVideoPath = null
    }

    fun nextOutputFile(): File {
        val dir = BareMediaStorage.videoDir(getApplication())
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        return File(dir, name)
    }

    companion object {
        private const val TAG = "BareVideo"
    }
}
