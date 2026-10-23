package com.engperini.meshsdr

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MeshCoreChannelsTest {
    @Before fun portuguese() { Lang.portuguese = true }
    @After fun noHashtags() { MeshCoreChannels.setHashtags("") }

    private fun bytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    // Vectors made with Python's cryptography package following MeshCore's
    // Mesh::createGroupDatagram + Utils::encryptThenMAC (independent of this code).
    private val publicTxt = bytes("1504e28a786a11750d989040c39a2a9f71557121e0b65c57e6bbbcd9bc2eff6732c9a5d8549da65a537de730ff213d4f7949d00662e4569dbf")
    private val testTxt = bytes("1500d901682a0e345aae0a7494390fdba5c56f28397326683a6b0fcb2bd6ba852c3a59984f")

    @Test fun channelHashes() {
        assertEquals(0x11, MeshCoreChannels.PUBLIC.hash)            // "channel 11" in the field logs
        assertEquals(0xd9, MeshCoreChannels.hashtag("#test").hash)  // "channel d9" in the field logs
    }

    @Test fun readsThePublicChannel() {
        val p = MeshCorePacket.parse(publicTxt)!!
        val m = MeshCoreChannels.decrypt(p.payload)!!
        assertEquals("Public", m.channel)
        assertEquals(1792706319L, m.timestamp)
        assertEquals("Arthur: teste do MeshSDR ✓", m.text)
        assertEquals("MeshCore GRP_TXT • FLOOD • 4 saltos via e2›8a›78›6a • canal Public • “Arthur: teste do MeshSDR ✓”", p.summary())
    }

    @Test fun hashtagChannelsOnlyWhenListed() {
        val p = MeshCorePacket.parse(testTxt)!!
        assertNull(MeshCoreChannels.decrypt(p.payload))
        assertTrue(p.summary(), p.summary().endsWith("canal d9 • 32 B cifrados"))
        MeshCoreChannels.setHashtags("test")
        assertEquals("Bob: oi no #test", MeshCoreChannels.decrypt(p.payload)?.text)
        assertEquals("#test", MeshCoreChannels.decrypt(p.payload)?.channel)
    }

    @Test fun rejectsTamperedOrUnknown() {
        val p = MeshCorePacket.parse(publicTxt)!!
        val tampered = p.payload.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }
        assertNull(MeshCoreChannels.decrypt(tampered))
        val otherChannel = p.payload.copyOf().also { it[0] = 0xe0.toByte() }
        assertNull(MeshCoreChannels.decrypt(otherChannel))
        assertNull(MeshCoreChannels.decrypt(ByteArray(10)))
    }

    @Test fun normalizesTheList() {
        assertEquals("#test, #brasil, #sp", MeshCoreChannels.normalize(" test,#brasil ; #sp, #test, #"))
        assertEquals("", MeshCoreChannels.normalize("  "))
    }
}
