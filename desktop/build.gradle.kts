import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.0"
}

// The radio and protocol code is shared with the Android app, file by file.
val shared = listOf(
    "Lang.kt", "LoraProfile.kt", "LoraReceiver.kt", "MeshCorePacket.kt",
    "MeshtasticAirPacket.kt", "IqLevel.kt", "RtlTcpProtocol.kt", "AutoRecorder.kt", "RemoteLink.kt"
)
// ...and so are their unit tests, which run here without an Android SDK.
val sharedTests = listOf(
    "AutoRecorderTest.kt", "IqLevelTest.kt", "LoraProfileTest.kt", "MeshCorePacketTest.kt",
    "MeshtasticAirPacketTest.kt", "RemoteLinkTest.kt", "RtlTcpProtocolTest.kt"
)
sourceSets.main {
    kotlin.srcDir(layout.buildDirectory.dir("generated/shared"))
}
val copyShared by tasks.registering(Copy::class) {
    from("../app/src/main/java/com/engperini/meshsdr") { include(shared) }
    into(layout.buildDirectory.dir("generated/shared/com/engperini/meshsdr"))
}
tasks.named("compileKotlin") { dependsOn(copyShared) }
sourceSets.test {
    kotlin.srcDir(layout.buildDirectory.dir("generated/sharedTests"))
}
val copySharedTests by tasks.registering(Copy::class) {
    from("../app/src/test/java/com/engperini/meshsdr") { include(sharedTests) }
    into(layout.buildDirectory.dir("generated/sharedTests/com/engperini/meshsdr"))
}
tasks.named("compileTestKotlin") { dependsOn(copySharedTests) }

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    testImplementation("junit:junit:4.13.2")
}

// Release builds pass the tag (v0.5.0 or 0.5.0); other builds use the next version.
val appVersion = System.getenv("MESHSDR_VERSION_NAME")?.removePrefix("v")?.takeIf { it.isNotBlank() } ?: "0.5.0"

compose.desktop {
    application {
        mainClass = "com.engperini.meshsdr.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Deb)
            packageName = "MeshSDR"
            packageVersion = appVersion
            description = "Passive LoRa receiver for Meshtastic and MeshCore with an RTL-SDR"
            vendor = "MeshSDR"
            licenseFile.set(project.file("../LICENSE"))
            // Native LoRa PHY (and rtl_tcp on Windows), built by CI into resources/<os-arch>.
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))
            modules("java.prefs")
            linux { iconFile.set(project.file("icons/meshsdr.png")) }
            windows {
                iconFile.set(project.file("icons/meshsdr.ico"))
                menu = true
                shortcut = true
                dirChooser = true
                upgradeUuid = "6f1d3c1e-5a0b-4b8e-9d7a-2c4f9e8a1b30"
            }
        }
    }
}
