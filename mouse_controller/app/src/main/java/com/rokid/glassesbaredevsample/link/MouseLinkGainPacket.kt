package com.rokid.glassesbaredevsample.link

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Laptop → glasses gain sync (RKMG). Same HMAC scheme as [MouseLinkPacket].
 */
object MouseLinkGainPacket {
    const val MAGIC: Int = 0x524B474D // RKMG
    const val BODY_SIZE: Int = 6
    const val PACKET_SIZE: Int = BODY_SIZE + MouseLinkPacket.AUTH_TAG_SIZE

    fun encode(gain: Float, token: String): ByteArray {
        val body = ByteBuffer.allocate(BODY_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        body.putInt(MAGIC)
        body.putShort(LinkGain.toMilli(gain).toShort())
        val bodyBytes = body.array()
        return bodyBytes + MouseLinkPacket.hmacTag(token, bodyBytes)
    }

    fun decode(packet: ByteArray, token: String): Float? {
        if (packet.size < PACKET_SIZE) return null
        val body = packet.copyOfRange(0, BODY_SIZE)
        val tag = packet.copyOfRange(BODY_SIZE, PACKET_SIZE)
        if (!tag.contentEquals(MouseLinkPacket.hmacTag(token, body))) return null
        val buf = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.int != MAGIC) return null
        return LinkGain.fromMilli(buf.short.toInt() and 0xFFFF)
    }
}
