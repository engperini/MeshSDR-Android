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
            byteArrayOf(1, 0x37, 0x05, 0xC5.toByte(), 0x08),
            RtlTcpProtocol.command(1, 923125000L)
        )
    }
    @Test fun validatesHeader() {
        assertTrue(RtlTcpProtocol.isValidHeader("RTL0".toByteArray() + ByteArray(8)))
        assertFalse(RtlTcpProtocol.isValidHeader("BAD!".toByteArray() + ByteArray(8)))
    }
    @Test fun manualGainSetsModeAgcAndValue() {
        val cmds = RtlTcpProtocol.gainCommands(402)
        assertArrayEquals(byteArrayOf(3, 0, 0, 0, 1), cmds[0])
        assertArrayEquals(byteArrayOf(8, 0, 0, 0, 0), cmds[1])
        assertArrayEquals(byteArrayOf(4, 0, 0, 0x01, 0x92.toByte()), cmds[2])
    }
    @Test fun autoGainEnablesAgc() {
        val cmds = RtlTcpProtocol.gainCommands(null)
        assertArrayEquals(byteArrayOf(3, 0, 0, 0, 0), cmds[0])
        assertArrayEquals(byteArrayOf(8, 0, 0, 0, 1), cmds[1])
    }
    @Test fun decodesTunerType() {
        assertEquals("R820T/R820T2", RtlTcpProtocol.tunerName("RTL0".toByteArray() + byteArrayOf(0, 0, 0, 5, 0, 0, 0, 29)))
    }
}
