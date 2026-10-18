package com.engperini.meshsdr

/** Native LoRa PHY for one [LoraProfile], fed with the raw rtl_tcp byte stream. */
/**
 * @param tuningOffsetHz how far above the channel the tuner sits (0 = on the channel).
 * @param average boxcar-average before decimating instead of keeping every Nth sample.
 */
class LoraReceiver(
    profile: LoraProfile,
    tuningOffsetHz: Int,
    average: Boolean,
    private val onFrame: (LoraFrame) -> Unit
) : AutoCloseable {
    companion object { init { System.loadLibrary("meshsdr_lora") } }
    private var handle = nativeCreate(
        profile.spreadingFactor, profile.bandwidthHz, profile.codingRate, 0, profile.frequencyHz,
        LoraProfile.decimation(profile), tuningOffsetHz.toDouble(), LoraProfile.SAMPLE_RATE.toDouble(), average
    )
    private external fun nativeCreate(
        sf: Int, bandwidth: Int, codingRate: Int, syncWord: Int, centerHz: Long,
        decimation: Int, shiftHz: Double, sampleRate: Double, average: Boolean
    ): Long
    private external fun nativeFeed(handle: Long, bytes: ByteArray, length: Int)
    private external fun nativeDestroy(handle: Long)

    @Suppress("unused") // Called by the native decoder.
    fun onFrame(data: ByteArray, status: Int, declaredLength: Int, crcOk: Boolean, hasCrc: Boolean, snr: Float, sync: Int) {
        onFrame(LoraFrame(data, status, declaredLength, crcOk, snr, sync, hasCrc))
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
