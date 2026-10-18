package com.engperini.meshsdr

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.view.Gravity
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
    private val sampleRate = LoraProfile.SAMPLE_RATE
    /** LoRa channel frequency; with the DC offset option the tuner sits TUNING_OFFSET_HZ above it. */
    private var centerHz = LoraProfile.DEFAULT.frequencyHz
    @Volatile private var profile = LoraProfile.DEFAULT
    @Volatile private var receiverStale = false
    /** Optional DSP: offset tuning off (no measured benefit), averaging on (measured gain). */
    @Volatile private var offsetTuning = false
    @Volatile private var averaging = true
    private lateinit var profileLabel: TextView
    /** Tuner gain in tenths of dB; AUTO_GAIN means hardware AGC. */
    private var gainTenths = 402
    private var detections = 0
    private var decoded = 0
    private lateinit var gainLabel: TextView
    private lateinit var detectionsValue: TextView
    private lateinit var decodedValue: TextView
    private lateinit var levelValue: TextView
    private lateinit var levelMeter: ProgressBar
    private lateinit var settingsBody: LinearLayout
    private lateinit var settingsSummary: TextView
    private lateinit var settingsArrow: TextView
    private lateinit var outputScroll: ScrollView
    private val profileChips = mutableMapOf<String, Button>()
    private val gainChips = mutableMapOf<Int, Button>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        profile = LoraProfile.byId(getPreferences(MODE_PRIVATE).getString("profile", null))
        centerHz = getPreferences(MODE_PRIVATE).getLong("frequency", profile.frequencyHz)
        gainTenths = getPreferences(MODE_PRIVATE).getInt("gainTenths", gainTenths)
        offsetTuning = getPreferences(MODE_PRIVATE).getBoolean("offsetTuning", false)
        // On by default since 18/10: ~9 dB more sensitivity measured on a real MeshCore packet.
        averaging = getPreferences(MODE_PRIVATE).getBoolean("averaging", true)
        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        // Android 15+ draws behind system bars for target SDK 35.
        // Apply safe-area padding to BOTH status and navigation bars.
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(pad + bars.left, pad / 2 + bars.top, pad + bars.right, pad / 2 + bars.bottom)
                insets
            }
        } else {
            @Suppress("DEPRECATION")
            root.setOnApplyWindowInsetsListener { view, insets ->
                @Suppress("DEPRECATION")
                view.setPadding(pad + insets.systemWindowInsetLeft, pad / 2 + insets.systemWindowInsetTop,
                    pad + insets.systemWindowInsetRight, pad / 2 + insets.systemWindowInsetBottom)
                insets
            }
        }

        // Header: logo, name, connection status.
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.logo)
            clipToOutline = true
            background = rounded(Ui.SURFACE_2, 12f)
            contentDescription = "MeshSDR"
        }
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("MeshSDR", 22f, Ui.TEXT, bold = true))
            addView(label("LoRa • Meshtastic • MeshCore via RTL-SDR", 12f))
        }
        status = statusChip()
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(logo, LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginEnd = dp(12) })
            addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
            addView(status, LinearLayout.LayoutParams(-2, -2))
        }
        root.addView(header)

        // Live numbers.
        val (detTile, detValue) = statTile("Detecções")
        val (decTile, decValue) = statTile("Decodificados")
        detectionsValue = detValue
        decodedValue = decValue
        levelValue = label("—", 13f, Ui.TEXT, bold = true)
        levelMeter = meter()
        val levelTile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Ui.SURFACE, 16f, Ui.OUTLINE, 1f)
            setPadding(dp(12), dp(8), dp(12), dp(10))
            addView(label("Sinal (IQ)", 11f))
            addView(levelValue)
            addView(levelMeter, LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(6) })
        }
        root.addView(row(this, detTile, decTile, levelTile), spaced(12))

        // Settings card, collapsible to give the output more room.
        val settings = card()
        settingsSummary = label("", 12f)
        val settingsHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val texts = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label("Configuração", 15f, Ui.TEXT, bold = true))
                addView(settingsSummary)
            }
            addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            settingsArrow = label("▾", 18f, Ui.ACCENT, bold = true)
            addView(settingsArrow)
            setOnClickListener { setSettingsExpanded(settingsBody.visibility != View.VISIBLE) }
        }
        settings.addView(settingsHeader)
        settingsBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        settings.addView(settingsBody)

        settingsBody.addView(sectionLabel("Perfil LoRa"))
        profileLabel = label("", 12f)
        val profileButtons = listOf("Meshtastic BR" to LoraProfile.MESHTASTIC_BR, "MeshCore BR" to LoraProfile.MESHCORE_BR,
            "Meshtastic US" to LoraProfile.MESHTASTIC_US).map { (label, p) ->
            chip(label) { selectProfile(p) }.also { profileChips[p.id] = it }
        }
        settingsBody.addView(row(this, *profileButtons.toTypedArray(), gapDp = 6))
        settingsBody.addView(profileLabel, spaced(6))

        settingsBody.addView(sectionLabel("Frequência do canal LoRa (MHz)"))
        freqInput = EditText(this).apply {
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText("%.6f".format(Locale.US, centerHz / 1e6))
            setTextColor(Ui.TEXT)
            textSize = 18f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            background = rounded(Ui.SURFACE_2, 14f, Ui.OUTLINE, 1f)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val apply = pill("Aplicar", UiStyle.TONAL) { applyFrequency() }
        settingsBody.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(freqInput, LinearLayout.LayoutParams(0, -2, 1f))
            addView(apply, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        })

        gainLabel = sectionLabel("")
        settingsBody.addView(gainLabel)
        val gainButtons = listOf("Auto" to AUTO_GAIN, "20" to 197, "30" to 297, "40" to 402, "49.6" to 496).map { (label, tenths) ->
            chip(label) { applyGain(tenths) }.also { gainChips[tenths] = it }
        }
        settingsBody.addView(row(this, *gainButtons.toTypedArray(), gapDp = 6))

        settingsBody.addView(sectionLabel("Processamento (opcional)"))
        settingsBody.addView(Switch(this).apply {
            text = "Sintonizar 250 kHz acima (evita pico DC)"
            setTextColor(Ui.TEXT)
            isChecked = offsetTuning
            setOnCheckedChangeListener { _, on ->
                offsetTuning = on
                getPreferences(MODE_PRIVATE).edit().putBoolean("offsetTuning", on).apply()
                applyFrequency()
                updateSettingsSummary()
            }
        }, spaced(4))
        settingsBody.addView(Switch(this).apply {
            text = "Filtro de média na decimação"
            setTextColor(Ui.TEXT)
            isChecked = averaging
            setOnCheckedChangeListener { _, on ->
                averaging = on
                getPreferences(MODE_PRIVATE).edit().putBoolean("averaging", on).apply()
                receiverStale = true
                log("APP", if (on) "Filtro de média ligado" else "Filtro de média desligado")
                updateSettingsSummary()
            }
        }, spaced(8))
        root.addView(settings, spaced(12))
        updateGainLabel()
        updateProfileLabel()

        // Main actions.
        val connect = pill("Conectar", UiStyle.PRIMARY) { launchDriver() }
        val stop = pill("Parar", UiStyle.OUTLINED) { disconnect() }
        val record = pill("● Gravar IQ 10 s", UiStyle.OUTLINED) {
            if (!running) log("IQ", "Conecte o RTL-SDR antes de gravar")
            else {
                captureRequested = true
                log("IQ", "Gravação IQ solicitada: 10 segundos, formato unsigned IQ 8-bit")
            }
        }
        root.addView(row(this, connect, stop, record), spaced(12))

        // Output: segmented tabs + monospace panel.
        messagesTab = chip("Mensagens") { showLogs = false; render() }
        logsTab = chip("Logs técnicos") { showLogs = true; render() }
        root.addView(row(this, messagesTab, logsTab, gapDp = 6), spaced(12))
        outputScroll = ScrollView(this).apply {
            background = rounded(Color.argb(200, 5, 9, 17), 16f, Ui.OUTLINE, 1f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isVerticalScrollBarEnabled = true
        }
        output = TextView(this).apply {
            textSize = 12.5f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(Color.parseColor("#FFCFE3F5"))
            setLineSpacing(0f, 1.15f)
            setTextIsSelectable(true)
        }
        outputScroll.addView(output)
        root.addView(outputScroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })

        val copy = pill("Copiar", UiStyle.TEXT) {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("MeshSDR", output.text))
            Toast.makeText(this, "Copiado", Toast.LENGTH_SHORT).show()
        }
        val save = pill("Salvar logs", UiStyle.TEXT) { saveLogs() }
        val clear = pill("Limpar aba", UiStyle.TEXT) {
            if (showLogs) logs.clear() else messages.clear()
            render()
        }
        root.addView(row(this, copy, save, clear, gapDp = 4), spaced(6))
        setContentView(root)
        updateStatus()
        log("APP", "Perfil LoRa: ${profile.describe()}. Qualquer sync word é aceito e mostrado.")
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
            if (messages.isEmpty()) "Aguardando LoRa: ${profile.describe()}.\nDetecções (cabeçalho válido) também aparecem aqui." else messages.joinToString("\n")
        setChipSelected(logsTab, showLogs)
        setChipSelected(messagesTab, !showLogs)
        messagesTab.text = if (messages.isEmpty()) "Mensagens" else "Mensagens (${messages.size})"
        // Follow new lines only when the reader is already at the bottom.
        val atBottom = outputScroll.scrollY + outputScroll.height >= output.height - dp(48)
        if (atBottom) outputScroll.post { outputScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun sectionLabel(text: String) = label(text, 12f, Ui.MUTED, bold = true).apply {
        setPadding(0, dp(12), 0, dp(6))
    }

    private fun spaced(topDp: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(topDp) }

    private fun setSettingsExpanded(expanded: Boolean) {
        settingsBody.visibility = if (expanded) View.VISIBLE else View.GONE
        settingsArrow.text = if (expanded) "▾" else "▸"
    }

    private fun updateSettingsSummary() {
        val gain = if (gainTenths == AUTO_GAIN) "ganho auto" else "%.1f dB".format(Locale.US, gainTenths / 10.0)
        val extras = listOfNotNull(if (offsetTuning) "deslocado" else null, if (averaging) "filtro" else null)
        settingsSummary.text = "%s • %.3f MHz • %s%s".format(Locale.US, profile.label, centerHz / 1e6, gain,
            if (extras.isEmpty()) "" else " • " + extras.joinToString(" + "))
    }

    /** UI thread: level meter from the last 2 s window. */
    private fun updateLevel(rms: Double, clipPct: Double) {
        // ~32 LSB RMS is a healthy level; clipping turns the bar red, near-silence amber.
        val color = when {
            clipPct > 1.0 -> Ui.RED
            rms < 3.0 -> Ui.AMBER
            else -> Ui.GREEN
        }
        levelValue.text = "%.0f LSB • sat. %.1f%%".format(Locale.US, rms, clipPct)
        levelValue.setTextColor(color)
        setMeter(levelMeter, (rms / 64.0 * 100).toInt(), color)
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
        updateSettingsSummary()
        sendCommands(listOf(RtlTcpProtocol.command(RtlTcpProtocol.SET_FREQUENCY, tunedHz())), "SET_FREQ")
        receiverStale = true
    }

    private fun newReceiver(): LoraReceiver {
        val p = profile.copy(frequencyHz = centerHz)
        val offset = tuningOffsetHz()
        log("LORA", "Decoder: ${p.describe()}; sintonizador em %.3f MHz; deslocamento %s; filtro de média %s".format(
            Locale.US, tunedHz() / 1e6, if (offset != 0) "250 kHz" else "desligado", if (averaging) "ligado" else "desligado"))
        return LoraReceiver(p, offset, averaging) { frame -> ui.post { onFrame(frame) } }
    }

    /** UI thread. */
    private fun onFrame(frame: LoraFrame) {
        if (frame.verified) decoded++ else detections++
        val text = frame.describe(profile)
        messages.addLast("[${timeFormat.format(Date())}] $text")
        while (messages.size > 200) messages.removeFirst()
        log("LORA", text)
        updateStatus()
        if (!showLogs) render()
    }

    private fun updateStatus() {
        if (running) setStatus(status, "Recebendo", Ui.GREEN) else setStatus(status, "Desconectado", Ui.MUTED)
        detectionsValue.text = detections.toString()
        decodedValue.text = decoded.toString()
        if (!running) {
            levelValue.text = "—"
            levelValue.setTextColor(Ui.TEXT)
            setMeter(levelMeter, 0, Ui.ACCENT)
        }
    }

    private fun tuningOffsetHz(): Int = if (offsetTuning) LoraProfile.TUNING_OFFSET_HZ else 0

    private fun tunedHz(): Long = centerHz + tuningOffsetHz()

    private fun selectProfile(p: LoraProfile) {
        profile = p
        getPreferences(MODE_PRIVATE).edit().putString("profile", p.id).apply()
        updateProfileLabel()
        freqInput.setText("%.6f".format(Locale.US, p.frequencyHz / 1e6))
        log("APP", "Perfil LoRa: ${p.describe()}")
        applyFrequency()
        render()
    }

    private fun updateProfileLabel() {
        profileLabel.text = profile.describe()
        profileChips.forEach { (id, chip) -> setChipSelected(chip, id == profile.id) }
        updateSettingsSummary()
    }

    private fun gainOrNull(): Int? = if (gainTenths == AUTO_GAIN) null else gainTenths

    private fun updateGainLabel() {
        gainLabel.text = if (gainTenths == AUTO_GAIN) "Ganho do sintonizador: automático (AGC)"
            else "Ganho do sintonizador: %.1f dB".format(Locale.US, gainTenths / 10.0)
        gainChips.forEach { (tenths, chip) -> setChipSelected(chip, tenths == gainTenths) }
        if (::settingsSummary.isInitialized) updateSettingsSummary()
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
        val uri = Uri.parse("iqsrc://-a 127.0.0.1 -p $port -s $sampleRate -f ${tunedHz()}")
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
        setStatus(status, "Conectando…", Ui.AMBER)
        setSettingsExpanded(false)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            // Optional: without it the service still runs, the notification is just hidden.
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1002)
        }
        try {
            ReceiverService.start(this, "${profile.label} • %.3f MHz".format(Locale.US, centerHz / 1e6))
            log("APP", "Serviço em primeiro plano ativo: recepção continua com a tela apagada")
        } catch (e: Exception) {
            log("ERROR", "Serviço em primeiro plano: ${e.message} (a recepção pode parar com a tela apagada)")
        }
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
                ui.post { updateStatus() }
                receiverStale = false
                var demod = newReceiver()
                val buffer = ByteArray(65536)
                var capture: PublicFiles.Target? = null
                var captureStart = 0L
                var captureBytes = 0L
                var captureName = ""
                var captureFrom = 0
                var bytes = 0L
                // Bytes since the RTL0 header: even = an I byte. Recordings start on one.
                var streamBytes = 0L
                var last = System.currentTimeMillis()
                while (running) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (captureRequested && capture == null) {
                        captureRequested = false
                        val target = PublicFiles.create(this,
                            "meshsdr-${System.currentTimeMillis()}-${tunedHz()}Hz-${sampleRate}sps.cu8", "application/octet-stream")
                        capture = target
                        captureName = target.location
                        captureStart = System.currentTimeMillis()
                        captureBytes = 0L
                        captureFrom = if (streamBytes % 2 == 0L) 0 else 1
                        log("IQ", "Gravando em $captureName")
                    }
                    // Local val: a captured `var` can't be smart-cast to non-null.
                    val activeCapture = capture
                    if (activeCapture != null) {
                        try {
                            if (n > captureFrom) {
                                activeCapture.stream.write(buffer, captureFrom, n - captureFrom)
                                captureBytes += n - captureFrom
                            }
                            captureFrom = 0
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
                    if (receiverStale) {
                        // Profile or frequency changed: restart the decoder with the new settings.
                        receiverStale = false
                        demod.close()
                        demod = newReceiver()
                    }
                    demod.feed(buffer, n)
                    level.add(buffer, n)
                    bytes += n
                    streamBytes += n
                    val now = System.currentTimeMillis()
                    if (now - last >= 2000) {
                        val rate = bytes * 1000.0 / (now - last) / 1e6
                        log("IQ", "%.2f MB/s • %s".format(Locale.US, rate, level.summary()))
                        val rms = level.lastRms
                        val clip = level.lastClipPct
                        ui.post { if (running) updateLevel(rms, clip) }
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
                ReceiverService.stop(this)
                try { socket?.close() } catch (_: Exception) {}
                socket = null
                ui.post { updateStatus() }
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
