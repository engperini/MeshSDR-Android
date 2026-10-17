package com.engperini.meshsdr

/** Native LoRa PHY: Meshtastic US LongFast, SF11/BW250k, sync 0x2b. */
class LoraReceiver(private val onDecoded: (ByteArray) -> Unit) : AutoCloseable {
    companion object { init { System.loadLibrary("meshsdr_lora") } }
    private var handle = nativeCreate()
    private external fun nativeCreate(): Long
    private external fun nativeFeed(handle: Long, bytes: ByteArray, length: Int)
    private external fun nativeDestroy(handle: Long)
    @Suppress("unused") // Called by native LoRa callback.
    fun onPacket(data: ByteArray) { onDecoded(data) }
    fun feed(bytes: ByteArray, count: Int) {
        if (handle != 0L) nativeFeed(handle, bytes, count)
    }
    override fun close() {
        val old = handle
        handle = 0
        if (old != 0L) nativeDestroy(old)
    }
}
