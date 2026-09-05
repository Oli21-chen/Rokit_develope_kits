package com.rokid.glassesbaredevsample.link

import com.rokid.glassesbaredevsample.camera.FrameJpegEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameLinkPacketTest {
    private val token = "dev-token"

    @Test
    fun chunkRoundTripReassemblesJpeg() {
        val jpeg = ByteArray(2500) { it.toByte() }
        val chunks = FrameLinkPacket.encodeChunks(
            jpeg = jpeg,
            sessionId = 7,
            frameSeq = 100L,
            tMs = 50_000L,
            rotationDeg = 270,
            width = FrameJpegEncoder.TARGET_WIDTH,
            height = FrameJpegEncoder.TARGET_HEIGHT,
            token = token,
            outputEnabled = true,
        )
        assertTrue(chunks.size >= 2)
        val reassembled = mutableMapOf<Int, ByteArray>()
        var meta: FrameLinkPacket.ChunkMeta? = null
        for (packet in chunks) {
            val decoded = FrameLinkPacket.decode(packet, token)!!
            assertTrue(decoded.authValid)
            meta = decoded.meta
            reassembled[decoded.meta.chunkIndex] = decoded.payload
        }
        assertNotNull(meta)
        assertEquals(7, meta!!.sessionId)
        assertEquals(100L, meta.frameSeq)
        val merged = ByteArray(jpeg.size)
        var offset = 0
        for (i in 0 until meta.chunkTotal) {
            val part = reassembled[i]!!
            part.copyInto(merged, offset)
            offset += part.size
        }
        assertTrue(jpeg.contentEquals(merged))
    }
}
