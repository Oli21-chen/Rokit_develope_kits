package com.rokid.glassesbaredevsample.link

import android.content.Intent
import kotlin.random.Random

/**
 * Glasses → laptop UDP link settings.
 *
 * Load order: [MouseLinkStore] saved values, then optional Intent extras (dev override).
 * Extras: `link_host` (String), `link_port` (Int), `link_token` (String)
 */
data class MouseLinkConfig(
    val host: String = DEFAULT_HOST,
    val port: Int = DEFAULT_PORT,
    val framePort: Int = DEFAULT_FRAME_PORT,
    val feedbackPort: Int = DEFAULT_FEEDBACK_PORT,
    val token: String = DEFAULT_TOKEN,
    val linkGain: Float = LinkGain.DEFAULT,
    val sessionId: Int = Random.nextInt(0, 0xFFFF + 1),
    val enabled: Boolean = true,
) {
    init {
        require(port in 1..65535) { "port out of range: $port" }
        require(framePort in 1..65535) { "framePort out of range: $framePort" }
        require(feedbackPort in 1..65535) { "feedbackPort out of range: $feedbackPort" }
        require(token.isNotEmpty()) { "token must be non-empty" }
        require(sessionId in 0..0xFFFF) { "sessionId must fit u16" }
        require(linkGain in LinkGain.MIN..LinkGain.MAX) { "linkGain out of range: $linkGain" }
    }

    val endpointLabel: String
        get() = if (host.isBlank()) "(no host)" else "$host:$port"

    companion object {
        const val EXTRA_HOST = "link_host"
        const val EXTRA_PORT = "link_port"
        const val EXTRA_TOKEN = "link_token"

        const val DEFAULT_HOST = ""
        const val DEFAULT_PORT = 9460
        const val DEFAULT_FRAME_PORT = 9461
        const val DEFAULT_FEEDBACK_PORT = 9462
        const val DEFAULT_TOKEN = "dev-token"

        fun fromIntent(intent: Intent?, base: MouseLinkConfig = MouseLinkConfig()): MouseLinkConfig {
            if (intent == null) return base
            val host = intent.getStringExtra(EXTRA_HOST)
            val port = if (intent.hasExtra(EXTRA_PORT)) intent.getIntExtra(EXTRA_PORT, base.port) else null
            val token = intent.getStringExtra(EXTRA_TOKEN)
            return fromExtras(host = host, port = port, token = token, base = base)
        }

        fun fromExtras(
            host: String?,
            port: Int?,
            token: String?,
            base: MouseLinkConfig = MouseLinkConfig(),
        ): MouseLinkConfig {
            return base.copy(
                host = host?.trim()?.takeIf { it.isNotEmpty() } ?: base.host,
                port = port ?: base.port,
                token = token?.takeIf { it.isNotEmpty() } ?: base.token,
            )
        }

        /** Saved config when Intent has no link extras; otherwise Intent fields override saved. */
        fun mergeSavedWithIntent(saved: MouseLinkConfig, intent: Intent?): MouseLinkConfig {
            if (intent == null || !intentHasLinkExtras(intent)) {
                return saved
            }
            return fromIntent(intent, base = saved)
        }

        /** True when [link_host] extra is non-blank (triggers persist after apply). */
        fun shouldPersistFromIntent(intent: Intent?): Boolean {
            return shouldPersistHostExtra(intent?.getStringExtra(EXTRA_HOST))
        }

        fun shouldPersistHostExtra(host: String?): Boolean {
            return !host.isNullOrBlank()
        }

        fun intentHasLinkExtras(intent: Intent?): Boolean {
            if (intent == null) return false
            if (!intent.getStringExtra(EXTRA_HOST).isNullOrBlank()) return true
            if (intent.hasExtra(EXTRA_PORT)) return true
            if (!intent.getStringExtra(EXTRA_TOKEN).isNullOrBlank()) return true
            return false
        }
    }
}
