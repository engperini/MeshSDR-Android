package com.engperini.meshsdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoraProfileTest {
    @Test fun defaultIsMeshtasticBrazil() {
        assertEquals(LoraProfile.MESHTASTIC_BR, LoraProfile.DEFAULT)
        assertEquals(919_875_000L, LoraProfile.DEFAULT.frequencyHz)
        assertEquals(LoraProfile.DEFAULT, LoraProfile.byId("unknown"))
    }

    @Test fun meshtasticLongFastSlotMatchesFirmwareHash() {
        // Firmware: djb2("LongFast") % numSlots, slot width = 250 kHz.
        var h = 5381L
        for (c in "LongFast") h = ((h shl 5) + h + c.code) and 0xffffffffL
        fun slot(start: Double, end: Double): Long {
            val n = ((end - start) / 0.25).toLong()
            return Math.round((start + 0.125 + (h % n) * 0.25) * 1e6)
        }
        assertEquals(slot(915.0, 928.0), LoraProfile.MESHTASTIC_BR.frequencyHz)
        assertEquals(slot(902.0, 928.0), LoraProfile.MESHTASTIC_US.frequencyHz)
    }

    @Test fun decimationGivesTwoSamplesPerChip() {
        assertEquals(2, LoraProfile.decimation(LoraProfile.MESHTASTIC_BR))
        assertEquals(8, LoraProfile.decimation(LoraProfile.MESHCORE_BR))
    }

    @Test fun describesUndecodedDetections() {
        val header = LoraFrame(ByteArray(0), LoraFrame.HEADER_ONLY, 40, false, -3.5f, 0x12)
        assertTrue(header.describe(), header.describe().contains("LoRa detectado"))
        assertTrue(header.describe().contains("40 bytes"))
        val bad = LoraFrame(ByteArray(10), LoraFrame.CRC_FAIL, 10, false, 1f, 0x2B)
        assertTrue(bad.describe().contains("CRC inválido"))
    }

    @Test fun decodedMeshtasticFrameUsesEnvelope() {
        val bytes = ByteArray(20).also { for (i in 0..3) it[i] = 0xff.toByte() }
        val frame = LoraFrame(bytes, LoraFrame.DECODED, 20, true, 5f, 0x2B)
        assertTrue(frame.describe(), frame.describe().startsWith("Meshtastic"))
        val other = LoraFrame(bytes, LoraFrame.DECODED, 20, true, 5f, 0x34)
        assertTrue(other.describe(), other.describe().contains("0x34 LoRaWAN"))
    }
}
