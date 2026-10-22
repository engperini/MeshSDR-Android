package com.engperini.meshsdr

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * The phone side of [RemoteLink]: connects to a MeshSDR receiver (Raspberry Pi
 * or computer) over Wi-Fi or Bluetooth, delivers each received message on its
 * own thread and sends commands in order.
 */
class RemoteClient(
    private val connector: () -> Connection,
    private val onMessage: (Map<String, Any?>) -> Unit,
    private val onEnd: (error: String?) -> Unit
) {
    class Connection(val input: InputStream, val output: OutputStream, val close: () -> Unit)

    private val writer = Executors.newSingleThreadExecutor()
    @Volatile private var connection: Connection? = null
    @Volatile private var closed = false

    fun start() {
        thread(name = "remote-link") {
            var error: String? = null
            try {
                val c = connector()
                connection = c
                if (closed) c.close()
                val input = BufferedInputStream(c.input)
                while (!closed) {
                    val line = RemoteLink.readLine(input) ?: break
                    RemoteLink.parse(line)?.let(onMessage)
                }
            } catch (e: Exception) {
                if (!closed) error = e.message ?: e.javaClass.simpleName
            } finally {
                val wasClosed = closed
                close()
                onEnd(if (wasClosed) null else error ?: tr("connection closed", "conexão encerrada"))
            }
        }
    }

    fun send(line: String) {
        if (closed) return
        writer.execute {
            try {
                connection?.output?.let {
                    it.write((line + "\n").toByteArray(Charsets.UTF_8))
                    it.flush()
                }
            } catch (_: Exception) { close() }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        try { connection?.close?.invoke() } catch (_: Exception) {}
        writer.shutdown()
    }

    companion object {
        /**
         * TCP over Wi-Fi. A Pi hotspot has no internet, so Android may keep mobile
         * data as the default network: bind the socket to the Wi-Fi network itself.
         */
        fun wifi(context: Context, host: String, port: Int = RemoteLink.PORT): () -> Connection = {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            @Suppress("DEPRECATION")
            val wifiNetwork = cm?.allNetworks?.firstOrNull {
                cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
            val socket = wifiNetwork?.socketFactory?.createSocket() ?: Socket()
            socket.connect(InetSocketAddress(host, port), 6000)
            // The receiver sends its state at least every 5 s.
            socket.soTimeout = 20_000
            Connection(socket.getInputStream(), socket.getOutputStream()) { socket.close() }
        }

        /** RFCOMM to the Pi's Bluetooth bridge; the phone must be paired with the Pi. */
        @SuppressLint("MissingPermission")  // MainActivity asks for BLUETOOTH_CONNECT first
        fun bluetooth(context: Context, address: String): () -> Connection = {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                ?: throw IllegalStateException(tr("This phone has no Bluetooth", "Este celular não tem Bluetooth"))
            if (!adapter.isEnabled) throw IllegalStateException(tr("Bluetooth is off", "O Bluetooth está desligado"))
            val socket = adapter.getRemoteDevice(address).createRfcommSocketToServiceRecord(UUID.fromString(RemoteLink.BLUETOOTH_UUID))
            socket.connect()
            Connection(socket.inputStream, socket.outputStream) { socket.close() }
        }
    }
}
