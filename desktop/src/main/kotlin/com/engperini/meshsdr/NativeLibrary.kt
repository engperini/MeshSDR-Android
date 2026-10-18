package com.engperini.meshsdr

import java.io.File

/**
 * Desktop loader for the native LoRa PHY. The packaged app ships it in its
 * resources directory; development runs can point -Dmeshsdr.native.dir at a build.
 */
internal object NativeLibrary {
    fun load() {
        val name = System.mapLibraryName("meshsdr_lora")
        val dirs = listOfNotNull(
            System.getProperty("compose.application.resources.dir"),
            System.getProperty("meshsdr.native.dir")
        )
        for (dir in dirs) {
            val file = File(dir, name)
            if (file.isFile) {
                System.load(file.absolutePath)
                return
            }
        }
        System.loadLibrary("meshsdr_lora")
    }
}
