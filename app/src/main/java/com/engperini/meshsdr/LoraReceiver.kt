package com.engperini.meshsdr

/** Native LoRa PHY for one [LoraProfile], fed with the raw rtl_tcp byte stream. */
class LoraReceiver(profile: LoraProfile, private val onFrame: (LoraFrame) -> Unit) : AutoCloseable {
    companion object { init { System.loadLibrary("meshsdr_lora") } }
    private var handle = nativeCreate(
        profile.spreadingFactor, profile.bandwidthHz, profile.codingRate, 0, profile.frequencyHz,
        LoraProfile.decimation(profile), LoraProfile.TUNING_OFFSET_HZ.toDouble(), LoraProfile.SAMPLE_RATE.toDouble()
    )
    private external fun nativeCreate(
        sf: Int, bandwidth: Int, codingRate: Int, syncWord: Int, centerHz: Long,
        decimation: Int, shiftHz: Double, sampleRate: Double
    ): Long
    private external fun nativeFeed(handle: Long, bytes: ByteArray, length: Int)
    private external fun nativeDestroy(handle: Long)

    @Suppress("unused") // Called by the native decoder.
    fun onFrame(data: ByteArray, status: Int, declaredLength: Int, crcOk: Boolean, snr: Float, sync: Int) {
        onFrame(LoraFrame(data, status, declaredLength, crcOk, snr, sync))
    }

    fun feed(bytes: ByteArray, count: Int) {
        if (handle != 0L) nativeFeed(handle, bytes, count)
    }

    override fun close() {
        val old = handle
        handle = 0
        if (old != 0L) nativeDestroy(old)
    }
}
