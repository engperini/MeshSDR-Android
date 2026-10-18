package com.engperini.meshsdr

/** Pure protocol helpers: testable without Android or a physical dongle. */
object RtlTcpProtocol {
    const val DEFAULT_SAMPLE_RATE = 1_024_000
    fun mhzToHz(input: String): Long {
        val mhz = input.trim().replace(',', '.').toDoubleOrNull()
            ?: throw IllegalArgumentException("Invalid frequency")
        require(mhz.isFinite() && mhz in 24.0..1766.0) { "Frequency out of tuner range" }
        return (mhz * 1_000_000).toLong()
    }

    fun command(id: Int, value: Long): ByteArray {
        require(id in 0..255)
        require(value in 0..0xffffffffL)
        return byteArrayOf(
            id.toByte(),
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte()
        )
    }

    const val SET_FREQUENCY = 0x01
    const val SET_GAIN_MODE = 0x03
    const val SET_GAIN = 0x04
    const val SET_AGC_MODE = 0x08
    const val SET_FREQ_CORRECTION = 0x05

    /** Crystal correction in ppm (signed), for dongles without a TCXO. */
    fun ppmCommand(ppm: Int): ByteArray {
        require(ppm in -200..200) { "ppm out of range" }
        return command(SET_FREQ_CORRECTION, ppm.toLong() and 0xffffffffL)
    }

    /** Tuner gain in tenths of dB, or null for automatic gain. */
    fun gainCommands(tenthsDb: Int?): List<ByteArray> =
        if (tenthsDb == null) listOf(command(SET_GAIN_MODE, 0), command(SET_AGC_MODE, 1))
        else {
            require(tenthsDb in 0..600) { "Gain out of range" }
            listOf(command(SET_GAIN_MODE, 1), command(SET_AGC_MODE, 0), command(SET_GAIN, tenthsDb.toLong()))
        }

    fun tunerName(header: ByteArray): String {
        val type = ((header[4].toInt() and 0xff) shl 24) or ((header[5].toInt() and 0xff) shl 16) or
            ((header[6].toInt() and 0xff) shl 8) or (header[7].toInt() and 0xff)
        return when (type) {
            1 -> "E4000"; 2 -> "FC0012"; 3 -> "FC0013"; 4 -> "FC2580"; 5 -> "R820T/R820T2"; 6 -> "R828D"
            else -> tr("unknown ($type)", "desconhecido ($type)")
        }
    }

    fun isValidHeader(header: ByteArray): Boolean =
        header.size == 12 && header[0] == 'R'.code.toByte() &&
        header[1] == 'T'.code.toByte() && header[2] == 'L'.code.toByte() &&
        header[3] == '0'.code.toByte()
}
