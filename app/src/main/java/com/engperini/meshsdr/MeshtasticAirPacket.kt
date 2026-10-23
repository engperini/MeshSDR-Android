package com.engperini.meshsdr

/**
 * Parses the 16-byte Meshtastic LoRa radio envelope AFTER PHY demodulation.
 * This is NOT a LoRa IQ demodulator and does not decrypt the payload.
 */
data class MeshtasticAirPacket(
    val destination: Long,
    val sender: Long,
    val packetId: Long,
    val flags: Int,
    val channelHash: Int,
    val nextHop: Int,
    val relayNode: Int,
    val encryptedPayload: ByteArray
) {
    val broadcast: Boolean get() = destination == 0xffffffffL
    /** Hops the packet may still take. */
    val hopLimit: Int get() = flags and 0x07
    /** Hop limit the sender set; 0 from firmware older than 2.3. */
    val hopStart: Int get() = (flags shr 5) and 0x07
    val wantAck: Boolean get() = flags and 0x08 != 0
    val viaMqtt: Boolean get() = flags and 0x10 != 0

    fun summary(): String {
        val parts = mutableListOf("Meshtastic")
        parts += if (broadcast) tr("broadcast", "para todos") else tr("to", "para") + " !%08x".format(destination)
        parts += tr("from", "de") + " !%08x".format(sender)
        parts += "id %08x".format(packetId)
        // 0x08 is the hash of the public LongFast channel with the default key.
        parts += tr("channel", "canal") + " %02x".format(channelHash) +
            if (channelHash == 0x08) tr(" (public LongFast)", " (LongFast público)") else ""
        parts += if (hopStart in 1..7 && hopStart >= hopLimit)
            tr("${hopStart - hopLimit} of $hopStart hops", "${hopStart - hopLimit} de $hopStart saltos")
            else tr("hop limit $hopLimit", "limite de saltos $hopLimit")
        if (relayNode != 0) parts += tr("relayed by ..%02x", "retransmitido por ..%02x").format(relayNode)
        if (wantAck) parts += tr("wants ack", "pede confirmação")
        if (viaMqtt) parts += tr("via MQTT", "via MQTT")
        parts += tr("${encryptedPayload.size} B encrypted", "${encryptedPayload.size} B cifrados")
        return parts.joinToString(" • ")
    }

    companion object {
        fun parse(frame: ByteArray): MeshtasticAirPacket {
            require(frame.size in 17..255) { "Expected 16-byte header plus encrypted payload" }
            fun u32(offset: Int): Long =
                (frame[offset].toLong() and 255) or
                ((frame[offset + 1].toLong() and 255) shl 8) or
                ((frame[offset + 2].toLong() and 255) shl 16) or
                ((frame[offset + 3].toLong() and 255) shl 24)
            return MeshtasticAirPacket(
                destination = u32(0),
                sender = u32(4),
                packetId = u32(8),
                flags = frame[12].toInt() and 255,
                channelHash = frame[13].toInt() and 255,
                nextHop = frame[14].toInt() and 255,
                relayNode = frame[15].toInt() and 255,
                encryptedPayload = frame.copyOfRange(16, frame.size)
            )
        }
    }
}
