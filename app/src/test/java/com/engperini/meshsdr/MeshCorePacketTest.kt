package com.engperini.meshsdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MeshCorePacketTest {
    // Assertions below use the Portuguese texts.
    @Before fun portuguese() { Lang.portuguese = true }

    private fun bytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    // Received over the air in Jundiaí on 17/10/2026 (MeshCore BR, CRC-ok).
    private val realResponse = bytes(
        "0640b678c4df75c2098027c5d84cc9de9ff03127198fd268bb848fde560de2f8b9a98bc8045c3255f9f0f929eff740340f392a6e21e2470fa99efa6809881436be0535c9df8f"
    )

    @Test fun parsesRealDirectResponse() {
        assertEquals(70, realResponse.size)
        val p = MeshCorePacket.parse(realResponse)!!
        assertEquals("DIRECT", p.routeName)
        assertEquals("RESPONSE", p.typeName)
        assertEquals(2, p.hashSize)
        assertTrue(p.path.isEmpty())
        // dest(1) + src(1) + MAC(2) + ciphertext: a whole number of AES blocks.
        assertEquals(68, p.payload.size)
        assertEquals(0, (p.payload.size - 4) % 16)
        val s = p.summary()
        assertTrue(s, s.contains("destino b6") && s.contains("origem 78") && s.contains("64 B cifrados"))
    }

    @Test fun parsesRealFloodAnonRequestPath() {
        // Only the first 24 bytes were logged; the rest is padded to the received 59 bytes.
        val prefix = bytes("1d4383b0e28a786a7fd14cdb4dbbdd3acb6b71b093061cb6")
        val frame = prefix + ByteArray(59 - prefix.size)
        val p = MeshCorePacket.parse(frame)!!
        assertEquals("FLOOD", p.routeName)
        assertEquals("ANON_REQ", p.typeName)
        assertEquals(listOf("83b0", "e28a", "786a"), p.path.map { MeshCorePacket.hex(it) })
        // dest(1) + sender pubkey(32) + MAC(2) + one 16-byte AES block.
        assertEquals(51, p.payload.size)
        assertTrue(p.summary(), p.summary().contains("3 saltos via 83b0›e28a›786a") && p.summary().contains("16 B cifrados"))
    }

    @Test fun decodesAdvertNameAndLocation() {
        val key = ByteArray(32) { (it + 1).toByte() }
        val ts = byteArrayOf(0x10, 0x20, 0x30, 0x40)
        val sig = ByteArray(64)
        fun le(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
        val appdata = byteArrayOf((0x80 or 0x10 or 0x02).toByte()) + le(-23186000) + le(-46884000) +
            "Rpt Jundiai".toByteArray()
        val frame = byteArrayOf(0x11, 0x00) + key + ts + sig + appdata   // FLOOD ADVERT, no path
        val p = MeshCorePacket.parse(frame)!!
        assertEquals("ADVERT", p.typeName)
        val a = p.advert()!!
        assertEquals(2, a.nodeType)
        assertEquals("Rpt Jundiai", a.name)
        assertEquals(-23.186, a.latitude!!, 1e-9)
        assertEquals(-46.884, a.longitude!!, 1e-9)
        assertTrue(p.summary(), p.summary().contains("Repetidora \"Rpt Jundiai\" em -23.18600, -46.88400"))
    }

    @Test fun rejectsMalformed() {
        assertNull(MeshCorePacket.parse(byteArrayOf(0x06)))                       // too short
        assertNull(MeshCorePacket.parse(byteArrayOf(0x46, 0x00, 0x01)))           // version != 1
        assertNull(MeshCorePacket.parse(byteArrayOf(0x06, 0xC0.toByte(), 0x01)))  // reserved hash size
        assertNull(MeshCorePacket.parse(byteArrayOf(0x05, 0x05, 0x01, 0x02)))     // path longer than frame
    }

    @Test fun frameUsesActiveProfileAndFlagsUnexpectedSync() {
        // The second real packet was reported with sync 0xD2 although it is MeshCore (0x12).
        val f = LoraFrame(realResponse, LoraFrame.DECODED, 70, true, -12.7f, 0xD2)
        val text = f.describe(LoraProfile.MESHCORE_BR)
        assertTrue(text, text.startsWith("MeshCore RESPONSE"))
        val header = LoraFrame(ByteArray(0), LoraFrame.HEADER_ONLY, 59, false, -12.7f, 0xD2)
        assertTrue(header.describe(LoraProfile.MESHCORE_BR).contains("esperado 0x12"))
        assertNotNull(MeshCorePacket.parse(realResponse))
    }
}
