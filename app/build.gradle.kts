plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.engperini.meshsdr"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.engperini.meshsdr"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("MESHSDR_VERSION_CODE") ?: "2").toInt()
        versionName = "0.2.${versionCode}"
    }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }
    ndkVersion = "27.2.12479018"
    // Stable signing from CI secrets so each APK upgrades the previous one in place.
    // Without MESHSDR_KEYSTORE the default (per-machine) debug key is used.
    val keystorePath = System.getenv("MESHSDR_KEYSTORE")
    if (keystorePath != null) {
        signingConfigs {
            create("stable") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("MESHSDR_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("MESHSDR_KEY_ALIAS") ?: "meshsdr"
                keyPassword = System.getenv("MESHSDR_KEY_PASSWORD") ?: System.getenv("MESHSDR_KEYSTORE_PASSWORD")
            }
        }
        buildTypes {
            getByName("debug") { signingConfig = signingConfigs.getByName("stable") }
            getByName("release") { signingConfig = signingConfigs.getByName("stable") }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
