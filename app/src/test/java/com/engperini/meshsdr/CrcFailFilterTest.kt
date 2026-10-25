package com.engperini.meshsdr

import org.junit.Assert.*
import org.junit.Test

class CrcFailFilterTest {
    private var now = 1_000L
    private val out = ArrayList<LoraFrame>()
    private val filter = CrcFailFilter({ out += it }, windowMs = 600, clock = { now })

    private fun frame(status: Int, length: Int, hasCrc: Boolean = true) =
        LoraFrame(ByteArray(length), status, length, status == LoraFrame.DECODED, 5f, 0x12, hasCrc)

    @Test fun badCrcThenDecodedShowsOnlyTheDecoded() {
        filter.accept(frame(LoraFrame.CRC_FAIL, 40))
        now += 5
        val ok = frame(LoraFrame.DECODED, 40)
        filter.accept(ok)
        now += 5_000
        filter.flush()
        assertEquals(listOf(ok), out)
    }

    @Test fun decodedThenBadCrcShowsOnlyTheDecoded() {
        val ok = frame(LoraFrame.DECODED, 40)
        filter.accept(ok)
        now += 100
        filter.accept(frame(LoraFrame.CRC_FAIL, 40))
        now += 5_000
        filter.drain()
        assertEquals(listOf(ok), out)
    }

    @Test fun realFailuresArePassedOnAfterTheWindow() {
        val bad = frame(LoraFrame.CRC_FAIL, 40)
        filter.accept(bad)
        filter.accept(frame(LoraFrame.DECODED, 33))   // another packet: different length
        assertEquals(1, out.size)
        now += 600
        filter.flush()
        assertEquals(1, out.size)                    // still inside the window
        now += 1
        filter.flush()
        assertSame(bad, out.last())
        assertEquals(2, out.size)
    }

    @Test fun oldDecodedDoesNotHideALaterFailure() {
        filter.accept(frame(LoraFrame.DECODED, 40))
        now += 2_000
        val bad = frame(LoraFrame.CRC_FAIL, 40)
        filter.accept(bad)
        filter.drain()
        assertSame(bad, out.last())
    }

    @Test fun otherEventsPassAtOnceAndCloseDrains() {
        val header = frame(LoraFrame.HEADER_ONLY, 0)
        val noCrc = frame(LoraFrame.DECODED, 40, hasCrc = false)
        val bad = frame(LoraFrame.CRC_FAIL, 40)
        filter.accept(bad)
        filter.accept(header)
        filter.accept(noCrc)                         // unverified: does not hide the failure
        assertEquals(listOf(header, noCrc), out)
        filter.drain()
        assertEquals(listOf(header, noCrc, bad), out)
    }
}
