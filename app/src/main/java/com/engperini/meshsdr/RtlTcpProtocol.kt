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

    fun isValidHeader(header: ByteArray): Boolean =
        header.size == 12 && header[0] == 'R'.code.toByte() &&
        header[1] == 'T'.code.toByte() && header[2] == 'L'.code.toByte() &&
        header[3] == '0'.code.toByte()
}
