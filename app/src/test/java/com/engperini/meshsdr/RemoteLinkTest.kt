package com.engperini.meshsdr

import org.junit.Assert.*
import org.junit.Test

class RemoteLinkTest {
    @Test fun jsonRoundTrip() {
        val value = mapOf(
            "s" to "a \"quoted\" \\ line\nwith tab\t and ção ✓", "n" to 923125000L, "neg" to -17L,
            "d" to 20.5, "b" to true, "z" to null, "list" to listOf(1L, "x", false), "obj" to mapOf("k" to "v")
        )
        assertEquals(value, Json.parse(Json.encode(value)))
    }

    @Test fun jsonParsesWhitespaceAndEscapes() {
        val m = Json.parse(" { \"a\" : [ 1 , 2.5e1 ] , \"u\" : \"\\u00e7\\/\" } ") as Map<*, *>
        assertEquals(listOf(1L, 25.0), m["a"])
        assertEquals("ç/", m["u"])
    }

    @Test fun jsonRejectsMalformedInput() {
        for (bad in listOf("", "{", "{\"a\":}", "[1,]", "{\"a\":1}x", "\"open", "nul", "{a:1}")) {
            try { Json.parse(bad); fail("accepted $bad") } catch (_: IllegalArgumentException) {}
        }
        assertNull(RemoteLink.parse("not json"))
        assertNull(RemoteLink.parse("[1,2]"))
    }

    @Test fun encodedLinesHaveNoRawNewlines() {
        val line = RemoteLink.line("t" to "log", "line" to "one\ntwo\r\n")
        assertFalse(line.contains('\n') || line.contains('\r'))
    }

    @Test fun nonFiniteNumbersBecomeNull() {
        assertEquals("{\"x\":null}", Json.encode(mapOf("x" to Double.NaN)))
    }

    @Test fun commandCarriesOpAndFields() {
        val m = RemoteLink.parse(RemoteLink.command("freq", "mhz" to "923.125", "bw" to "62.5"))!!
        assertEquals("cmd", m.str("t"))
        assertEquals("freq", m.str("op"))
        assertEquals("923.125", m.str("mhz"))
    }

    @Test fun stateRoundTrip() {
        val state = RemoteState(
            status = RemoteState.RECEIVING, profileId = LoraProfile.MESHCORE_BR.id, frequencyHz = 923_125_000,
            genericSf = 9, genericBw = 62_500, gainTenths = 402, ppm = -17, averaging = false, autoRecord = true,
            detections = 6, decoded = 2, levelRms = 17.2, levelClipPct = 0.03
        )
        assertEquals(state, RemoteState.from(RemoteLink.parse(state.toLine())!!))
        assertEquals(state.copy(levelRms = null), RemoteState.from(RemoteLink.parse(state.copy(levelRms = null).toLine())!!))
    }

    @Test fun stateProfileFollowsGenericSettings() {
        val generic = RemoteState(profileId = LoraProfile.GENERIC_ID, frequencyHz = 868_100_000, genericSf = 7, genericBw = 250_000)
        val p = generic.profile()
        assertEquals(LoraProfile.GENERIC_ID, p.id)
        assertEquals(868_100_000L, p.frequencyHz)
        assertEquals(7, p.spreadingFactor)
        assertEquals(250_000, p.bandwidthHz)
        assertEquals(923_125_000L, RemoteState(profileId = LoraProfile.MESHCORE_BR.id, frequencyHz = 923_125_000).profile().frequencyHz)
    }

    @Test fun readsLinesWithLimit() {
        val input = "one\r\ntwo ✓\nlast".toByteArray(Charsets.UTF_8).inputStream()
        assertEquals("one", RemoteLink.readLine(input))
        assertEquals("two ✓", RemoteLink.readLine(input))
        assertEquals("last", RemoteLink.readLine(input))
        assertNull(RemoteLink.readLine(input))
        try { RemoteLink.readLine("x".repeat(100).toByteArray().inputStream(), max = 10); fail() } catch (_: java.io.IOException) {}
    }

    @Test fun onlyPlainFileNamesAreSafe() {
        assertTrue(RemoteLink.isSafeFileName("meshsdr-auto-1761120000000-923125000Hz-1000000sps.cu8"))
        for (bad in listOf("", "../etc/passwd", "a/b.cu8", "a\\b", ".hidden", "x\ny")) assertFalse(bad, RemoteLink.isSafeFileName(bad))
    }
}
