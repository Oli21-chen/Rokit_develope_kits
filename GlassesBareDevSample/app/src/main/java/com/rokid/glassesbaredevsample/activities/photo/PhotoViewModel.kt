package com.rokid.glassesbaredevsample.activities.photo

import android.app.Application
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.lifecycle.AndroidViewModel
import com.rokid.glassesbaredevsample.utils.BareMediaStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PhotoViewModel(application: Application) : AndroidViewModel(application) {
    private val _status = MutableStateFlow("等待拍照")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _lastSavedPath = MutableStateFlow<String?>(null)
    val lastSavedPath: StateFlow<String?> = _lastSavedPath.asStateFlow()

    @Volatile
    var boundCapture: ImageCapture? = null

    @Volatile
    private var capturing = false

    fun onCaptureStarted() {
        capturing = true
        _status.value = "拍摄中…"
    }

    fun onPhotoSaved(path: String?) {
        capturing = false
        if (path != null) {
            _status.value = "已保存"
            _lastSavedPath.value = path
            Log.i(TAG, "Photo saved: $path")
        } else {
            _status.value = "拍照失败"
        }
    }

    fun nextOutputFile(): File {
        val dir = BareMediaStorage.photoDir(getApplication())
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
        return File(dir, name)
    }

    fun onError(msg: String) {
        capturing = false
        _status.value = msg
        Log.w(TAG, msg)
    }

    fun setReady() {
        if (!capturing) {
            _status.value = "相机就绪 · 单击拍照"
        }
    }

    fun clearCapture() {
        boundCapture = null
        capturing = false
    }

    fun canCapture(): Boolean = !capturing

    companion object {
        private const val TAG = "BarePhoto"
    }
}
