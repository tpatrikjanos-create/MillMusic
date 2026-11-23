plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "hu.mill.music"
    compileSdk = 35

    defaultConfig {
        applicationId = "hu.mill.music"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    // Saját, fix kulcs: így minden új APK felülírja az előzőt (adatvesztés nélkül).
    signingConfigs {
        create("mill") {
            storeFile = file("millmusic.jks")
            storePassword = "millmusic123"
            keyAlias = "millmusic"
            keyPassword = "millmusic123"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("mill")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("mill")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

// Nincs külső függőség: csak Android rendszer-API-k.
