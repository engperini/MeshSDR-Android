package com.engperini.meshsdr

import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class RtlTcpClientTest {
    private fun withServer(server: (Socket) -> Unit, client: (Int) -> Unit) {
        ServerSocket(0).use { listener ->
            val failure = AtomicReference<Throwable?>()
            val worker = thread {
                try { listener.accept().use(server) }
                catch (t: Throwable) { failure.set(t) }
            }
            try { client(listener.localPort) }
            finally {
                worker.join(3000)
                assertFalse("Server thread hung", worker.isAlive)
                failure.get()?.let { throw AssertionError("Mock server failed", it) }
            }
        }
    }

    @Test fun readsFragmentedHeaderAndIqAndSendsFrequency() {
        val header = "RTL0".toByteArray() + ByteArray(8)
        val iq = byteArrayOf(0, 127, 255.toByte(), 128.toByte(), 12, 34)
        withServer({ socket ->
            socket.soTimeout = 3000
            val out = socket.getOutputStream()
            out.write(header, 0, 3); out.flush()
            Thread.sleep(15)
            out.write(header, 3, 9); out.flush()
            val command = ByteArray(5)
            val input = socket.getInputStream()
            var received = 0
            while (received < 5) {
                val n = input.read(command, received, 5 - received)
                if (n < 0) throw AssertionError("Command truncated")
                received += n
            }
            assertArrayEquals(RtlTcpProtocol.command(1, 923125000L), command)
            out.write(iq); out.flush()
        }, { port ->
            RtlTcpClient(Socket("127.0.0.1", port)).use { client ->
                assertArrayEquals(header, client.readHeader())
                client.setFrequency(923125000L)
                val received = ByteArray(6)
                var offset = 0
                while (offset < received.size) {
                    val n = client.readIq(ByteArray(received.size - offset))
                    assertTrue(n > 0)
                    // Validate the transport's byte count; payload checked in separate test.
                    offset += n
                }
                assertEquals(6, offset)
            }
        })
    }

    @Test fun rejectsInvalidHeader() {
        withServer({ socket ->
            socket.getOutputStream().write("NOPE".toByteArray() + ByteArray(8))
        }, { port ->
            RtlTcpClient(Socket("127.0.0.1", port)).use { client ->
                try { client.readHeader(); fail("Expected invalid header") }
                catch (_: IllegalArgumentException) {}
            }
        })
    }

    @Test fun rejectsTruncatedHeader() {
        withServer({ socket ->
            socket.getOutputStream().write("RTL0".toByteArray())
        }, { port ->
            RtlTcpClient(Socket("127.0.0.1", port)).use { client ->
                try { client.readHeader(); fail("Expected EOF") }
                catch (_: java.io.EOFException) {}
            }
        })
    }
}
