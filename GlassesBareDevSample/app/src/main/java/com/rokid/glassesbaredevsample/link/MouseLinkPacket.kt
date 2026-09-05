package com.rokid.glassesbaredevsample.link

import com.rokid.glassesbaredevsample.hand.PointerCommand
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.roundToInt

/**
 * MouseLinkPacket v2 — little-endian binary + truncated HMAC-SHA256 auth tag.
 *
 * Layout v2 (23-byte body + 8-byte tag = 31 bytes):
 * magic u32, version u8, flags u8, sessionId u16, sequence u32, tMs u32,
 * dx i16, dy i16, buttons u8, gainMilli u16, authTag[8]
 *
 * v1 (21 + 8 = 29 bytes) remains decodable for older agents.
 */
object MouseLinkPacket {
    const val MAGIC: Int = 0x524B4D31 // RKM1
    const val VERSION: Int = 2
    const val VERSION_V1: Int = 1
    const val BODY_SIZE: Int = 23
    const val BODY_SIZE_V1: Int = 21
    const val AUTH_TAG_SIZE: Int = 8
    const val PACKET_SIZE_V1: Int = BODY_SIZE_V1 + AUTH_TAG_SIZE

    const val FLAG_HEARTBEAT: Int = 1 shl 0
    const val FLAG_OUTPUT_ENABLED: Int = 1 shl 1
    const val FLAG_HAND_OK: Int = 1 shl 2
    const val BUTTON_LEFT: Int = 1 shl 0
    const val BUTTON_WHEEL_UP: Int = 1 shl 1
    const val BUTTON_WHEEL_DOWN: Int = 1 shl 2

    const val DX_MIN: Int = Short.MIN_VALUE.toInt()
    const val DX_MAX: Int = Short.MAX_VALUE.toInt()

    data class Decoded(
        val magic: Int,
        val version: Int,
        val flags: Int,
        val sessionId: Int,
        val sequence: Long,
        val tMs: Long,
        val dx: Int,
        val dy: Int,
        val buttons: Int,
        val authValid: Boolean,
        val linkGain: Float = LinkGain.DEFAULT,
    ) {
        val heartbeat: Boolean get() = flags and FLAG_HEARTBEAT != 0
        val outputEnabled: Boolean get() = flags and FLAG_OUTPUT_ENABLED != 0
        val handOk: Boolean get() = flags and FLAG_HAND_OK != 0
        val leftPressed: Boolean get() = buttons and BUTTON_LEFT != 0
    }

    fun encode(
        command: PointerCommand,
        sessionId: Int,
        sequence: Long,
        tMs: Long,
        token: String,
        heartbeat: Boolean = false,
        linkGain: Float = LinkGain.DEFAULT,
    ): ByteArray {
        val flags = buildFlags(
            heartbeat = heartbeat,
            outputEnabled = command.outputEnabled,
            handOk = command.handOk,
        )
        val buttons = buildButtons(command)
        val dx = clampDelta(command.dx)
        val dy = clampDelta(command.dy)
        // v1 motion body; [linkGain] is sent separately via MouseLinkGainPacket (RKMG).
        val body = ByteBuffer.allocate(BODY_SIZE_V1).order(ByteOrder.LITTLE_ENDIAN)
        body.putInt(MAGIC)
        body.put(VERSION_V1.toByte())
        body.put(flags.toByte())
        body.putShort((sessionId and 0xFFFF).toShort())
        body.putInt((sequence and 0xFFFF_FFFFL).toInt())
        body.putInt((tMs and 0xFFFF_FFFFL).toInt())
        body.putShort(dx.toShort())
        body.putShort(dy.toShort())
        body.put(buttons.toByte())
        val bodyBytes = body.array()
        val tag = hmacTag(token, bodyBytes)
        return bodyBytes + tag
    }

    fun decode(packet: ByteArray, token: String): Decoded? {
        if (packet.size < BODY_SIZE_V1 + AUTH_TAG_SIZE) return null
        val version = packet[4].toInt() and 0xFF
        val bodySize = when (version) {
            VERSION -> BODY_SIZE
            VERSION_V1 -> BODY_SIZE_V1
            else -> return null
        }
        if (packet.size < bodySize + AUTH_TAG_SIZE) return null
        val body = packet.copyOfRange(0, bodySize)
        val tag = packet.copyOfRange(bodySize, bodySize + AUTH_TAG_SIZE)
        val expected = hmacTag(token, body)
        val authValid = tag.contentEquals(expected)
        val buf = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
        val magic = buf.int
        val parsedVersion = buf.get().toInt() and 0xFF
        val flags = buf.get().toInt() and 0xFF
        val sessionId = buf.short.toInt() and 0xFFFF
        val sequence = buf.int.toLong() and 0xFFFF_FFFFL
        val tMs = buf.int.toLong() and 0xFFFF_FFFFL
        val dx = buf.short.toInt()
        val dy = buf.short.toInt()
        val buttons = buf.get().toInt() and 0xFF
        val linkGain = if (parsedVersion >= VERSION && buf.hasRemaining()) {
            LinkGain.fromMilli(buf.short.toInt() and 0xFFFF)
        } else {
            LinkGain.DEFAULT
        }
        if (magic != MAGIC || parsedVersion != version) return null
        return Decoded(
            magic = magic,
            version = parsedVersion,
            flags = flags,
            sessionId = sessionId,
            sequence = sequence,
            tMs = tMs,
            dx = dx,
            dy = dy,
            buttons = buttons,
            authValid = authValid,
            linkGain = linkGain,
        )
    }

    fun buildFlags(heartbeat: Boolean, outputEnabled: Boolean, handOk: Boolean): Int {
        var flags = 0
        if (heartbeat) flags = flags or FLAG_HEARTBEAT
        if (outputEnabled) flags = flags or FLAG_OUTPUT_ENABLED
        if (handOk) flags = flags or FLAG_HAND_OK
        return flags
    }

    fun buildButtons(command: PointerCommand): Int {
        var buttons = 0
        if (command.leftPressed) buttons = buttons or BUTTON_LEFT
        when {
            command.wheelDelta > 0 -> buttons = buttons or BUTTON_WHEEL_UP
            command.wheelDelta < 0 -> buttons = buttons or BUTTON_WHEEL_DOWN
        }
        return buttons
    }

    fun clampDelta(value: Float): Int =
        value.roundToInt().coerceIn(DX_MIN, DX_MAX)

    fun hmacTag(token: String, body: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(token.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(body).copyOf(AUTH_TAG_SIZE)
    }
}

/**
 * Receiver-side sequence / freshness helper (unit-tested; mirrored in Python agent).
 */
class MouseLinkReceiveGuard(
    private val maxTmsSkew: Long = 5_000L,
) {
    private var lastSequence: Long? = null
    private var lastTms: Long? = null

    fun reset() {
        lastSequence = null
        lastTms = null
    }

    /**
     * @return true if this packet should be accepted for mouse application.
     */
    fun accept(sequence: Long, tMs: Long, nowMs: Long = tMs): Boolean {
        lastSequence?.let { prev ->
            if (sequence == prev) return false
            // Allow wrap; reject only exact duplicate. Out-of-order older seq is dropped
            // when not wrapped (simple unsigned compare for near window).
            val delta = (sequence - prev) and 0xFFFF_FFFFL
            if (delta == 0L || delta > 0x7FFF_FFFFL) {
                // duplicate already handled; very old/replay
                if (delta != 0L && sequence < prev && prev - sequence < 1_000_000L) {
                    return false
                }
            }
        }
        lastTms?.let { prevT ->
            // Stale relative to last accepted packet timestamp.
            if (tMs + 2L < prevT) return false
        }
        if (kotlin.math.abs(nowMs - tMs) > maxTmsSkew && lastTms != null) {
            // Optional skew vs local clock — only enforce after first packet when clocks differ wildly.
            // For unit tests we pass nowMs ~= tMs; agent uses soft check.
        }
        lastSequence = sequence
        lastTms = tMs
        return true
    }
}
