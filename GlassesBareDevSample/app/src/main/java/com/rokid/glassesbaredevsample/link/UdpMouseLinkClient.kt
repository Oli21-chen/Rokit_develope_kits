package com.rokid.glassesbaredevsample.link

import android.util.Log
import com.rokid.glassesbaredevsample.hand.PointerCommand
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Best-effort UDP sender for MouseLinkPacket v1 motion + RKMG gain sync.
 * Gain replies from laptop are read on a dedicated receive thread.
 */
class UdpMouseLinkClient(
    initialConfig: MouseLinkConfig = MouseLinkConfig(),
) : MouseLinkClient {
    private val sendExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "MouseLinkUdpSend").apply { isDaemon = true }
    }
    private val configRef = AtomicReference(initialConfig)
    private val sequence = AtomicLong(0L)
    private val lastErrorRef = AtomicReference<String?>(null)
    private val closed = AtomicBoolean(false)
    private val socketRef = AtomicReference<DatagramSocket?>(null)
    private val receiveStarted = AtomicBoolean(false)
    private var receiveThread: Thread? = null

    override var onRemoteGainChanged: ((Float) -> Unit)? = null

    override val lastSequence: Long
        get() = sequence.get()

    override val lastError: String?
        get() = lastErrorRef.get()

    override fun updateConfig(config: MouseLinkConfig) {
        configRef.set(config)
        lastErrorRef.set(null)
        Log.i(TAG, "Link config → ${config.endpointLabel} session=${config.sessionId} gain=${config.linkGain}")
    }

    override fun send(command: PointerCommand, heartbeat: Boolean) {
        val config = configRef.get()
        if (!config.enabled || config.host.isBlank()) {
            return
        }
        val seq = sequence.incrementAndGet() and 0xFFFF_FFFFL
        val tMs = android.os.SystemClock.elapsedRealtime()
        val packetBytes = try {
            MouseLinkPacket.encode(
                command = command,
                sessionId = config.sessionId,
                sequence = seq,
                tMs = tMs,
                token = config.token,
                heartbeat = heartbeat,
                linkGain = config.linkGain,
            )
        } catch (error: Exception) {
            lastErrorRef.set(error.message ?: error.javaClass.simpleName)
            Log.e(TAG, "Encode failed", error)
            return
        }
        sendExecutor.execute {
            try {
                val sock = ensureSocket()
                val address = InetAddress.getByName(config.host)
                val packet = DatagramPacket(packetBytes, packetBytes.size, address, config.port)
                sock.send(packet)
                lastErrorRef.set(null)
            } catch (error: Exception) {
                val raw = error.message ?: error.javaClass.simpleName
                lastErrorRef.set(MouseLinkErrors.format(raw, config.host, config.port))
                Log.e(TAG, "Send failed to ${config.endpointLabel}: $raw", error)
            }
        }
    }

    override fun sendGainSync(gain: Float) {
        val config = configRef.get()
        if (!config.enabled || config.host.isBlank()) return
        val packetBytes = try {
            MouseLinkGainPacket.encode(LinkGain.clamp(gain), config.token)
        } catch (error: Exception) {
            Log.e(TAG, "Gain encode failed", error)
            return
        }
        sendExecutor.execute {
            try {
                val sock = ensureSocket()
                val address = InetAddress.getByName(config.host)
                val packet = DatagramPacket(packetBytes, packetBytes.size, address, config.port)
                sock.send(packet)
            } catch (error: Exception) {
                Log.e(TAG, "Gain sync send failed", error)
            }
        }
    }

    override fun close() {
        closed.set(true)
        receiveThread?.interrupt()
        receiveThread = null
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
        val sock = DatagramSocket().also { socket ->
            socket.soTimeout = RECEIVE_TIMEOUT_MS
        }
        socketRef.set(sock)
        startReceiveLoop(sock)
        return sock
    }

    private fun startReceiveLoop(sock: DatagramSocket) {
        if (!receiveStarted.compareAndSet(false, true)) return
        receiveThread = Thread({
            val buffer = ByteArray(256)
            while (!closed.get() && !Thread.interrupted()) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    sock.receive(packet)
                    val data = packet.data.copyOfRange(0, packet.length)
                    val config = configRef.get()
                    MouseLinkGainPacket.decode(data, config.token)?.let { gain ->
                        onRemoteGainChanged?.invoke(gain)
                    }
                } catch (_: SocketTimeoutException) {
                    // idle
                } catch (_: InterruptedException) {
                    break
                } catch (error: Exception) {
                    if (!closed.get()) {
                        Log.w(TAG, "Receive loop: ${error.message}")
                    }
                }
            }
        }, "MouseLinkUdpRecv").apply {
            isDaemon = true
            start()
        }
    }

    companion object {
        private const val TAG = "MouseLink"
        private const val RECEIVE_TIMEOUT_MS = 500
    }
}
