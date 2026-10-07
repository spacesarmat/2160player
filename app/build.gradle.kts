import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Ключ подписи релизов: keystore.properties (не в git) или переменные окружения в CI.
val signing = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun signingValue(key: String, env: String): String? = signing.getProperty(key) ?: System.getenv(env)

android {
    namespace = "tv.p2160.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "tv.p2160.player"
        minSdk = 24
        targetSdk = 36
        versionCode = (findProperty("p2160.versionCode") as String?)?.toInt() ?: 8
        versionName = (findProperty("p2160.versionName") as String?) ?: "0.1.6"
    }

    signingConfigs {
        create("release") {
            signingValue("storeFile", "P2160_KEYSTORE")?.let { storeFile = file(it) }
            storePassword = signingValue("storePassword", "P2160_KEYSTORE_PASSWORD")
            keyAlias = signingValue("keyAlias", "P2160_KEY_ALIAS")
            keyPassword = signingValue("keyPassword", "P2160_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Без ключа релиз собирается неподписанным (например, в CI на pull request).
            if (signingValue("storeFile", "P2160_KEYSTORE") != null) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    // Отдельные APK под архитектуры: FFmpeg занимает ~8 МБ на каждую ABI.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = true
        }
    }

    // nextlib-media3ext и nextlib-mediainfo несут одинаковые сборки FFmpeg — берём одну копию.
    packaging {
        // Нативные библиотеки (FFmpeg, libtorrent) храним сжатыми: APK меньше примерно на 10 МБ на ABI.
        jniLibs.useLegacyPackaging = true
        jniLibs.pickFirsts += listOf("**/libavcodec.so", "**/libavutil.so", "**/libswscale.so", "**/libswresample.so")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":player-core"))
    implementation(project(":source-torrent"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
}
