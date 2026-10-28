plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.marqueelens"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.marqueelens"
        minSdk = 26
        targetSdk = 34
        versionCode = 33
        versionName = "4.0"
    }

    // One fixed signing key, so every new build installs as an update over the old one (your lists stay).
    // GitHub: the SIGNING_KEY and SIGNING_PASSWORD secrets (the workflow writes app/marquee.keystore).
    // Android Studio: put marquee.keystore in the app folder and SIGNING_PASSWORD=… in signing.properties next to it.
    val keystore = file("marquee.keystore")
    val signingPassword = System.getenv("SIGNING_PASSWORD")
        ?: file("signing.properties").takeIf { it.exists() }?.readLines()
            ?.firstOrNull { it.startsWith("SIGNING_PASSWORD=") }?.substringAfter("=")?.trim()
    val fixedKey = keystore.exists() && !signingPassword.isNullOrBlank()
    signingConfigs {
        if (fixedKey) create("marquee") {
            storeFile = keystore
            storePassword = signingPassword
            keyAlias = "marquee"
            keyPassword = signingPassword
        }
    }

    buildTypes {
        debug {
            if (fixedKey) signingConfig = signingConfigs.getByName("marquee")
        }
        release {
            isMinifyEnabled = false
            if (fixedKey) signingConfig = signingConfigs.getByName("marquee")
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

// No extra libraries: only Android itself and the Kotlin standard library.
