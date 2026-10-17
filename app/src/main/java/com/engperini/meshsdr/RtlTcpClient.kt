package com.engperini.meshsdr

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/** Transport independent of Android. Allows fake rtl_tcp servers in JVM tests. */
class RtlTcpClient(private val socket: Socket) : AutoCloseable {
    private val input: InputStream = socket.getInputStream()
    private val output: OutputStream = socket.getOutputStream()

    fun readHeader(): ByteArray {
        val header = ByteArray(12)
        var offset = 0
        while (offset < header.size) {
            val count = input.read(header, offset, header.size - offset)
            if (count < 0) throw EOFException("rtl_tcp header truncated at $offset bytes")
            if (count == 0) continue
            offset += count
        }
        require(RtlTcpProtocol.isValidHeader(header)) { "Invalid rtl_tcp header" }
        return header
    }

    fun setFrequency(hz: Long) {
        output.write(RtlTcpProtocol.command(0x01, hz))
        output.flush()
    }

    fun readIq(buffer: ByteArray): Int {
        require(buffer.isNotEmpty())
        return input.read(buffer)
    }

    override fun close() = socket.close()
}
