package com.engperini.meshsdr

/**
 * Native LoRa PHY for one [LoraProfile] and spreading factor, fed with the raw rtl_tcp byte stream.
 * @param spreadingFactor one of the profile's [LoraProfile.spreadingFactors].
 * @param average boxcar-average before decimating instead of keeping every Nth sample.
 * @param diversity also run decoders for every decimation phase, both filter settings and
 *   drift tracking (see diversity.h); false = a single decoder.
 */
class LoraReceiver(
    profile: LoraProfile,
    private val spreadingFactor: Int,
    average: Boolean,
    diversity: Boolean,
    private val onFrame: (LoraFrame) -> Unit
) : AutoCloseable {
    companion object { init { NativeLibrary.load() } }
    private var handle = nativeCreate(
        spreadingFactor, profile.bandwidthHz, profile.codingRate, 0, profile.frequencyHz,
        LoraProfile.decimation(profile), 0.0, LoraProfile.SAMPLE_RATE.toDouble(), average, diversity
    )
    private external fun nativeCreate(
        sf: Int, bandwidth: Int, codingRate: Int, syncWord: Int, centerHz: Long,
        decimation: Int, shiftHz: Double, sampleRate: Double, average: Boolean, diversity: Boolean
    ): Long
    private external fun nativeFeed(handle: Long, bytes: ByteArray, length: Int)
    private external fun nativeDestroy(handle: Long)

    @Suppress("unused") // Called by the native decoder.
    fun onFrame(data: ByteArray, status: Int, declaredLength: Int, crcOk: Boolean, hasCrc: Boolean, snr: Float, sync: Int,
                offsetHz: Float) {
        onFrame(LoraFrame(data, status, declaredLength, crcOk, snr, sync, hasCrc, spreadingFactor, offsetHz))
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
