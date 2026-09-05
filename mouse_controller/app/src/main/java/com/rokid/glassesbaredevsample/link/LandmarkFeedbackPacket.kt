package com.rokid.glassesbaredevsample.link

import com.rokid.glassesbaredevsample.hand.HandLandmark
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * LandmarkFeedbackPacket v1 — laptop → glasses HUD overlay (RKLM).
 *
 * Layout: magic u32, version u8, flags u8, sessionId u16, sequence u32, tMs u32,
 * 21 × (x f32, y f32), authTag[8]
 */
object LandmarkFeedbackPacket {
    const val MAGIC: Int = 0x524B4C4D // RKLM
    const val VERSION: Int = 1
    const val HEADER_SIZE: Int = 16
    const val LANDMARK_COUNT: Int = 21
    const val AUTH_TAG_SIZE: Int = 8
    const val PACKET_SIZE: Int = HEADER_SIZE + LANDMARK_COUNT * 8 + AUTH_TAG_SIZE

    const val FLAG_HAND_PRESENT: Int = 1 shl 0
    const val FLAG_PRECISION_ACTIVE: Int = 1 shl 1

    data class Decoded(
        val sessionId: Int,
        val sequence: Long,
        val tMs: Long,
        val handPresent: Boolean,
        val precisionActive: Boolean,
        val landmarks: List<HandLandmark>,
        val authValid: Boolean,
    )

    fun decode(packet: ByteArray, token: String): Decoded? {
        if (packet.size < PACKET_SIZE) return null
        val body = packet.copyOfRange(0, HEADER_SIZE + LANDMARK_COUNT * 8)
        val tag = packet.copyOfRange(HEADER_SIZE + LANDMARK_COUNT * 8, PACKET_SIZE)
        val authValid = tag.contentEquals(hmacTag(token, body))
        val buf = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
        val magic = buf.int
        val version = buf.get().toInt() and 0xFF
        val flags = buf.get().toInt() and 0xFF
        if (magic != MAGIC || version != VERSION) return null
        val sessionId = buf.short.toInt() and 0xFFFF
        val sequence = buf.int.toLong() and 0xFFFF_FFFFL
        val tMs = buf.int.toLong() and 0xFFFF_FFFFL
        val landmarks = buildList(LANDMARK_COUNT) {
            repeat(LANDMARK_COUNT) {
                add(HandLandmark(buf.float, buf.float, 0f))
            }
        }
        return Decoded(
            sessionId = sessionId,
            sequence = sequence,
            tMs = tMs,
            handPresent = flags and FLAG_HAND_PRESENT != 0,
            precisionActive = flags and FLAG_PRECISION_ACTIVE != 0,
            landmarks = landmarks,
            authValid = authValid,
        )
    }

    private fun hmacTag(token: String, body: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(token.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(body).copyOf(AUTH_TAG_SIZE)
    }
}
