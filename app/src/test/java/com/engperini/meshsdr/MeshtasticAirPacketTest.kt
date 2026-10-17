package com.engperini.meshsdr

import org.junit.Assert.*
import org.junit.Test

class MeshtasticAirPacketTest {
    @Test fun parsesBroadcastFrame() {
        val frame = byteArrayOf(
            -1, -1, -1, -1, // destination broadcast, little-endian
            0x78, 0x56, 0x34, 0x12, // sender
            4, 3, 2, 1, // packet id
            3, 0x42, 0, 0, // flags, channel hash, hop metadata
            0x11, 0x22, 0x33 // encrypted payload
        )
        val packet = MeshtasticAirPacket.parse(frame)
        assertTrue(packet.broadcast)
        assertEquals(0x12345678L, packet.sender)
        assertEquals(0x01020304L, packet.packetId)
        assertEquals(0x42, packet.channelHash)
        assertEquals(3, packet.encryptedPayload.size)
        assertTrue(packet.summary().contains("broadcast"))
        assertTrue(packet.summary().contains("encrypted"))
    }

    @Test fun rejectsTruncatedFrames() {
        for (length in 0..16) {
            try { MeshtasticAirPacket.parse(ByteArray(length)); fail("Accepted $length") }
            catch (_: IllegalArgumentException) {}
        }
    }
}
