package com.rokid.glassesbaredevsample.link

import com.rokid.glassesbaredevsample.hand.PointerCommand

interface MouseLinkClient {
    var onRemoteGainChanged: ((Float) -> Unit)?
    fun updateConfig(config: MouseLinkConfig)
    fun send(command: PointerCommand, heartbeat: Boolean = false)
    fun sendGainSync(gain: Float)
    fun close()
    val lastSequence: Long
    val lastError: String?
}
