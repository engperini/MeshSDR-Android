package com.engperini.meshsdr

import org.junit.Assert.assertTrue
import org.junit.Test

class IqLevelTest {
    @Test fun flagsNearSilentInputAsLowGain() {
        // What the first hardware recordings looked like: only 127/128.
        val level = IqLevel()
        val bytes = ByteArray(10000) { if (it % 3 == 0) 128.toByte() else 127.toByte() }
        level.add(bytes, bytes.size)
        assertTrue(level.summary().contains("aumente o ganho"))
    }

    @Test fun flagsSaturation() {
        val level = IqLevel()
        val bytes = ByteArray(1000) { if (it % 10 == 0) 255.toByte() else 100.toByte() }
        level.add(bytes, bytes.size)
        assertTrue(level.summary().contains("SATURANDO"))
    }

    @Test fun healthyLevelHasNoWarning() {
        val level = IqLevel()
        val bytes = ByteArray(1000) { (127 + ((it % 41) - 20)).toByte() }
        level.add(bytes, bytes.size)
        val text = level.summary()
        assertTrue(text, !text.contains("—"))
    }
}
