package com.rokid.glassesbaredevsample.link

import com.rokid.glassesbaredevsample.hand.PointerCommand
import com.rokid.glassesbaredevsample.hand.PointerGesture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MouseLinkPacketTest {
    private val token = "dev-token"

    private fun sampleCommand(
        output: Boolean = true,
        handOk: Boolean = true,
        dx: Float = 12.4f,
        dy: Float = -3.6f,
        left: Boolean = true,
    ) = PointerCommand(
        outputEnabled = output,
        handOk = handOk,
        dx = dx,
        dy = dy,
        leftPressed = left,
        gesture = PointerGesture.PINCH,
    )

    @Test
    fun roundTripPreservesFields() {
        val encoded = MouseLinkPacket.encode(
            command = sampleCommand(),
            sessionId = 42,
            sequence = 99L,
            tMs = 1_234_567L,
            token = token,
            heartbeat = true,
        )
        assertEquals(MouseLinkPacket.PACKET_SIZE_V1, encoded.size)
        val decoded = MouseLinkPacket.decode(encoded, token)
        assertNotNull(decoded)
        assertTrue(decoded!!.authValid)
        assertEquals(MouseLinkPacket.MAGIC, decoded.magic)
        assertEquals(MouseLinkPacket.VERSION_V1, decoded.version)
        assertEquals(LinkGain.DEFAULT, decoded.linkGain, 0.001f)
        assertTrue(decoded.heartbeat)
        assertTrue(decoded.outputEnabled)
        assertTrue(decoded.handOk)
        assertEquals(42, decoded.sessionId)
        assertEquals(99L, decoded.sequence)
        assertEquals(1_234_567L, decoded.tMs)
        assertEquals(12, decoded.dx)
        assertEquals(-4, decoded.dy)
        assertTrue(decoded.leftPressed)
    }

    @Test
    fun rejectsWrongTokenHmac() {
        val encoded = MouseLinkPacket.encode(
            command = sampleCommand(left = false),
            sessionId = 1,
            sequence = 1L,
            tMs = 10L,
            token = token,
        )
        val decoded = MouseLinkPacket.decode(encoded, "other-token")
        assertNotNull(decoded)
        assertFalse(decoded!!.authValid)
    }

    @Test
    fun rejectsBadMagic() {
        val encoded = MouseLinkPacket.encode(
            command = sampleCommand(),
            sessionId = 1,
            sequence = 1L,
            tMs = 10L,
            token = token,
        )
        encoded[0] = 0
        assertNull(MouseLinkPacket.decode(encoded, token))
    }

    @Test
    fun encodesWheelButtons() {
        val up = PointerCommand(
            outputEnabled = true,
            handOk = true,
            dx = 0f,
            dy = 0f,
            leftPressed = false,
            gesture = PointerGesture.TRACKING,
            wheelDelta = 1,
        )
        val encodedUp = MouseLinkPacket.encode(up, 1, 1L, 10L, token)
        assertEquals(
            MouseLinkPacket.BUTTON_WHEEL_UP,
            MouseLinkPacket.decode(encodedUp, token)!!.buttons,
        )

        val down = up.copy(wheelDelta = -1)
        val encodedDown = MouseLinkPacket.encode(down, 1, 2L, 11L, token)
        assertEquals(
            MouseLinkPacket.BUTTON_WHEEL_DOWN,
            MouseLinkPacket.decode(encodedDown, token)!!.buttons,
        )
    }

    @Test
    fun v2BodyStillDecodesWhenPresent() {
        val body = java.nio.ByteBuffer.allocate(MouseLinkPacket.BODY_SIZE)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        body.putInt(MouseLinkPacket.MAGIC)
        body.put(MouseLinkPacket.VERSION.toByte())
        body.put(0)
        body.putShort(1)
        body.putInt(2)
        body.putInt(10)
        body.putShort(3)
        body.putShort(-2)
        body.put(0)
        body.putShort(LinkGain.toMilli(0.75f).toShort())
        val bodyBytes = body.array()
        val packet = bodyBytes + MouseLinkPacket.hmacTag(token, bodyBytes)
        val decoded = MouseLinkPacket.decode(packet, token)!!
        assertEquals(MouseLinkPacket.VERSION, decoded.version)
        assertEquals(0.75f, decoded.linkGain, 0.001f)
    }

    @Test
    fun gainSyncPacketRoundTrip() {
        val encoded = MouseLinkGainPacket.encode(0.42f, token)
        assertEquals(MouseLinkGainPacket.PACKET_SIZE, encoded.size)
        assertEquals(0.42f, MouseLinkGainPacket.decode(encoded, token)!!, 0.001f)
    }
}

class MouseLinkReceiveGuardTest {
    @Test
    fun rejectsDuplicateSequence() {
        val guard = MouseLinkReceiveGuard()
        assertTrue(guard.accept(sequence = 1L, tMs = 100L))
        assertFalse(guard.accept(sequence = 1L, tMs = 110L))
        assertTrue(guard.accept(sequence = 2L, tMs = 120L))
    }

    @Test
    fun rejectsStaleOlderTimestamp() {
        val guard = MouseLinkReceiveGuard()
        assertTrue(guard.accept(sequence = 5L, tMs = 500L))
        assertFalse(guard.accept(sequence = 6L, tMs = 490L))
    }
}

class MouseLinkConfigTest {
    @Test
    fun fromExtrasOverridesHostPortToken() {
        val config = MouseLinkConfig.fromExtras(
            host = "192.168.1.20",
            port = 9460,
            token = "secret",
        )
        assertEquals("192.168.1.20", config.host)
        assertEquals(9460, config.port)
        assertEquals("secret", config.token)
    }

    @Test
    fun mergeExtrasOverrideSavedHost() {
        val saved = MouseLinkConfig(host = "192.168.0.100", port = 9460, token = "dev-token")
        val merged = MouseLinkConfig.fromExtras(
            host = "192.168.0.200",
            port = null,
            token = null,
            base = saved,
        )
        assertEquals("192.168.0.200", merged.host)
        assertEquals(9460, merged.port)
    }

    @Test
    fun shouldPersistHostExtra() {
        assertFalse(MouseLinkConfig.shouldPersistHostExtra(null))
        assertFalse(MouseLinkConfig.shouldPersistHostExtra(""))
        assertTrue(MouseLinkConfig.shouldPersistHostExtra("192.168.0.100"))
    }
}
