plugins {
    alias(libs.plugins.android.library)
}

// RTSP-Server 1.4.3 (pedroSG94, Apache-2.0) исходниками: правка ответа на PLAY — см. NOTICE.md.
android {
    namespace = "com.pedro.rtspserver"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.rootencoder.library) {
        exclude(group = "com.github.pedroSG94.RootEncoder", module = "whip")
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15to18")
    }
    implementation(libs.ktor.network)
    implementation(libs.ktor.network.tls)
}
