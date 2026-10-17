package com.engperini.meshsdr

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.view.View
import android.os.Build
import android.view.WindowInsets
import android.widget.*
import java.io.BufferedInputStream
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private companion object { const val AUTO_GAIN = -1 }
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var freqInput: EditText
    private lateinit var status: TextView
    private lateinit var output: TextView
    private lateinit var messagesTab: Button
    private lateinit var logsTab: Button
    private var showLogs = true
    private val logs = ArrayDeque<String>()
    private val messages = ArrayDeque<String>()
    @Volatile private var running = false
    @Volatile private var socket: Socket? = null
    @Volatile private var captureRequested = false
    private val port = 14423
    private val sampleRate = 1000000
    private var centerHz = 906875000L
    /** Tuner gain in tenths of dB; AUTO_GAIN means hardware AGC. */
    private var gainTenths = 402
    private lateinit var gainLabel: TextView
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        centerHz = getPreferences(MODE_PRIVATE).getLong("frequency", centerHz)
        gainTenths = getPreferences(MODE_PRIVATE).getInt("gainTenths", gainTenths)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 18, 18, 18)
        }
        val title = TextView(this).apply { text = "MeshSDR  •  RTL2832U"; textSize = 22f }
        // Android 15+ draws behind system bars for target SDK 35.
        // Apply safe-area padding to BOTH status and navigation bars.
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(18 + bars.left, 18 + bars.top, 18 + bars.right, 18 + bars.bottom)
                insets
            }
        } else {
            @Suppress("DEPRECATION")
            root.setOnApplyWindowInsetsListener { view, insets ->
                @Suppress("DEPRECATION")
                view.setPadding(18 + insets.systemWindowInsetLeft, 18 + insets.systemWindowInsetTop,
                    18 + insets.systemWindowInsetRight, 18 + insets.systemWindowInsetBottom)
                insets
            }
        }
        root.addView(title)
        status = TextView(this).apply { text = "Desconectado"; textSize = 15f; setPadding(0, 12, 0, 12) }
        root.addView(status)
        root.addView(TextView(this).apply { text = "Frequência central (MHz)"; textSize = 16f })
        val freqRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        freqInput = EditText(this).apply {
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText("%.6f".format(Locale.US, centerHz / 1e6))
        }
        freqRow.addView(freqInput, LinearLayout.LayoutParams(0, -2, 1f))
        val apply = Button(this).apply { text = "Aplicar"; setOnClickListener { applyFrequency() } }
        freqRow.addView(apply)
        root.addView(freqRow)
        val presets = LinearLayout(this)
        listOf("906.875", "915.000", "923.125").forEach { value ->
            presets.addView(Button(this).apply {
                text = value
                textSize = 11f
                setOnClickListener { freqInput.setText(value); applyFrequency() }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(presets)
        gainLabel = TextView(this).apply { textSize = 16f; setPadding(0, 8, 0, 0) }
        root.addView(gainLabel)
        val gains = LinearLayout(this)
        listOf("Auto" to AUTO_GAIN, "20" to 197, "30" to 297, "40" to 402, "49.6" to 496).forEach { (label, tenths) ->
            gains.addView(Button(this).apply {
                text = label
                textSize = 11f
                setOnClickListener { applyGain(tenths) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(gains)
        updateGainLabel()
        val controls = LinearLayout(this)
        controls.addView(Button(this).apply {
            text = "Conectar"
            setOnClickListener { launchDriver() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(Button(this).apply {
            text = "Parar"
            setOnClickListener { disconnect() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(controls)
        root.addView(Button(this).apply {
            text = "Gravar IQ (10 segundos)"
            setOnClickListener {
                if (!running) log("IQ", "Conecte o RTL-SDR antes de gravar")
                else {
                    captureRequested = true
                    log("IQ", "Gravação IQ solicitada: 10 segundos, formato unsigned IQ 8-bit")
                }
            }
        })
        val tabs = LinearLayout(this)
        messagesTab = Button(this).apply { text = "Mensagens"; setOnClickListener { showLogs = false; render() } }
        logsTab = Button(this).apply { text = "Logs técnicos"; setOnClickListener { showLogs = true; render() } }
        tabs.addView(messagesTab, LinearLayout.LayoutParams(0, -2, 1f))
        tabs.addView(logsTab, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(tabs)
        val scroll = ScrollView(this)
        output = TextView(this).apply {
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(8, 8, 8, 8)
        }
        scroll.addView(output)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val bottom = LinearLayout(this)
        bottom.addView(Button(this).apply {
            text = "Copiar"
            setOnClickListener {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("MeshSDR", output.text))
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        bottom.addView(Button(this).apply {
            text = "Salvar logs"
            setOnClickListener { saveLogs() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        bottom.addView(Button(this).apply {
            text = "Limpar aba"
            setOnClickListener {
                if (showLogs) logs.clear() else messages.clear()
                render()
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(bottom)
        setContentView(root)
        log("APP", "LoRa PHY habilitado: Meshtastic US LongFast SF11/BW250k. Aguardando pacotes CRC-ok.")
        render()
    }

    private fun saveLogs() {
        val text = (logs + listOf("", "--- Mensagens ---") + messages).joinToString("\n")
        thread(name = "save-logs") {
            try {
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val location = PublicFiles.create(this, "meshsdr-log-$stamp.txt", "text/plain").use { target ->
                    target.stream.write(text.toByteArray(Charsets.UTF_8))
                    target.location
                }
                log("APP", "Logs salvos em $location")
                ui.post { Toast.makeText(this, "Logs salvos em $location", Toast.LENGTH_LONG).show() }
            } catch (e: Exception) { log("ERROR", "Salvar logs: ${e.message}") }
        }
    }

    private fun log(tag: String, detail: String) {
        ui.post {
            logs.addLast("[${timeFormat.format(Date())}] [$tag] $detail")
            while (logs.size > 400) logs.removeFirst()
            if (showLogs) render()
        }
    }

    private fun render() {
        output.text = if (showLogs) logs.joinToString("\n") else
            if (messages.isEmpty()) "Aguardando pacotes LoRa válidos (SF11 / BW250k / sync 0x2B)." else messages.joinToString("\n")
        logsTab.isEnabled = !showLogs
        messagesTab.isEnabled = showLogs
    }

    private fun applyFrequency() {
        val mhz = freqInput.text.toString().replace(',', '.').toDoubleOrNull()
        if (mhz == null || mhz !in 24.0..1766.0) {
            log("UI", "Frequência inválida ou fora da faixa típica R820T: 24–1766 MHz")
            return
        }
        centerHz = (mhz * 1e6).toLong()
        getPreferences(MODE_PRIVATE).edit().putLong("frequency", centerHz).apply()
        log("SDR", "Frequência solicitada: $centerHz Hz")
        sendCommands(listOf(RtlTcpProtocol.command(RtlTcpProtocol.SET_FREQUENCY, centerHz)), "SET_FREQ")
    }

    private fun gainOrNull(): Int? = if (gainTenths == AUTO_GAIN) null else gainTenths

    private fun updateGainLabel() {
        gainLabel.text = if (gainTenths == AUTO_GAIN) "Ganho do sintonizador: automático (AGC)"
            else "Ganho do sintonizador: %.1f dB".format(Locale.US, gainTenths / 10.0)
    }

    private fun applyGain(tenths: Int) {
        gainTenths = tenths
        getPreferences(MODE_PRIVATE).edit().putInt("gainTenths", tenths).apply()
        updateGainLabel()
        sendCommands(RtlTcpProtocol.gainCommands(gainOrNull()), "ganho")
    }

    /** Sends rtl_tcp commands if connected; otherwise they are applied on connect. */
    private fun sendCommands(commands: List<ByteArray>, what: String) {
        val active = socket
        if (active == null || !active.isConnected || active.isClosed) return
        thread {
            try {
                synchronized(active) {
                    val out = active.getOutputStream()
                    commands.forEach { out.write(it) }
                    out.flush()
                }
                log("SDR", "Comando rtl_tcp $what enviado")
            } catch (e: Exception) { log("ERROR", "Comando $what: ${e.message}") }
        }
    }

    private fun launchDriver() {
        if (running) { log("APP", "Recepção já ativa"); return }
        applyFrequency()
        val uri = Uri.parse("iqsrc://-a 127.0.0.1 -p $port -s $sampleRate -f $centerHz")
        try {
            startActivityForResult(Intent(Intent.ACTION_VIEW, uri), 1001)
            log("USB", "Solicitada abertura do driver RTL-SDR")
        } catch (e: ActivityNotFoundException) {
            log("ERROR", "Driver RTL-SDR não encontrado. Instale RTL-SDR Driver (marto.rtl_tcp_andro).")
        }
    }

    @Deprecated("Legacy result for external SDR driver")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 1001) return
        if (resultCode != RESULT_OK) {
            log("USB", "Driver cancelou ou falhou: ${data?.getStringExtra("detailed_exception_message") ?: "sem detalhes"}")
            return
        }
        log("USB", "Driver abriu o dispositivo; conectando ao fluxo IQ")
        connectStream()
    }

    private fun connectStream() {
        if (running) return
        running = true
        status.text = "Conectando…"
        thread(name = "rtl-iq-reader") {
            try {
                val s = Socket("127.0.0.1", port)
                socket = s
                s.soTimeout = 5000
                val input = BufferedInputStream(s.getInputStream())
                val header = ByteArray(12)
                var offset = 0
                while (offset < header.size) {
                    val n = input.read(header, offset, header.size - offset)
                    if (n < 0) throw IllegalStateException("Fim do fluxo no cabeçalho")
                    offset += n
                }
                if (String(header, 0, 4, Charsets.US_ASCII) != "RTL0")
                    throw IllegalStateException("Cabeçalho rtl_tcp inválido")
                log("SDR", "RTL0 recebido; sintonizador ${RtlTcpProtocol.tunerName(header)}; amostras IQ ativas")
                synchronized(s) {
                    val out = s.getOutputStream()
                    RtlTcpProtocol.gainCommands(gainOrNull()).forEach { out.write(it) }
                    out.flush()
                }
                log("SDR", if (gainTenths == AUTO_GAIN) "Ganho automático (AGC) aplicado"
                    else "Ganho manual %.1f dB aplicado".format(Locale.US, gainTenths / 10.0))
                val level = IqLevel()
                ui.post { status.text = "Recebendo IQ • ${centerHz / 1e6} MHz" }
                val demod = LoraReceiver { payload ->
                    try {
                        val packet = MeshtasticAirPacket.parse(payload)
                        ui.post {
                            messages.addLast("[${timeFormat.format(Date())}] ${packet.summary()}")
                            while (messages.size > 200) messages.removeFirst()
                            if (!showLogs) render()
                        }
                        log("LORA", "Pacote Meshtastic CRC-ok: ${payload.size} bytes")
                    } catch (e: IllegalArgumentException) {
                        log("LORA", "Quadro LoRa CRC-ok não reconhecido como Meshtastic: ${payload.size} bytes")
                    }
                }
                val buffer = ByteArray(65536)
                var capture: PublicFiles.Target? = null
                var captureStart = 0L
                var captureBytes = 0L
                var captureName = ""
                var bytes = 0L
                var last = System.currentTimeMillis()
                while (running) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (captureRequested && capture == null) {
                        captureRequested = false
                        val target = PublicFiles.create(this,
                            "meshsdr-${System.currentTimeMillis()}-${centerHz}Hz-${sampleRate}sps.cu8", "application/octet-stream")
                        capture = target
                        captureName = target.location
                        captureStart = System.currentTimeMillis()
                        captureBytes = 0L
                        log("IQ", "Gravando em $captureName")
                    }
                    // Local val: a captured `var` can't be smart-cast to non-null.
                    val activeCapture = capture
                    if (activeCapture != null) {
                        try {
                            activeCapture.stream.write(buffer, 0, n)
                            captureBytes += n
                            if (System.currentTimeMillis() - captureStart >= 10_000L) {
                                activeCapture.close()
                                capture = null
                                log("IQ", "Gravação concluída: $captureBytes bytes em $captureName")
                            }
                        } catch (e: Exception) {
                            log("ERROR", "Gravação IQ: ${e.message}")
                            try { activeCapture.close() } catch (_: Exception) {}
                            capture = null
                        }
                    }
                    demod.feed(buffer, n)
                    level.add(buffer, n)
                    bytes += n
                    val now = System.currentTimeMillis()
                    if (now - last >= 2000) {
                        val rate = bytes * 1000.0 / (now - last) / 1e6
                        log("IQ", "%.2f MB/s • %s".format(Locale.US, rate, level.summary()))
                        bytes = 0
                        last = now
                    }
                }
                demod.close()
                capture?.close()
                if (capture != null) log("IQ", "Gravação parcial: $captureBytes bytes em $captureName")
            } catch (e: Exception) {
                if (running) log("ERROR", "Fluxo IQ: ${e.message}")
            } finally {
                running = false
                try { socket?.close() } catch (_: Exception) {}
                socket = null
                ui.post { status.text = "Desconectado" }
                log("SDR", "Recepção encerrada")
            }
        }
    }

    private fun disconnect() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        log("APP", "Desconexão solicitada")
    }

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }
}
