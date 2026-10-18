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
    private lateinit var settingsScroll: ScrollView
    private lateinit var settingsSummary: TextView
    private lateinit var settingsArrow: TextView
    private lateinit var outputScroll: ScrollView
    private val profileChips = mutableMapOf<String, Button>()
    private val gainChips = mutableMapOf<Int, Button>()
    private val sfChips = mutableMapOf<Int, Button>()
    private val languageChips = mutableMapOf<Boolean, Button>()
    private lateinit var genericBox: LinearLayout
    private lateinit var bwInput: EditText
    private lateinit var channelLabel: TextView
    /** Generic LoRa settings, kept while another profile is selected. */
    private var genericSf = LoraProfile.ALL_SF
    private var genericBw = 125_000
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Lang.apply(getPreferences(MODE_PRIVATE).getString("language", null))
        genericSf = getPreferences(MODE_PRIVATE).getInt("genericSf", genericSf)
        genericBw = getPreferences(MODE_PRIVATE).getInt("genericBw", genericBw)
            .takeIf { it in LoraProfile.GENERIC_BANDWIDTHS } ?: 125_000
        profile = LoraProfile.byId(getPreferences(MODE_PRIVATE).getString("profile", null))
        centerHz = getPreferences(MODE_PRIVATE).getLong("frequency", profile.frequencyHz)
        if (profile.protocol == Protocol.GENERIC) profile = LoraProfile.generic(centerHz, genericSf, genericBw)
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
        val (detTile, detValue) = statTile(tr("Detections", "Detecções"))
        val (decTile, decValue) = statTile(tr("Decoded", "Decodificados"))
        detectionsValue = detValue
        decodedValue = decValue
        levelValue = label("—", 13f, Ui.TEXT, bold = true)
        levelMeter = meter()
        val levelTile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Ui.SURFACE, 16f, Ui.OUTLINE, 1f)
            setPadding(dp(12), dp(8), dp(12), dp(10))
            addView(label(tr("Signal (IQ)", "Sinal (IQ)"), 11f))
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
                addView(label(tr("Settings", "Configuração"), 15f, Ui.TEXT, bold = true))
                addView(settingsSummary)
            }
            addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            settingsArrow = label("▾", 18f, Ui.ACCENT, bold = true)
            addView(settingsArrow)
            setOnClickListener { setSettingsExpanded(settingsScroll.visibility != View.VISIBLE) }
        }
        settings.addView(settingsHeader)
        settingsBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // Scrolls inside the card so the buttons and output stay on screen when expanded.
        settingsScroll = MaxHeightScrollView(this).apply {
            maxHeightPx = (resources.displayMetrics.heightPixels * 0.42).toInt()
            isVerticalScrollBarEnabled = true
            addView(settingsBody)
        }
        settings.addView(settingsScroll)

        settingsBody.addView(sectionLabel(tr("LoRa profile", "Perfil LoRa")))
        profileLabel = label("", 12f)
        val profileButtons = listOf("Meshtastic BR" to LoraProfile.MESHTASTIC_BR, "MeshCore BR" to LoraProfile.MESHCORE_BR,
            "Meshtastic US" to LoraProfile.MESHTASTIC_US).map { (label, p) ->
            chip(label) { selectProfile(p) }.also { profileChips[p.id] = it }
        }
        val genericChip = chip(tr("Generic LoRa", "LoRa genérico")) {
            selectProfile(LoraProfile.generic(centerHz, genericSf, genericBw))
        }.also { profileChips[LoraProfile.GENERIC_ID] = it }
        settingsBody.addView(row(this, profileButtons[0], profileButtons[1], gapDp = 6))
        settingsBody.addView(row(this, profileButtons[2], genericChip, gapDp = 6), spaced(6))
        settingsBody.addView(profileLabel, spaced(6))

        // Generic LoRa: one SF or all at once; the bandwidth is typed next to the frequency.
        genericBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        genericBox.addView(sectionLabel(tr("Spreading factor (All = SF7–12 at once)", "Fator de espalhamento (Todos = SF7–12 juntos)")))
        val sfButtons = (listOf(LoraProfile.ALL_SF) + LoraProfile.GENERIC_SFS).map { sf ->
            chip(if (sf == LoraProfile.ALL_SF) tr("All", "Todos") else "$sf") { selectGeneric(sf, genericBw) }.apply {
                minWidth = 0
                minimumWidth = 0
                setPadding(dp(2), 0, dp(2), 0)
            }.also { sfChips[sf] = it }
        }
        genericBox.addView(row(this, *sfButtons.toTypedArray(), gapDp = 4))
        settingsBody.addView(genericBox)

        channelLabel = sectionLabel("")
        settingsBody.addView(channelLabel)
        freqInput = numberInput("%.6f".format(Locale.US, centerHz / 1e6))
        bwInput = numberInput(LoraProfile.formatKhz(genericBw))
        val apply = pill(tr("Apply", "Aplicar"), UiStyle.TONAL) { applyFrequency() }
        settingsBody.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(freqInput, LinearLayout.LayoutParams(0, -2, 1f))
            addView(bwInput, LinearLayout.LayoutParams(dp(84), -2).apply { marginStart = dp(8) })
            addView(apply, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        })

        gainLabel = sectionLabel("")
        settingsBody.addView(gainLabel)
        val gainButtons = listOf("Auto" to AUTO_GAIN, "20" to 197, "30" to 297, "40" to 402, "49.6" to 496).map { (label, tenths) ->
            chip(label) { applyGain(tenths) }.also { gainChips[tenths] = it }
        }
        settingsBody.addView(row(this, *gainButtons.toTypedArray(), gapDp = 6))

        settingsBody.addView(sectionLabel(tr("Processing (optional)", "Processamento (opcional)")))
        settingsBody.addView(Switch(this).apply {
            text = tr("Tune 250 kHz above (avoids DC spike)", "Sintonizar 250 kHz acima (evita pico DC)")
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
            text = tr("Averaging filter when decimating", "Filtro de média na decimação")
            setTextColor(Ui.TEXT)
            isChecked = averaging
            setOnCheckedChangeListener { _, on ->
                averaging = on
                getPreferences(MODE_PRIVATE).edit().putBoolean("averaging", on).apply()
                receiverStale = true
                log("APP", if (on) tr("Averaging filter on", "Filtro de média ligado") else tr("Averaging filter off", "Filtro de média desligado"))
                updateSettingsSummary()
            }
        }, spaced(8))

        settingsBody.addView(sectionLabel("Idioma / Language"))
        val languageButtons = listOf("Português" to true, "English" to false).map { (label, pt) ->
            chip(label) { selectLanguage(pt) }.also { languageChips[pt] = it }
        }
        settingsBody.addView(row(this, *languageButtons.toTypedArray(), gapDp = 6))
        languageChips.forEach { (pt, chip) -> setChipSelected(chip, pt == Lang.portuguese) }
        root.addView(settings, spaced(12))
        updateGainLabel()
        updateProfileLabel()

        // Main actions.
        val connect = pill(tr("Connect", "Conectar"), UiStyle.PRIMARY) { launchDriver() }
        val stop = pill(tr("Stop", "Parar"), UiStyle.OUTLINED) { disconnect() }
        val record = pill(tr("● Record IQ 10 s", "● Gravar IQ 10 s"), UiStyle.OUTLINED) {
            if (!running) log("IQ", tr("Connect the RTL-SDR before recording", "Conecte o RTL-SDR antes de gravar"))
            else {
                captureRequested = true
                log("IQ", tr("IQ recording requested: 10 seconds, unsigned 8-bit IQ",
                    "Gravação IQ solicitada: 10 segundos, formato unsigned IQ 8-bit"))
            }
        }
        root.addView(row(this, connect, stop, record), spaced(12))

        // Output: segmented tabs + monospace panel.
        messagesTab = chip(tr("Messages", "Mensagens")) { showLogs = false; render() }
        logsTab = chip(tr("Technical logs", "Logs técnicos")) { showLogs = true; render() }
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

        val copy = pill(tr("Copy", "Copiar"), UiStyle.TEXT) {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("MeshSDR", output.text))
            Toast.makeText(this, tr("Copied", "Copiado"), Toast.LENGTH_SHORT).show()
        }
        val save = pill(tr("Save logs", "Salvar logs"), UiStyle.TEXT) { saveLogs() }
        val clear = pill(tr("Clear tab", "Limpar aba"), UiStyle.TEXT) {
            if (showLogs) logs.clear() else messages.clear()
            render()
        }
        root.addView(row(this, copy, save, clear, gapDp = 4), spaced(6))
        setContentView(root)
        updateStatus()
        log("APP", tr("LoRa profile: ${active().describe()}. Any sync word is accepted and shown.",
            "Perfil LoRa: ${active().describe()}. Qualquer sync word é aceito e mostrado."))
        render()
    }

    private fun saveLogs() {
        val text = (logs + listOf("", tr("--- Messages ---", "--- Mensagens ---")) + messages).joinToString("\n")
        thread(name = "save-logs") {
            try {
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val location = PublicFiles.create(this, "meshsdr-log-$stamp.txt", "text/plain").use { target ->
                    target.stream.write(text.toByteArray(Charsets.UTF_8))
                    target.location
                }
                val saved = tr("Logs saved to $location", "Logs salvos em $location")
                log("APP", saved)
                ui.post { Toast.makeText(this, saved, Toast.LENGTH_LONG).show() }
            } catch (e: Exception) { log("ERROR", tr("Save logs", "Salvar logs") + ": ${e.message}") }
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
            if (messages.isEmpty()) tr("Waiting for LoRa: ${active().describe()}.\nDetections (valid header) also show up here.",
                "Aguardando LoRa: ${active().describe()}.\nDetecções (cabeçalho válido) também aparecem aqui.")
            else messages.joinToString("\n")
        setChipSelected(logsTab, showLogs)
        setChipSelected(messagesTab, !showLogs)
        val messagesName = tr("Messages", "Mensagens")
        messagesTab.text = if (messages.isEmpty()) messagesName else "$messagesName (${messages.size})"
        // Follow new lines only when the reader is already at the bottom.
        val atBottom = outputScroll.scrollY + outputScroll.height >= output.height - dp(48)
        if (atBottom) outputScroll.post { outputScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun numberInput(value: String) = EditText(this).apply {
        setSingleLine(true)
        inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        setText(value)
        setTextColor(Ui.TEXT)
        textSize = 18f
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        background = rounded(Ui.SURFACE_2, 14f, Ui.OUTLINE, 1f)
        setPadding(dp(14), dp(10), dp(14), dp(10))
    }

    private fun sectionLabel(text: String) = label(text, 12f, Ui.MUTED, bold = true).apply {
        setPadding(0, dp(12), 0, dp(6))
    }

    private fun spaced(topDp: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(topDp) }

    private fun setSettingsExpanded(expanded: Boolean) {
        settingsScroll.visibility = if (expanded) View.VISIBLE else View.GONE
        settingsArrow.text = if (expanded) "▾" else "▸"
    }

    private fun updateSettingsSummary() {
        val gain = if (gainTenths == AUTO_GAIN) tr("auto gain", "ganho auto") else "%.1f dB".format(Locale.US, gainTenths / 10.0)
        val extras = listOfNotNull(if (offsetTuning) tr("offset", "deslocado") else null,
            if (averaging) tr("filter", "filtro") else null)
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
        levelValue.text = tr("%.0f LSB • clip %.1f%%", "%.0f LSB • sat. %.1f%%").format(Locale.US, rms, clipPct)
        levelValue.setTextColor(color)
        setMeter(levelMeter, (rms / 64.0 * 100).toInt(), color)
    }

    private fun applyFrequency() {
        val mhz = freqInput.text.toString().replace(',', '.').toDoubleOrNull()
        if (mhz == null || mhz !in 24.0..1766.0) {
            log("UI", tr("Invalid frequency or outside the typical R820T range: 24–1766 MHz",
                "Frequência inválida ou fora da faixa típica R820T: 24–1766 MHz"))
            return
        }
        if (profile.protocol == Protocol.GENERIC) {
            val khz = bwInput.text.toString().replace(',', '.').toDoubleOrNull()
            val bw = khz?.let { LoraProfile.genericBandwidth(it) }
            if (bw == null) {
                log("UI", tr("Bandwidth not supported at 1 MS/s. Use (kHz): ", "Largura de banda não suportada a 1 MS/s. Use (kHz): ") +
                    LoraProfile.GENERIC_BANDWIDTHS.joinToString(", ") { LoraProfile.formatKhz(it) })
                return
            }
            if (bw != genericBw) {
                genericBw = bw
                getPreferences(MODE_PRIVATE).edit().putInt("genericBw", bw).apply()
                profile = LoraProfile.generic(centerHz, genericSf, bw)
            }
            bwInput.setText(LoraProfile.formatKhz(bw))
        }
        centerHz = (mhz * 1e6).toLong()
        getPreferences(MODE_PRIVATE).edit().putLong("frequency", centerHz).apply()
        log("SDR", tr("Frequency requested: $centerHz Hz", "Frequência solicitada: $centerHz Hz"))
        updateProfileLabel()
        sendCommands(listOf(RtlTcpProtocol.command(RtlTcpProtocol.SET_FREQUENCY, tunedHz())), "SET_FREQ")
        receiverStale = true
    }

    /** Profile with the frequency actually in use. */
    private fun active(): LoraProfile = profile.copy(frequencyHz = centerHz)

    /** One decoder per spreading factor of the profile (six in generic "all SF" mode). */
    private fun newReceivers(): List<LoraReceiver> {
        val p = active()
        val offset = tuningOffsetHz()
        val on = tr("on", "ligado")
        val off = tr("off", "desligado")
        log("LORA", tr("Decoder: %s; tuner at %.3f MHz; offset %s; averaging filter %s",
            "Decoder: %s; sintonizador em %.3f MHz; deslocamento %s; filtro de média %s").format(
            Locale.US, p.describe(), tunedHz() / 1e6, if (offset != 0) "250 kHz" else off, if (averaging) on else off))
        return p.spreadingFactors.map { sf ->
            LoraReceiver(p, sf, offset, averaging) { frame -> ui.post { onFrame(frame) } }
        }
    }

    /** UI thread. */
    private fun onFrame(frame: LoraFrame) {
        if (frame.verified) decoded++ else detections++
        val text = frame.describe(active())
        messages.addLast("[${timeFormat.format(Date())}] $text")
        while (messages.size > 200) messages.removeFirst()
        log("LORA", text)
        updateStatus()
        if (!showLogs) render()
    }

    private fun updateStatus() {
        if (running) setStatus(status, tr("Receiving", "Recebendo"), Ui.GREEN)
        else setStatus(status, tr("Disconnected", "Desconectado"), Ui.MUTED)
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
        // The generic profile keeps whatever frequency is being explored.
        if (p.protocol != Protocol.GENERIC) freqInput.setText("%.6f".format(Locale.US, p.frequencyHz / 1e6))
        log("APP", tr("LoRa profile: ", "Perfil LoRa: ") + p.copy(frequencyHz = centerHz).describe())
        applyFrequency()
        render()
    }

    private fun selectGeneric(sf: Int, bw: Int) {
        genericSf = sf
        genericBw = bw
        getPreferences(MODE_PRIVATE).edit().putInt("genericSf", sf).putInt("genericBw", bw).apply()
        selectProfile(LoraProfile.generic(centerHz, sf, bw))
        receiverStale = true
    }

    private fun selectLanguage(portuguese: Boolean) {
        if (portuguese == Lang.portuguese) return
        if (running) {
            log("APP", tr("Stop reception before changing the language", "Pare a recepção antes de trocar o idioma"))
            return
        }
        getPreferences(MODE_PRIVATE).edit().putString("language", if (portuguese) "pt" else "en").apply()
        Lang.portuguese = portuguese
        recreate()
    }

    private fun updateProfileLabel() {
        profileLabel.text = active().describe()
        profileChips.forEach { (id, chip) -> setChipSelected(chip, id == profile.id) }
        val generic = profile.protocol == Protocol.GENERIC
        genericBox.visibility = if (generic) View.VISIBLE else View.GONE
        sfChips.forEach { (sf, chip) -> setChipSelected(chip, generic && sf == profile.spreadingFactor) }
        bwInput.visibility = if (generic) View.VISIBLE else View.GONE
        channelLabel.text = if (generic) tr("Frequency (MHz) • bandwidth (kHz)", "Frequência (MHz) • largura de banda (kHz)")
            else tr("LoRa channel frequency (MHz)", "Frequência do canal LoRa (MHz)")
        updateSettingsSummary()
    }

    private fun gainOrNull(): Int? = if (gainTenths == AUTO_GAIN) null else gainTenths

    private fun updateGainLabel() {
        gainLabel.text = if (gainTenths == AUTO_GAIN) tr("Tuner gain: automatic (AGC)", "Ganho do sintonizador: automático (AGC)")
            else tr("Tuner gain: %.1f dB", "Ganho do sintonizador: %.1f dB").format(Locale.US, gainTenths / 10.0)
        gainChips.forEach { (tenths, chip) -> setChipSelected(chip, tenths == gainTenths) }
        if (::settingsSummary.isInitialized) updateSettingsSummary()
    }

    private fun applyGain(tenths: Int) {
        gainTenths = tenths
        getPreferences(MODE_PRIVATE).edit().putInt("gainTenths", tenths).apply()
        updateGainLabel()
        sendCommands(RtlTcpProtocol.gainCommands(gainOrNull()), tr("gain", "ganho"))
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
                log("SDR", tr("rtl_tcp command $what sent", "Comando rtl_tcp $what enviado"))
            } catch (e: Exception) { log("ERROR", tr("Command", "Comando") + " $what: ${e.message}") }
        }
    }

    private fun launchDriver() {
        if (running) { log("APP", tr("Reception already active", "Recepção já ativa")); return }
        applyFrequency()
        val uri = Uri.parse("iqsrc://-a 127.0.0.1 -p $port -s $sampleRate -f ${tunedHz()}")
        try {
            startActivityForResult(Intent(Intent.ACTION_VIEW, uri), 1001)
            log("USB", tr("Asked the RTL-SDR driver to open the device", "Solicitada abertura do driver RTL-SDR"))
        } catch (e: ActivityNotFoundException) {
            log("ERROR", tr("RTL-SDR driver not found. Install RTL-SDR Driver (marto.rtl_tcp_andro).",
                "Driver RTL-SDR não encontrado. Instale RTL-SDR Driver (marto.rtl_tcp_andro)."))
        }
    }

    @Deprecated("Legacy result for external SDR driver")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 1001) return
        if (resultCode != RESULT_OK) {
            val detail = data?.getStringExtra("detailed_exception_message") ?: tr("no details", "sem detalhes")
            log("USB", tr("Driver cancelled or failed: $detail", "Driver cancelou ou falhou: $detail"))
            return
        }
        log("USB", tr("Driver opened the device; connecting to the IQ stream", "Driver abriu o dispositivo; conectando ao fluxo IQ"))
        connectStream()
    }

    private fun connectStream() {
        if (running) return
        running = true
        setStatus(status, tr("Connecting…", "Conectando…"), Ui.AMBER)
        setSettingsExpanded(false)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            // Optional: without it the service still runs, the notification is just hidden.
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1002)
        }
        try {
            ReceiverService.start(this, "${profile.label} • %.3f MHz".format(Locale.US, centerHz / 1e6))
            log("APP", tr("Foreground service active: reception continues with the screen off",
                "Serviço em primeiro plano ativo: recepção continua com a tela apagada"))
        } catch (e: Exception) {
            log("ERROR", tr("Foreground service: ${e.message} (reception may stop with the screen off)",
                "Serviço em primeiro plano: ${e.message} (a recepção pode parar com a tela apagada)"))
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
                    if (n < 0) throw IllegalStateException(tr("Stream ended in the header", "Fim do fluxo no cabeçalho"))
                    offset += n
                }
                if (String(header, 0, 4, Charsets.US_ASCII) != "RTL0")
                    throw IllegalStateException(tr("Invalid rtl_tcp header", "Cabeçalho rtl_tcp inválido"))
                val tuner = RtlTcpProtocol.tunerName(header)
                log("SDR", tr("RTL0 received; tuner $tuner; IQ samples flowing", "RTL0 recebido; sintonizador $tuner; amostras IQ ativas"))
                synchronized(s) {
                    val out = s.getOutputStream()
                    RtlTcpProtocol.gainCommands(gainOrNull()).forEach { out.write(it) }
                    out.flush()
                }
                log("SDR", if (gainTenths == AUTO_GAIN) tr("Automatic gain (AGC) applied", "Ganho automático (AGC) aplicado")
                    else tr("Manual gain %.1f dB applied", "Ganho manual %.1f dB aplicado").format(Locale.US, gainTenths / 10.0))
                val level = IqLevel()
                ui.post { updateStatus() }
                receiverStale = false
                var demods = newReceivers()
                // Time spent decoding, to show whether the phone keeps up (several SFs cost more).
                var decodeNanos = 0L
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
                        log("IQ", tr("Recording to $captureName", "Gravando em $captureName"))
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
                                log("IQ", tr("Recording finished: $captureBytes bytes in $captureName",
                                    "Gravação concluída: $captureBytes bytes em $captureName"))
                            }
                        } catch (e: Exception) {
                            log("ERROR", tr("IQ recording", "Gravação IQ") + ": ${e.message}")
                            try { activeCapture.close() } catch (_: Exception) {}
                            capture = null
                        }
                    }
                    if (receiverStale) {
                        // Profile or frequency changed: restart the decoder with the new settings.
                        receiverStale = false
                        demods.forEach { it.close() }
                        demods = newReceivers()
                    }
                    val t0 = System.nanoTime()
                    demods.forEach { it.feed(buffer, n) }
                    decodeNanos += System.nanoTime() - t0
                    level.add(buffer, n)
                    bytes += n
                    streamBytes += n
                    val now = System.currentTimeMillis()
                    if (now - last >= 2000) {
                        val rate = bytes * 1000.0 / (now - last) / 1e6
                        val cpu = decodeNanos / 1e4 / (now - last)
                        val busy = if (cpu > 80) tr(" — decoder can't keep up: pick a single SF",
                            " — decodificador não acompanha: escolha um único SF") else ""
                        log("IQ", "%.2f MB/s • %s • CPU %.0f%%%s".format(Locale.US, rate, level.summary(), cpu, busy))
                        decodeNanos = 0
                        val rms = level.lastRms
                        val clip = level.lastClipPct
                        ui.post { if (running) updateLevel(rms, clip) }
                        bytes = 0
                        last = now
                    }
                }
                demods.forEach { it.close() }
                capture?.close()
                if (capture != null) log("IQ", tr("Partial recording: $captureBytes bytes in $captureName",
                    "Gravação parcial: $captureBytes bytes em $captureName"))
            } catch (e: Exception) {
                if (running) log("ERROR", tr("IQ stream", "Fluxo IQ") + ": ${e.message}")
            } finally {
                running = false
                ReceiverService.stop(this)
                try { socket?.close() } catch (_: Exception) {}
                socket = null
                ui.post { updateStatus() }
                log("SDR", tr("Reception stopped", "Recepção encerrada"))
            }
        }
    }

    private fun disconnect() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        log("APP", tr("Disconnect requested", "Desconexão solicitada"))
    }

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }
}
