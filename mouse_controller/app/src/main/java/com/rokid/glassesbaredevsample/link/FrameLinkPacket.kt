package com.rokid.glassesbaredevsample.link

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * FrameLinkPacket v1 — chunked JPEG camera frames (glasses → laptop).
 *
 * Each UDP datagram carries one chunk:
 * magic u32, version u8, flags u8, sessionId u16, frameSeq u32, tMs u32,
 * rotationDeg u16, width u16, height u16, chunkIndex u16, chunkTotal u16,
 * payloadLen u16, payload[payloadLen], authTag[8]
 */
object FrameLinkPacket {
    const val MAGIC: Int = 0x524B4652 // RKFR
    const val VERSION: Int = 1
    const val HEADER_SIZE: Int = 28
    const val AUTH_TAG_SIZE: Int = 8
    const val MAX_PAYLOAD: Int = 1200

    const val FLAG_OUTPUT_ENABLED: Int = 1 shl 0

    data class ChunkMeta(
        val sessionId: Int,
        val frameSeq: Long,
        val tMs: Long,
        val rotationDeg: Int,
        val width: Int,
        val height: Int,
        val chunkIndex: Int,
        val chunkTotal: Int,
        val outputEnabled: Boolean,
    )

    fun encodeChunks(
        jpeg: ByteArray,
        sessionId: Int,
        frameSeq: Long,
        tMs: Long,
        rotationDeg: Int,
        width: Int,
        height: Int,
        token: String,
        outputEnabled: Boolean,
    ): List<ByteArray> {
        if (jpeg.isEmpty()) return emptyList()
        val chunkTotal = (jpeg.size + MAX_PAYLOAD - 1) / MAX_PAYLOAD
        val flags = if (outputEnabled) FLAG_OUTPUT_ENABLED else 0
        return (0 until chunkTotal).map { index ->
            val offset = index * MAX_PAYLOAD
            val payloadLen = minOf(MAX_PAYLOAD, jpeg.size - offset)
            val payload = jpeg.copyOfRange(offset, offset + payloadLen)
            encodeChunk(
                flags = flags,
                sessionId = sessionId,
                frameSeq = frameSeq,
                tMs = tMs,
                rotationDeg = rotationDeg,
                width = width,
                height = height,
                chunkIndex = index,
                chunkTotal = chunkTotal,
                payload = payload,
                token = token,
            )
        }
    }

    fun encodeChunk(
        flags: Int,
        sessionId: Int,
        frameSeq: Long,
        tMs: Long,
        rotationDeg: Int,
        width: Int,
        height: Int,
        chunkIndex: Int,
        chunkTotal: Int,
        payload: ByteArray,
        token: String,
    ): ByteArray {
        require(payload.size <= MAX_PAYLOAD) { "payload too large: ${payload.size}" }
        require(chunkIndex in 0 until chunkTotal) { "chunkIndex out of range" }
        val body = ByteBuffer.allocate(HEADER_SIZE + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        body.putInt(MAGIC)
        body.put(VERSION.toByte())
        body.put(flags.toByte())
        body.putShort((sessionId and 0xFFFF).toShort())
        body.putInt((frameSeq and 0xFFFF_FFFFL).toInt())
        body.putInt((tMs and 0xFFFF_FFFFL).toInt())
        body.putShort((rotationDeg and 0xFFFF).toShort())
        body.putShort((width and 0xFFFF).toShort())
        body.putShort((height and 0xFFFF).toShort())
        body.putShort((chunkIndex and 0xFFFF).toShort())
        body.putShort((chunkTotal and 0xFFFF).toShort())
        body.putShort((payload.size and 0xFFFF).toShort())
        body.put(payload)
        val bodyBytes = body.array()
        return bodyBytes + MouseLinkPacket.hmacTag(token, bodyBytes)
    }

    data class DecodedChunk(
        val meta: ChunkMeta,
        val payload: ByteArray,
        val authValid: Boolean,
    )

    fun decode(packet: ByteArray, token: String): DecodedChunk? {
        if (packet.size < HEADER_SIZE + AUTH_TAG_SIZE) return null
        val payloadLen = ByteBuffer.wrap(packet, 26, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
        if (packet.size < HEADER_SIZE + payloadLen + AUTH_TAG_SIZE) return null
        val body = packet.copyOfRange(0, HEADER_SIZE + payloadLen)
        val tag = packet.copyOfRange(HEADER_SIZE + payloadLen, HEADER_SIZE + payloadLen + AUTH_TAG_SIZE)
        val authValid = tag.contentEquals(MouseLinkPacket.hmacTag(token, body))
        val buf = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
        val magic = buf.int
        val version = buf.get().toInt() and 0xFF
        val flags = buf.get().toInt() and 0xFF
        if (magic != MAGIC || version != VERSION) return null
        val sessionId = buf.short.toInt() and 0xFFFF
        val frameSeq = buf.int.toLong() and 0xFFFF_FFFFL
        val tMs = buf.int.toLong() and 0xFFFF_FFFFL
        val rotationDeg = buf.short.toInt() and 0xFFFF
        val width = buf.short.toInt() and 0xFFFF
        val height = buf.short.toInt() and 0xFFFF
        val chunkIndex = buf.short.toInt() and 0xFFFF
        val chunkTotal = buf.short.toInt() and 0xFFFF
        val parsedPayloadLen = buf.short.toInt() and 0xFFFF
        if (parsedPayloadLen != payloadLen) return null
        val payload = ByteArray(payloadLen)
        buf.get(payload)
        return DecodedChunk(
            meta = ChunkMeta(
                sessionId = sessionId,
                frameSeq = frameSeq,
                tMs = tMs,
                rotationDeg = rotationDeg,
                width = width,
                height = height,
                chunkIndex = chunkIndex,
                chunkTotal = chunkTotal,
                outputEnabled = flags and FLAG_OUTPUT_ENABLED != 0,
            ),
            payload = payload,
            authValid = authValid,
        )
    }
}
