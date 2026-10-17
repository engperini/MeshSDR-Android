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
    fun summary(): String =
        "Meshtastic from=!%08x to=%s id=%08x ch=%02x hop=%d bytes=%d (encrypted)".format(
            sender, if (broadcast) "broadcast" else "!%08x".format(destination),
            packetId, channelHash, flags and 0x07, encryptedPayload.size
        )

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
