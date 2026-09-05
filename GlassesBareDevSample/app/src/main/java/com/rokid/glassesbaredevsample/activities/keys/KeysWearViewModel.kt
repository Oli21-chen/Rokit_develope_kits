package com.rokid.glassesbaredevsample.activities.keys

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class KeysWearViewModel(application: Application) : AndroidViewModel(application) {
    private val _logLines = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = _logLines.asStateFlow()

    private val _takeState = MutableStateFlow("-")
    val takeState: StateFlow<String> = _takeState.asStateFlow()

    private val _legState = MutableStateFlow("-")
    val legState: StateFlow<String> = _legState.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WearFoldActions.TAKE_STATUS -> {
                    val v = intent.getStringExtra(WearFoldActions.EXTRA_TAKE) ?: "?"
                    _takeState.value = v
                    append("佩戴: $v")
                }
                WearFoldActions.LEG_STATUS -> {
                    val v = intent.getStringExtra(WearFoldActions.EXTRA_LEG) ?: "?"
                    _legState.value = v
                    append("镜腿: $v")
                }
            }
        }
    }

    fun appendLog(line: String) {
        append(line)
    }

    fun register() {
        val filter = IntentFilter().apply {
            addAction(WearFoldActions.TAKE_STATUS)
            addAction(WearFoldActions.LEG_STATUS)
        }
        ContextCompat.registerReceiver(
            getApplication(),
            receiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED,
        )
        append("Receiver 已注册")
    }

    fun unregister() {
        try {
            getApplication<Application>().unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
    }

    private fun append(line: String) {
        val next = (_logLines.value + line).takeLast(12)
        _logLines.value = next
    }

    override fun onCleared() {
        unregister()
        super.onCleared()
    }
}
