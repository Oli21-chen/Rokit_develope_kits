package com.rokid.glassesbaredevsample.link

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Best-effort UDP sender for chunked FrameLinkPacket v1 JPEG streams.
 */
class UdpFrameLinkClient(
    initialConfig: MouseLinkConfig = MouseLinkConfig(),
) {
    private val sendExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "FrameLinkUdpSend").apply { isDaemon = true }
    }
    private val configRef = AtomicReference(initialConfig)
    private val frameSequence = AtomicLong(0L)
    private val lastErrorRef = AtomicReference<String?>(null)
    private val socketRef = AtomicReference<DatagramSocket?>(null)

    val lastFrameSequence: Long
        get() = frameSequence.get()

    val lastError: String?
        get() = lastErrorRef.get()

    fun updateConfig(config: MouseLinkConfig) {
        configRef.set(config)
        lastErrorRef.set(null)
    }

    fun sendFrame(
        jpeg: ByteArray,
        tMs: Long,
        rotationDeg: Int,
        width: Int,
        height: Int,
        outputEnabled: Boolean,
    ) {
        val config = configRef.get()
        if (!config.enabled || config.host.isBlank() || jpeg.isEmpty()) return
        val frameSeq = frameSequence.incrementAndGet() and 0xFFFF_FFFFL
        val chunks = FrameLinkPacket.encodeChunks(
            jpeg = jpeg,
            sessionId = config.sessionId,
            frameSeq = frameSeq,
            tMs = tMs,
            rotationDeg = rotationDeg,
            width = width,
            height = height,
            token = config.token,
            outputEnabled = outputEnabled,
        )
        sendExecutor.execute {
            try {
                val sock = ensureSocket()
                val address = InetAddress.getByName(config.host)
                for (chunk in chunks) {
                    val packet = DatagramPacket(chunk, chunk.size, address, config.framePort)
                    sock.send(packet)
                }
                lastErrorRef.set(null)
            } catch (error: Exception) {
                val raw = error.message ?: error.javaClass.simpleName
                lastErrorRef.set(MouseLinkErrors.format(raw, config.host, config.framePort))
                Log.e(TAG, "Frame send failed to ${config.host}:${config.framePort}: $raw", error)
            }
        }
    }

    fun close() {
        sendExecutor.execute {
            try {
                socketRef.getAndSet(null)?.close()
            } catch (_: Exception) {
            }
        }
        sendExecutor.shutdown()
    }

    private fun ensureSocket(): DatagramSocket {
        socketRef.get()?.let { return it }
        val sock = DatagramSocket()
        socketRef.set(sock)
        return sock
    }

    companion object {
        private const val TAG = "FrameLink"
    }
}
