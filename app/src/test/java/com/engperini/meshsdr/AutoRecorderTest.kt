package com.engperini.meshsdr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

class AutoRecorderTest {
    private var now = 0L
    private val files = mutableListOf<ByteArrayOutputStream>()
    private val recorder = AutoRecorder(
        open = { name -> ByteArrayOutputStream().also { files += it }.let { AutoRecorder.Output(it, name) {} } },
        log = {}, preBytes = 10, postMillis = 100, maxMillis = 1000, maxFiles = 2, clock = { now }
    )
    private var offset = 0L

    private fun feed(vararg bytes: Int) {
        val chunk = ByteArray(bytes.size) { bytes[it].toByte() }
        recorder.add(chunk, chunk.size, offset) { "f" }
        offset += chunk.size
    }

    @Test fun savesTheSecondsBeforeTheEventAndWhatFollows() {
        feed(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)   // ring keeps the last 10: 2..11
        recorder.trigger()                            // decoder saw a packet in that chunk
        now = 50; feed(12, 13)                        // still within postMillis: recorded
        now = 200; feed(14, 15)                       // 200 ms after the event: recorded, then closed
        now = 300; feed(16, 17)                       // not recorded
        assertEquals(1, files.size)
        assertArrayEquals(ByteArray(14) { (it + 2).toByte() }, files[0].toByteArray())
    }

    @Test fun recordingStartsOnAnIByte() {
        feed(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10)        // 11 bytes: ring holds 1..10, 1 is a Q byte
        recorder.trigger()
        now = 500; feed(11, 12)
        assertArrayEquals(ByteArray(11) { (it + 2).toByte() }, files[0].toByteArray())
    }

    @Test fun stopsAtTheFileLimit() {
        repeat(4) {
            recorder.trigger()
            now += 500; feed(1, 2)
            now += 500; feed(3, 4)
        }
        assertEquals(2, files.size)
    }

    @Test fun recordingRightAfterAnotherStillStartsOnAnIByte() {
        recorder.trigger()
        now = 500; feed(0, 1, 2)                      // starts with an empty ring
        now = 700; feed(3, 4)                         // closes; stream offset is now 5 (odd)
        recorder.trigger()
        now = 710; feed(5, 6, 7)                      // 5 is a Q byte: dropped
        now = 1000; feed(8, 9)
        assertArrayEquals(byteArrayOf(0, 1, 2, 3, 4), files[0].toByteArray())
        assertArrayEquals(byteArrayOf(6, 7, 8, 9), files[1].toByteArray())
    }
}
