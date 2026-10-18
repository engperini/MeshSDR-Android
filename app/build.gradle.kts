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
        // Release builds pass MESHSDR_VERSION_NAME from the git tag (e.g. 0.3.0).
        versionName = System.getenv("MESHSDR_VERSION_NAME") ?: "0.2.${versionCode}"
    }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }
    ndkVersion = "27.2.12479018"
    // Public, test-only key committed to the repo: every build (CI or local)
    // gets the same signature, so new APKs install over the previous one.
    signingConfigs {
        create("stable") {
            storeFile = file("meshsdr-debug.jks")
            storePassword = "meshsdr-public"
            keyAlias = "meshsdr"
            keyPassword = "meshsdr-public"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("stable") }
        getByName("release") { signingConfig = signingConfigs.getByName("stable") }
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
