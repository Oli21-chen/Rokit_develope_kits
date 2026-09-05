package com.rokid.glassesbaredevsample.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LandmarkFeedbackPacketTest {
    @Test
    fun rejectsShortPacket() {
        assertEquals(null, LandmarkFeedbackPacket.decode(ByteArray(32), "dev-token"))
    }

    @Test
    fun buildFlagsIncludesPrecisionMode() {
        val flags = MouseLinkPacket.buildFlags(
            heartbeat = false,
            outputEnabled = true,
            handOk = false,
            precisionMode = true,
        )
        assertTrue(flags and MouseLinkPacket.FLAG_PRECISION_MODE != 0)
    }
}
