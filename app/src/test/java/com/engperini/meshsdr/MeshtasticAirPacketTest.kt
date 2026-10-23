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
        Lang.portuguese = false
        assertTrue(packet.summary().contains("broadcast"))
        assertTrue(packet.summary().contains("encrypted"))
    }

    @Test fun describesHopsChannelAndFlags() {
        // Flags 0x6a: hop limit 2, wants ack, hop start 3 -> one hop taken; LongFast default channel; relay byte 0x9c.
        val frame = byteArrayOf(0x44, 0x33, 0x22, 0x11, 0x78, 0x56, 0x34, 0x12, 4, 3, 2, 1, 0x6a, 0x08, 0, 0x9c.toByte(), 1, 2)
        val packet = MeshtasticAirPacket.parse(frame)
        assertEquals(2, packet.hopLimit)
        assertEquals(3, packet.hopStart)
        assertTrue(packet.wantAck)
        Lang.portuguese = false
        assertEquals("Meshtastic • to !11223344 • from !12345678 • id 01020304 • channel 08 (public LongFast) • " +
            "1 of 3 hops • relayed by ..9c • wants ack • 2 B encrypted", packet.summary())
        Lang.portuguese = true
        assertEquals("Meshtastic • para !11223344 • de !12345678 • id 01020304 • canal 08 (LongFast público) • " +
            "1 de 3 saltos • retransmitido por ..9c • pede confirmação • 2 B cifrados", packet.summary())
    }

    @Test fun oldFirmwareShowsTheHopLimit() {
        val frame = ByteArray(20).also { it[12] = 0x03; it[13] = 0x5a }
        Lang.portuguese = false
        val text = MeshtasticAirPacket.parse(frame).summary()
        assertTrue(text, text.contains("hop limit 3") && text.contains("channel 5a") && !text.contains("public"))
    }

    @Test fun rejectsTruncatedFrames() {
        for (length in 0..16) {
            try { MeshtasticAirPacket.parse(ByteArray(length)); fail("Accepted $length") }
            catch (_: IllegalArgumentException) {}
        }
    }
}
