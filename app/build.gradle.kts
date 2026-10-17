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
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
