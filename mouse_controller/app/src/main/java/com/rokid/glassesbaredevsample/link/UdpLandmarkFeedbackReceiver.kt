package com.rokid.glassesbaredevsample.link

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Receives RKLM landmark feedback from laptop (port 9462) for HUD overlay.
 */
class UdpLandmarkFeedbackReceiver(
    private val port: Int = MouseLinkConfig.DEFAULT_FEEDBACK_PORT,
    private val token: String = MouseLinkConfig.DEFAULT_TOKEN,
    private val onFeedback: (LandmarkFeedbackPacket.Decoded) -> Unit,
) {
    private val closed = AtomicBoolean(false)
    private var thread: Thread? = null
    private var socket: DatagramSocket? = null

    fun start() {
        if (thread != null) return
        closed.set(false)
        thread = Thread(::receiveLoop, "LandmarkFeedbackRx").apply {
            isDaemon = true
            start()
        }
    }

    fun close() {
        closed.set(true)
        socket?.close()
        thread?.interrupt()
        thread = null
        socket = null
    }

    private fun receiveLoop() {
        val buffer = ByteArray(LandmarkFeedbackPacket.PACKET_SIZE)
        try {
            DatagramSocket(port).use { sock ->
                socket = sock
                sock.soTimeout = 500
                Log.i(TAG, "Listening landmark feedback UDP :$port")
                while (!closed.get()) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        sock.receive(packet)
                        val decoded = LandmarkFeedbackPacket.decode(
                            packet.data.copyOfRange(packet.offset, packet.offset + packet.length),
                            token,
                        ) ?: continue
                        if (!decoded.authValid) {
                            Log.w(TAG, "reject bad RKLM HMAC from ${packet.address.hostAddress}")
                            continue
                        }
                        onFeedback(decoded)
                    } catch (_: SocketTimeoutException) {
                        // keep listening
                    } catch (error: Exception) {
                        if (!closed.get()) {
                            Log.w(TAG, "landmark feedback receive failed: ${error.message}")
                        }
                    }
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "landmark feedback bind failed on :$port — ${error.message}")
        } finally {
            socket = null
        }
    }

    companion object {
        private const val TAG = "LandmarkFeedbackRx"
    }
}
