plugins {
    alias(libs.plugins.android.library)
}

// Торрент-источник: libtorrent4j (нативные библиотеки ~6–8 МБ на ABI в сжатом виде),
// поэтому вынесен из player-core в отдельный модуль.
android {
    namespace = "tv.p2160.torrent"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all { test ->
            // Интеграционный тест с настоящей сетью: P2160_TORRENT_IT=1 ./gradlew :source-torrent:testDebugUnitTest
            test.environment("P2160_TORRENT_IT", System.getenv("P2160_TORRENT_IT") ?: "")
            test.testLogging { showStandardStreams = true }
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":player-core"))
    api(libs.libtorrent4j)
    // Нативные .so внутри jar (lib/<abi>/libtorrent4j.so) — AGP раскладывает их по ABI-сплитам.
    implementation(libs.libtorrent4j.android.arm)
    implementation(libs.libtorrent4j.android.arm64)
    implementation(libs.libtorrent4j.android.x86)
    implementation(libs.libtorrent4j.android.x64)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit)
    // Десктопная сборка libtorrent для интеграционного теста на Windows.
    testImplementation(libs.libtorrent4j.windows)
}
