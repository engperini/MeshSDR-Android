package com.engperini.meshsdr

/** Loads the native LoRa PHY. Android packs it in the APK; the desktop app has its own version of this file. */
internal object NativeLibrary {
    fun load() = System.loadLibrary("meshsdr_lora")
}
