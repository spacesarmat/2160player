plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "tv.p2160.sample.embed"
    compileSdk = 37

    defaultConfig {
        applicationId = "tv.p2160.sample.embed"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    // nextlib-media3ext и nextlib-mediainfo несут одинаковые сборки FFmpeg — берём одну копию.
    // Без этого блока сборка любого приложения с player-core падает на mergeNativeLibs.
    packaging {
        jniLibs.pickFirsts += listOf("**/libavcodec.so", "**/libavutil.so", "**/libswscale.so", "**/libswresample.so")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Внутри этого репозитория — зависимость на модуль. Снаружи:
    // implementation("tv.p2160:player-core:0.1.0") (см. docs/EMBEDDING.md).
    implementation(project(":player-core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
}
