package com.engperini.meshsdr

import java.util.Locale

/** Signal level of rtl_tcp unsigned 8-bit IQ, to tell whether the gain is usable. */
class IqLevel {
    private var sumSquares = 0.0
    private var clipped = 0L
    private var count = 0L

    /** Values from the last [summary] window, for the UI meter. */
    var lastRms = 0.0
        private set
    var lastClipPct = 0.0
        private set

    fun add(bytes: ByteArray, length: Int) {
        for (i in 0 until length) {
            val v = bytes[i].toInt() and 0xff
            val d = v - 127.5
            sumSquares += d * d
            if (v == 0 || v == 255) clipped++
        }
        count += length
    }

    /** RMS in ADC steps (LSB) and percentage of saturated samples; resets the window. */
    fun summary(): String {
        if (count == 0L) return tr("no samples", "sem amostras")
        val rms = Math.sqrt(sumSquares / count)
        val clipPct = 100.0 * clipped / count
        lastRms = rms
        lastClipPct = clipPct
        sumSquares = 0.0; clipped = 0; count = 0
        val hint = when {
            clipPct > 1.0 -> tr(" — SATURATING: lower the gain", " — SATURANDO: reduza o ganho")
            rms < 3.0 -> tr(" — near-silent signal: raise the gain / check the antenna",
                " — sinal quase nulo: aumente o ganho / verifique a antena")
            else -> ""
        }
        return String.format(Locale.US, tr("level %.1f LSB RMS, clipping %.2f%%%s", "nível %.1f LSB RMS, saturação %.2f%%%s"),
            rms, clipPct, hint)
    }
}
