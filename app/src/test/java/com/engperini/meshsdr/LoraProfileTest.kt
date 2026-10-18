package com.engperini.meshsdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LoraProfileTest {
    // Assertions below use the Portuguese texts.
    @Before fun portuguese() { Lang.portuguese = true }

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

    @Test fun frameWithoutCrcIsNotVerified() {
        // Field case: noise produced a header claiming "no CRC"; its bytes cannot be trusted.
        val f = LoraFrame(ByteArray(117), LoraFrame.DECODED, 117, true, -19.7f, 0x59, hasCrc = false)
        assertTrue(!f.verified)
        assertTrue(f.describe(LoraProfile.MESHCORE_BR), f.describe(LoraProfile.MESHCORE_BR).contains("sem CRC"))
        assertTrue(LoraFrame(ByteArray(20), LoraFrame.DECODED, 20, true, 5f, 0x12).verified)
    }

    @Test fun decodedMeshtasticFrameUsesEnvelope() {
        val bytes = ByteArray(20).also { for (i in 0..3) it[i] = 0xff.toByte() }
        val frame = LoraFrame(bytes, LoraFrame.DECODED, 20, true, 5f, 0x2B)
        assertTrue(frame.describe(), frame.describe().startsWith("Meshtastic"))
        val other = LoraFrame(bytes, LoraFrame.DECODED, 20, true, 5f, 0x34)
        assertTrue(other.describe(), other.describe().contains("0x34 LoRaWAN"))
    }

    @Test fun genericProfileRunsEverySpreadingFactorAndShowsRawBytes() {
        val all = LoraProfile.generic(915_000_000)
        assertEquals(listOf(7, 8, 9, 10, 11, 12), all.spreadingFactors)
        assertTrue(all.describe(), all.describe().contains("SF7–12 / BW125 kHz"))
        assertEquals(listOf(9), LoraProfile.generic(915_000_000, 9, 250_000).spreadingFactors)
        for (bw in LoraProfile.GENERIC_BANDWIDTHS) LoraProfile.decimation(LoraProfile.generic(915_000_000, 7, bw))
        assertEquals(LoraProfile.GENERIC_ID, LoraProfile.byId(LoraProfile.GENERIC_ID).id)

        // Bytes that would parse as Meshtastic are not interpreted, and no sync word is "expected".
        val bytes = ByteArray(20).also { for (i in 0..3) it[i] = 0xff.toByte() }
        val text = LoraFrame(bytes, LoraFrame.DECODED, 20, true, 5f, 0x2B, spreadingFactor = 9).describe(all)
        assertTrue(text, text.startsWith("SF9 • Quadro LoRa CRC-ok") && text.contains("ffffffff"))
        val header = LoraFrame(ByteArray(0), LoraFrame.HEADER_ONLY, 30, false, 1f, 0x34, spreadingFactor = 7)
        assertTrue(!header.describe(all).contains("esperado"))
    }

    @Test fun englishTexts() {
        Lang.portuguese = false
        try {
            val header = LoraFrame(ByteArray(0), LoraFrame.HEADER_ONLY, 40, false, -3.5f, 0x12)
            assertTrue(header.describe(), header.describe().startsWith("LoRa detected"))
            assertTrue(LoraProfile.generic(915_000_000).describe().startsWith("Generic LoRa"))
        } finally {
            Lang.portuguese = true
        }
    }
}
