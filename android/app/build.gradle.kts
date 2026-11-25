plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("app.cash.paparazzi") // снимки экранов на JVM для сверки со стилем WorkDesk (только тесты)
}

android {
    namespace = "kz.workdesk.ai.pilot"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "kz.workdesk.ai.pilot"
        minSdk = 30 // AccessibilityService.takeScreenshot
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    // Подпись релиза: ключ из таблицы секретов, в CI — секреты PILOT_KEYSTORE_*. Без них релиз не подписывается.
    val ks = System.getenv("PILOT_KEYSTORE")
    signingConfigs {
        if (ks != null) create("release") {
            storeFile = file(ks)
            storeType = "pkcs12"
            storePassword = System.getenv("PILOT_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("PILOT_KEY_ALIAS") ?: "pilot"
            keyPassword = System.getenv("PILOT_KEY_PASSWORD") ?: System.getenv("PILOT_KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        release {
            if (ks != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
}
