package com.engperini.meshsdr

import java.util.Locale

/** LoRa radio settings the decoder listens for. */
data class LoraProfile(
    val id: String,
    val label: String,
    val frequencyHz: Long,
    val spreadingFactor: Int,
    val bandwidthHz: Int,
    /** 1..4 means 4/5..4/8; only a starting guess, the LoRa header carries the real one. */
    val codingRate: Int,
    /** Expected sync word, for labels only: the decoder accepts any and reports it. */
    val syncWord: Int
) {
    fun describe(): String = String.format(
        Locale.US, "%s • %.3f MHz • SF%d / BW%s kHz / CR4/%d",
        label, frequencyHz / 1e6, spreadingFactor, formatKhz(bandwidthHz), codingRate + 4
    )

    companion object {
        const val SAMPLE_RATE = 1_000_000
        /** The tuner sits this far above the channel to keep the RTL DC spike out of it. */
        const val TUNING_OFFSET_HZ = 250_000

        // Brazil: the Meshtastic community uses region ANZ (915-928 MHz) with
        // LongFast, whose default slot there is 919.875 MHz. MeshCore groups in
        // Brazil use 923.125 MHz, 62.5 kHz, SF8, CR 4/8.
        val MESHTASTIC_BR = LoraProfile("meshtastic-br", "Meshtastic BR (ANZ LongFast)", 919_875_000, 11, 250_000, 1, 0x2B)
        val MESHCORE_BR = LoraProfile("meshcore-br", "MeshCore BR", 923_125_000, 8, 62_500, 4, 0x12)
        val MESHTASTIC_US = LoraProfile("meshtastic-us", "Meshtastic US (LongFast)", 906_875_000, 11, 250_000, 1, 0x2B)
        val ALL = listOf(MESHTASTIC_BR, MESHCORE_BR, MESHTASTIC_US)
        val DEFAULT = MESHTASTIC_BR

        fun byId(id: String?): LoraProfile = ALL.firstOrNull { it.id == id } ?: DEFAULT

        /** Decimation from the 1 MS/s stream to the decoder's 2 samples per chip. */
        fun decimation(profile: LoraProfile): Int {
            val rate = 2 * profile.bandwidthHz
            require(SAMPLE_RATE % rate == 0) { "Bandwidth ${profile.bandwidthHz} does not divide $SAMPLE_RATE" }
            return SAMPLE_RATE / rate
        }

        fun syncName(sync: Int): String = when (sync) {
            0x2B -> "0x2B Meshtastic"
            0x12 -> "0x12 rede privada (MeshCore?)"
            0x34 -> "0x34 LoRaWAN"
            else -> String.format(Locale.US, "0x%02X", sync)
        }

        private fun formatKhz(hz: Int): String =
            if (hz % 1000 == 0) (hz / 1000).toString() else String.format(Locale.US, "%.1f", hz / 1000.0)
    }
}

/** One event from the native decoder. */
class LoraFrame(
    val bytes: ByteArray,
    val status: Int,
    val declaredLength: Int,
    val crcOk: Boolean,
    val snrDb: Float,
    val syncWord: Int
) {
    fun describe(): String {
        val sync = LoraProfile.syncName(syncWord)
        val snr = String.format(Locale.US, "SNR %.1f dB", snrDb)
        return when (status) {
            HEADER_ONLY -> "LoRa detectado • sync $sync • cabeçalho válido: $declaredLength bytes • $snr"
            CRC_FAIL -> "Quadro LoRa com CRC inválido • ${bytes.size} bytes • sync $sync • $snr"
            else -> {
                val meshtastic = if (syncWord == 0x2B) runCatching { MeshtasticAirPacket.parse(bytes).summary() }.getOrNull() else null
                meshtastic?.let { "$it • $snr" }
                    ?: "Quadro LoRa CRC-ok • ${bytes.size} bytes • sync $sync • $snr • ${hex(bytes)}"
            }
        }
    }

    companion object {
        // Mirrors lora_lite::PacketStatus.
        const val HEADER_ONLY = 0
        const val CRC_FAIL = 1
        const val DECODED = 2

        fun hex(bytes: ByteArray, max: Int = 24): String =
            bytes.take(max).joinToString("") { String.format(Locale.US, "%02x", it.toInt() and 0xff) } +
                if (bytes.size > max) "…" else ""
    }
}
