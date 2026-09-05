package com.rokid.glassesbaredevsample.link

import org.junit.Assert.assertTrue
import org.junit.Test

class MouseLinkErrorsTest {
    @Test
    fun enetunreachExplainsWifiAndHost() {
        val msg = MouseLinkErrors.format("sendto failed: ENETUNREACH (Network is unreachable)", "192.168.0.1", 9460)
        assertTrue(msg.contains("Wi‑Fi"))
        assertTrue(msg.contains(".1"))
    }

    @Test
    fun gatewayHostAddsHint() {
        val msg = MouseLinkErrors.format("timeout", "192.168.0.1", 9460)
        assertTrue(msg.contains("路由器"))
    }
}
