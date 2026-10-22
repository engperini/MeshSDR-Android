package com.engperini.meshsdr

import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class RemoteClientTest {
    private fun tcp(port: Int): () -> RemoteClient.Connection = {
        val socket = Socket()
        socket.connect(InetSocketAddress("127.0.0.1", port), 2000)
        RemoteClient.Connection(socket.getInputStream(), socket.getOutputStream()) { socket.close() }
    }

    @Test fun receivesMessagesSendsCommandsAndReportsTheEnd() {
        val server = ServerSocket(0)
        val commands = LinkedBlockingQueue<String>()
        thread {
            server.accept().use { s ->
                val out = s.getOutputStream()
                out.write((RemoteLink.line("t" to "hello", "host" to "pi") + "\n").toByteArray())
                out.write("not json\n".toByteArray())
                out.write((RemoteState(status = RemoteState.RECEIVING, decoded = 3).toLine() + "\n").toByteArray())
                out.flush()
                commands.put(RemoteLink.readLine(s.getInputStream()) ?: "")
            }
        }
        val messages = LinkedBlockingQueue<Map<String, Any?>>()
        val ended = CountDownLatch(1)
        var endError: String? = "unset"
        val client = RemoteClient(tcp(server.localPort), { messages.put(it) }, { endError = it; ended.countDown() })
        client.start()
        assertEquals("hello", messages.poll(5, TimeUnit.SECONDS)?.str("t"))
        val state = RemoteState.from(messages.poll(5, TimeUnit.SECONDS)!!)
        assertEquals(3, state.decoded)
        client.send(RemoteLink.command("connect"))
        assertEquals("connect", RemoteLink.parse(commands.poll(5, TimeUnit.SECONDS)!!)?.str("op"))
        // The receiver closed the connection: reported as an error so the app retries.
        assertTrue(ended.await(5, TimeUnit.SECONDS))
        assertNotNull(endError)
        server.close()
    }

    @Test fun closingIsNotAnError() {
        val server = ServerSocket(0)
        val accepted = CountDownLatch(1)
        thread { server.accept().use { accepted.countDown(); Thread.sleep(3000) } }
        val ended = CountDownLatch(1)
        var endError: String? = "unset"
        val client = RemoteClient(tcp(server.localPort), {}, { endError = it; ended.countDown() })
        client.start()
        assertTrue(accepted.await(5, TimeUnit.SECONDS))
        Thread.sleep(200)
        client.close()
        assertTrue(ended.await(5, TimeUnit.SECONDS))
        assertNull(endError)
        server.close()
    }

    @Test fun unreachableReceiverEndsWithAnError() {
        val port = ServerSocket(0).use { it.localPort }
        val ended = CountDownLatch(1)
        var endError: String? = null
        RemoteClient(tcp(port), {}, { endError = it; ended.countDown() }).start()
        assertTrue(ended.await(5, TimeUnit.SECONDS))
        assertNotNull(endError)
    }
}
