package com.engperini.meshsdr

import org.junit.Assert.*
import org.junit.Test

class RtlTcpProtocolTest {
    @Test fun acceptsDecimalAndComma() {
        assertEquals(923125000L, RtlTcpProtocol.mhzToHz("923.125"))
        assertEquals(906875000L, RtlTcpProtocol.mhzToHz("906,875"))
    }
    @Test fun rejectsInvalidFrequencies() {
        for (s in listOf("", "NaN", "23", "1800", "not-a-frequency")) {
            try { RtlTcpProtocol.mhzToHz(s); fail("Accepted $s") }
            catch (_: IllegalArgumentException) {}
        }
    }
    @Test fun frequencyCommandUsesBigEndian() {
        assertArrayEquals(
            byteArrayOf(1, 0x37, 0x05, 0x6B, 0xC8.toByte()),
            RtlTcpProtocol.command(1, 923125000L)
        )
    }
    @Test fun validatesHeader() {
        assertTrue(RtlTcpProtocol.isValidHeader("RTL0".toByteArray() + ByteArray(8)))
        assertFalse(RtlTcpProtocol.isValidHeader("BAD!".toByteArray() + ByteArray(8)))
    }
}
