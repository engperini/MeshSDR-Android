package com.engperini.meshsdr.desktop

import com.engperini.meshsdr.LoraProfile
import com.engperini.meshsdr.RemoteLink
import com.engperini.meshsdr.bool
import com.engperini.meshsdr.int
import com.engperini.meshsdr.long
import com.engperini.meshsdr.str
import com.engperini.meshsdr.tr
import java.io.File
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.concurrent.thread

/**
 * Lets a phone be the screen of this receiver ([RemoteLink]): it gets the
 * state, logs and messages, and sends the same commands as the window's
 * buttons. Listens on TCP (Wi-Fi); the Raspberry Pi's Bluetooth bridge
 * connects here too, from 127.0.0.1.
 */
class LinkServer(private val app: AppState, private val port: Int = RemoteLink.PORT) {
    private val history = Any()
    private val logs = ArrayDeque<String>()
    private val messages = ArrayDeque<String>()
    private val clients = CopyOnWriteArrayList<Client>()
    @Volatile private var server: ServerSocket? = null

    /** @throws java.io.IOException if the port is taken. */
    fun start() {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(port))
        server = s
        thread(name = "link-accept", isDaemon = true) { acceptLoop(s) }
        thread(name = "link-state", isDaemon = true) { stateLoop(s) }
    }

    fun stop() {
        try { server?.close() } catch (_: Exception) {}
        server = null
        clients.forEach { it.close() }
    }

    val clientCount: Int get() = clients.size

    fun onLog(line: String) = remember(logs, 400, RemoteLink.line("t" to "log", "line" to line), line)

    fun onMessage(line: String) = remember(messages, 200, RemoteLink.line("t" to "msg", "line" to line), line)

    private fun remember(list: ArrayDeque<String>, max: Int, encoded: String, line: String) {
        synchronized(history) {
            list.addLast(line)
            while (list.size > max) list.removeFirst()
            clients.forEach { it.send(encoded) }
        }
    }

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            val socket = try { s.accept() } catch (_: Exception) { break }
            val client = Client(socket)
            val from = socket.inetAddress.hostAddress
            app.log("LINK", if (socket.inetAddress.isLoopbackAddress)
                tr("Remote screen connected via Bluetooth (bridge)", "Tela remota conectada via Bluetooth (ponte)")
                else tr("Remote screen connected from $from", "Tela remota conectada de $from"))
            client.send(RemoteLink.line("t" to "hello", "app" to "MeshSDR", "proto" to RemoteLink.VERSION,
                "host" to app.hostName))
            client.send(app.remoteState().toLine())
            synchronized(history) {
                logs.forEach { client.send(RemoteLink.line("t" to "log", "line" to it)) }
                messages.forEach { client.send(RemoteLink.line("t" to "msg", "line" to it)) }
                clients.add(client)
            }
            client.start()
        }
    }

    /** Sends the state whenever it changes, and every 5 s so a phone can tell the link is alive. */
    private fun stateLoop(s: ServerSocket) {
        var last = ""
        var sentAt = 0L
        while (!s.isClosed) {
            val line = app.remoteState().toLine()
            val now = System.currentTimeMillis()
            if (line != last || now - sentAt >= 5000) {
                last = line
                sentAt = now
                clients.forEach { it.send(line) }
            }
            Thread.sleep(300)
        }
    }

    private fun handle(client: Client, m: Map<String, Any?>) {
        if (m.str("t") != "cmd") return
        when (val op = m.str("op")) {
            "connect" -> ui { app.connect() }
            "stop" -> ui { app.disconnect() }
            "record" -> ui { app.requestCapture() }
            "profile" -> ui {
                val id = m.str("id")
                if (id == LoraProfile.GENERIC_ID) app.selectGenericProfile() else app.selectProfile(LoraProfile.byId(id))
            }
            "sf" -> m.int("sf")?.let { sf -> ui { app.selectGenericSf(sf) } }
            "freq" -> ui {
                m.str("mhz")?.let { app.freqText = it }
                m.str("bw")?.let { app.bwText = it }
                app.applyFrequency()
            }
            "gain" -> m.int("gain")?.let { g -> if (AppState.GAIN_STEPS.any { it.second == g }) ui { app.applyGain(g) } }
            "ppm" -> m.int("ppm")?.let { v -> ui { app.ppmText = "$v"; app.applyPpm() } }
            "avg" -> m.bool("on")?.let { on -> ui { app.changeAveraging(on) } }
            "autoRec" -> m.bool("on")?.let { on -> ui { app.changeAutoRecord(on) } }
            "lang" -> m.bool("pt")?.let { pt -> ui { app.selectLanguage(pt) } }
            "channels" -> m.str("text")?.let { text -> ui { app.hashtagText = text; app.applyHashtags() } }
            "time" -> m.long("ms")?.let { ms -> app.syncClock(ms, m.str("tz")) }
            "files" -> client.send(filesLine())
            "get" -> sendFile(client, m.str("name") ?: "")
            else -> client.send(RemoteLink.line("t" to "error", "text" to "unknown command $op"))
        }
    }

    private fun filesLine(): String {
        val files = (app.outputDir().listFiles() ?: emptyArray())
            .filter { it.isFile && RemoteLink.isSafeFileName(it.name) }
            .sortedByDescending { it.lastModified() }
            .take(100)
            .map { mapOf("name" to it.name, "size" to it.length(), "time" to it.lastModified()) }
        return RemoteLink.line("t" to "files", "files" to files)
    }

    /** Streams a recording in base64 chunks; a slow link (Bluetooth) just takes longer. */
    private fun sendFile(client: Client, name: String) {
        val file = File(app.outputDir(), name)
        if (!RemoteLink.isSafeFileName(name) || !file.isFile) {
            client.send(RemoteLink.line("t" to "error", "text" to tr("File not found: $name", "Arquivo não encontrado: $name")))
            return
        }
        thread(name = "link-file", isDaemon = true) {
            try {
                file.inputStream().use { input ->
                    val buffer = ByteArray(RemoteLink.CHUNK_BYTES)
                    var offset = 0L
                    val encoder = Base64.getEncoder()
                    while (client.open) {
                        val n = input.readNBytes(buffer, 0, buffer.size)
                        if (n <= 0) break
                        client.sendBlocking(RemoteLink.line("t" to "chunk", "name" to name, "off" to offset,
                            "data" to encoder.encodeToString(buffer.copyOf(n))))
                        offset += n
                    }
                    client.sendBlocking(RemoteLink.line("t" to "chunk", "name" to name, "off" to offset, "data" to "",
                        "done" to true, "size" to offset))
                }
            } catch (e: Exception) {
                client.send(RemoteLink.line("t" to "error", "text" to "$name: ${e.message}"))
            }
        }
    }

    private fun ui(block: () -> Unit) = SwingUtilities.invokeLater(block)


    /** One phone: a writer thread drains its queue so a slow link never blocks the receiver. */
    private inner class Client(private val socket: Socket) {
        private val queue = LinkedBlockingQueue<String>(4000)
        @Volatile var open = true
            private set

        fun start() {
            thread(name = "link-writer", isDaemon = true) { writeLoop() }
            thread(name = "link-reader", isDaemon = true) { readLoop() }
        }

        /** Live updates: a phone that stops reading for minutes is dropped instead of filling memory. */
        fun send(line: String) {
            if (open && !queue.offer(line)) close()
        }

        /** File chunks wait for room, so a download is paced by the link. */
        fun sendBlocking(line: String) {
            while (open && !queue.offer(line, 1, TimeUnit.SECONDS)) { /* wait */ }
        }

        private fun writeLoop() {
            try {
                val out = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8).buffered()
                while (open) {
                    val line = queue.poll(1, TimeUnit.SECONDS) ?: continue
                    out.write(line)
                    out.write("\n")
                    if (queue.isEmpty()) out.flush()
                }
            } catch (_: Exception) {
            } finally { close() }
        }

        private fun readLoop() {
            try {
                val input = socket.getInputStream().buffered()
                while (open) {
                    val line = RemoteLink.readLine(input, 64 * 1024) ?: break
                    RemoteLink.parse(line)?.let { handle(this, it) }
                }
            } catch (_: Exception) {
            } finally { close() }
        }

        fun close() {
            if (!open) return
            open = false
            clients.remove(this)
            try { socket.close() } catch (_: Exception) {}
            app.log("LINK", tr("Remote screen disconnected", "Tela remota desconectada"))
        }
    }
}
