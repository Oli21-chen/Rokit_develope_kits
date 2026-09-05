package com.rokid.glassesbaredevsample.activities.audio

import android.annotation.SuppressLint
import android.app.Application
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import com.rokid.glassesbaredevsample.app.CONSTANT
import com.rokid.glassesbaredevsample.utils.BarePermissions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AudioViewModel(application: Application) : AndroidViewModel(application) {
    private val _status = MutableStateFlow("未录音")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _lastSavedPath = MutableStateFlow<String?>(null)
    val lastSavedPath: StateFlow<String?> = _lastSavedPath.asStateFlow()

    private val _permissionGranted = MutableStateFlow(false)
    val permissionGranted: StateFlow<Boolean> = _permissionGranted.asStateFlow()

    private var recorder: AudioRecord? = null
    private var thread: Thread? = null
    private var currentOutputFile: File? = null
    @Volatile private var recording = false

    fun refreshPermission() {
        _permissionGranted.value = BarePermissions.hasRecordAudio(getApplication())
        if (!_permissionGranted.value && recording) {
            stopRecording()
        }
        if (!_permissionGranted.value) {
            _status.value = "未授予麦克风权限"
        }
    }

    fun onRecordAudioPermissionResult(granted: Boolean) {
        _permissionGranted.value = granted
        if (!granted) {
            stopRecording()
            _status.value = "未授予麦克风权限"
        } else if (!recording) {
            _status.value = "未录音 · 单击开始/停止"
        }
    }

    fun onSpriteClick() {
        if (!_permissionGranted.value) {
            _status.value = "未授予麦克风权限"
            return
        }
        if (recording) stopRecording() else startRecording()
    }

    fun stopIfRecording() {
        stopRecording()
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        if (recording || !_permissionGranted.value) return
        val rec = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(CONSTANT.AUDIO_SAMPLE_RATE)
                    .setChannelMask(CONSTANT.AUDIO_CHANNEL_MASK)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .build()
        recorder = rec
        rec.startRecording()
        recording = true
        val dir = File(getApplication<Application>().getExternalFilesDir(Environment.DIRECTORY_MUSIC), "pcm")
        dir.mkdirs()
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".pcm"
        val file = File(dir, name)
        currentOutputFile = file
        _status.value = "录音中"
        thread = Thread {
            FileOutputStream(file).use { out ->
                val buf = ByteArray(1024)
                while (recording) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) out.write(buf, 0, n)
                }
            }
        }.also { it.start() }
    }

    private fun stopRecording() {
        if (!recording && recorder == null) return
        val savedFile = currentOutputFile
        recording = false
        thread?.join(500)
        thread = null
        recorder?.stop()
        recorder?.release()
        recorder = null
        currentOutputFile = null
        if (!_permissionGranted.value) return
        if (savedFile != null) {
            _status.value = "已保存"
            _lastSavedPath.value = savedFile.absolutePath
        } else {
            _status.value = "已停止 · 单击开始/停止"
        }
    }

    override fun onCleared() {
        stopRecording()
        super.onCleared()
    }
}
