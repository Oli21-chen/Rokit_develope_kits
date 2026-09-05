package com.rokid.glassesbaredevsample.link

import android.content.Context

/**
 * Persists laptop UDP endpoint on device (Stage 5a).
 * Stores host/port/token only; session id stays ephemeral per app run.
 */
class MouseLinkStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): MouseLinkConfig {
        val host = prefs.getString(KEY_HOST, MouseLinkConfig.DEFAULT_HOST).orEmpty()
        val port = prefs.getInt(KEY_PORT, MouseLinkConfig.DEFAULT_PORT)
        val token = prefs.getString(KEY_TOKEN, MouseLinkConfig.DEFAULT_TOKEN)
            ?: MouseLinkConfig.DEFAULT_TOKEN
        val gain = prefs.getFloat(KEY_GAIN, LinkGain.DEFAULT)
        return MouseLinkConfig(host = host, port = port, token = token, linkGain = gain)
    }

    fun save(config: MouseLinkConfig) {
        if (config.host.isBlank()) return
        prefs.edit()
            .putString(KEY_HOST, config.host.trim())
            .putInt(KEY_PORT, config.port)
            .putString(KEY_TOKEN, config.token)
            .putFloat(KEY_GAIN, LinkGain.clamp(config.linkGain))
            .apply()
    }

    fun saveLinkGain(gain: Float) {
        prefs.edit()
            .putFloat(KEY_GAIN, LinkGain.clamp(gain))
            .apply()
    }

    fun loadLinkGain(): Float = LinkGain.clamp(prefs.getFloat(KEY_GAIN, LinkGain.DEFAULT))

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "mouse_link_config"
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_TOKEN = "token"
        private const val KEY_GAIN = "link_gain"
    }
}
