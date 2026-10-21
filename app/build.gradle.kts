plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.facu.geminibrowser"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.facu.geminibrowser"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "0.2.0"
    }

    // Clave de firma fija: así cada APK nuevo se instala "encima" del anterior sin desinstalar.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
