package com.engperini.meshsdr

import java.util.Locale

/**
 * MeshCore over-the-air packet, parsed after LoRa PHY decoding.
 * Layout from https://docs.meshcore.io/packet_format/ and /payloads/:
 * header (0bVVPPPPRR), transport codes (4 B, transport routes only),
 * path length (bits 6-7 hash size - 1, bits 0-5 hop count), path, payload.
 * Encrypted payloads are only described (sizes, hashes), except group text on
 * channels with a public key (Public, hashtags: [MeshCoreChannels]).
 * ADVERT is signed but not encrypted, so its node name and location are shown.
 */
data class MeshCorePacket(
    val routeType: Int,
    val payloadType: Int,
    val version: Int,
    val transportCodes: ByteArray?,
    val hashSize: Int,
    val path: List<ByteArray>,
    val payload: ByteArray
) {
    val routeName: String get() = ROUTES[routeType]
    val typeName: String get() = TYPES.getOrElse(payloadType) { "TYPE_$payloadType" }

    fun summary(): String {
        val hops = if (path.isEmpty()) tr("direct", "direto") else
            "${path.size} " + (if (path.size > 1) tr("hops", "saltos") else tr("hop", "salto")) +
                " via ${path.joinToString("›") { hex(it) }}"
        return "MeshCore $typeName • $routeName • $hops • ${describePayload()}"
    }

    private fun describePayload(): String = when (payloadType) {
        TYPE_ADVERT -> advert()?.describe() ?: tr("incomplete advert (${payload.size} B)", "anúncio incompleto (${payload.size} B)")
        TYPE_REQ, TYPE_RESPONSE, TYPE_TXT_MSG, TYPE_PATH ->
            if (payload.size >= 4) tr("to ${hx(payload[0])} • from ${hx(payload[1])} • ${payload.size - 4} B encrypted",
                "destino ${hx(payload[0])} • origem ${hx(payload[1])} • ${payload.size - 4} B cifrados")
            else "${payload.size} B"
        TYPE_ANON_REQ ->
            if (payload.size >= 35) tr("to ${hx(payload[0])} • sender ${hex(payload.copyOfRange(1, 5))}… • ${payload.size - 35} B encrypted",
                "destino ${hx(payload[0])} • remetente ${hex(payload.copyOfRange(1, 5))}… • ${payload.size - 35} B cifrados")
            else "${payload.size} B"
        TYPE_GRP_TXT, TYPE_GRP_DATA -> {
            val message = if (payloadType == TYPE_GRP_TXT) MeshCoreChannels.decrypt(payload) else null
            val known = if (payload.isNotEmpty()) MeshCoreChannels.nameFor(payload[0].toInt() and 0xff) else null
            when {
                message != null -> tr("channel ${message.channel}", "canal ${message.channel}") + " • \u201c${message.text}\u201d"
                payload.size >= 3 -> tr("channel ${hx(payload[0])}", "canal ${hx(payload[0])}") +
                    (known?.let { " ($it?)" } ?: "") +
                    tr(" • ${payload.size - 3} B encrypted", " • ${payload.size - 3} B cifrados")
                else -> "${payload.size} B"
            }
        }
        TYPE_ACK -> if (payload.size >= 4) "checksum ${hex(payload.copyOfRange(0, 4))}" else "${payload.size} B"
        else -> "${payload.size} B"
    }

    data class Advert(
        val publicKey: ByteArray,
        val timestamp: Long,
        val nodeType: Int,
        val latitude: Double?,
        val longitude: Double?,
        val name: String?
    ) {
        fun describe(): String {
            val kind = when (nodeType) {
                1 -> tr("Node (chat)", "Nó (chat)"); 2 -> tr("Repeater", "Repetidora"); 3 -> tr("Room", "Sala")
                4 -> "Sensor"; else -> tr("Node", "Nó")
            }
            val where = if (latitude != null && longitude != null)
                String.format(Locale.US, tr(" at %.5f, %.5f", " em %.5f, %.5f"), latitude, longitude) else ""
            val who = name?.let { " \"$it\"" } ?: ""
            return "$kind$who$where • " + tr("key", "chave") + " ${hex(publicKey.copyOfRange(0, 4))}…"
        }
    }

    /** ADVERT: pubkey 32, timestamp 4, signature 64, appdata (flags, [lat, lon], [f1], [f2], [name]). */
    fun advert(): Advert? {
        if (payloadType != TYPE_ADVERT || payload.size < 100) return null
        val key = payload.copyOfRange(0, 32)
        val ts = u32(payload, 32)
        var i = 100
        if (i >= payload.size) return Advert(key, ts, 0, null, null, null)
        val flags = payload[i++].toInt() and 0xff
        var lat: Double? = null
        var lon: Double? = null
        if (flags and 0x10 != 0) {
            if (i + 8 > payload.size) return null
            lat = s32(payload, i) / 1e6
            lon = s32(payload, i + 4) / 1e6
            i += 8
        }
        if (flags and 0x20 != 0) i += 2
        if (flags and 0x40 != 0) i += 2
        if (i > payload.size) return null
        val name = if (flags and 0x80 != 0 && i < payload.size)
            String(payload, i, payload.size - i, Charsets.UTF_8).filter { it >= ' ' && it != '\u007f' }.take(32)
        else null
        return Advert(key, ts, flags and 0x0f, lat, lon, name)
    }

    companion object {
        const val TYPE_REQ = 0x00
        const val TYPE_RESPONSE = 0x01
        const val TYPE_TXT_MSG = 0x02
        const val TYPE_ACK = 0x03
        const val TYPE_ADVERT = 0x04
        const val TYPE_GRP_TXT = 0x05
        const val TYPE_GRP_DATA = 0x06
        const val TYPE_ANON_REQ = 0x07
        const val TYPE_PATH = 0x08
        private val ROUTES = listOf("TRANSPORT_FLOOD", "FLOOD", "DIRECT", "TRANSPORT_DIRECT")
        private val TYPES = mapOf(
            0x00 to "REQ", 0x01 to "RESPONSE", 0x02 to "TXT_MSG", 0x03 to "ACK", 0x04 to "ADVERT",
            0x05 to "GRP_TXT", 0x06 to "GRP_DATA", 0x07 to "ANON_REQ", 0x08 to "PATH", 0x09 to "TRACE",
            0x0A to "MULTIPART", 0x0B to "CONTROL", 0x0F to "RAW_CUSTOM"
        )

        /** Returns null when the bytes cannot be a well-formed MeshCore packet. */
        fun parse(frame: ByteArray): MeshCorePacket? {
            if (frame.size < 2) return null
            val header = frame[0].toInt() and 0xff
            val route = header and 0x03
            val type = (header shr 2) and 0x0f
            val version = (header shr 6) and 0x03
            if (version != 0) return null                       // only version 1 is defined
            if (type in 0x0C..0x0E) return null                 // reserved
            var i = 1
            val transport = if (route == 0 || route == 3) {
                if (frame.size < i + 4) return null
                frame.copyOfRange(i, i + 4).also { i += 4 }
            } else null
            if (i >= frame.size) return null
            val pathLen = frame[i++].toInt() and 0xff
            val hashCode = pathLen shr 6
            if (hashCode == 3) return null                      // reserved
            val hashSize = hashCode + 1
            val hops = pathLen and 0x3f
            val pathBytes = hops * hashSize
            if (pathBytes > 64 || i + pathBytes > frame.size) return null
            val path = (0 until hops).map { h -> frame.copyOfRange(i + h * hashSize, i + (h + 1) * hashSize) }
            i += pathBytes
            val payload = frame.copyOfRange(i, frame.size)
            if (payload.isEmpty()) return null
            return MeshCorePacket(route, type, version, transport, hashSize, path, payload)
        }

        private fun u32(b: ByteArray, o: Int): Long =
            (b[o].toLong() and 0xff) or ((b[o + 1].toLong() and 0xff) shl 8) or
                ((b[o + 2].toLong() and 0xff) shl 16) or ((b[o + 3].toLong() and 0xff) shl 24)

        private fun s32(b: ByteArray, o: Int): Int = u32(b, o).toInt()
        private fun hx(b: Byte) = String.format(Locale.US, "%02x", b.toInt() and 0xff)
        fun hex(b: ByteArray) = b.joinToString("") { hx(it) }
    }
}
