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

// NDI® SDK (закрытый, в git не кладём): ndi.sdk.dir в local.properties, NDI_SDK_DIR или путь установки по умолчанию.
// Нет SDK — приложение собирается без NDI (пункт не показывается). Заголовки — third-party/ndi/include (MIT).
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val ndiSdkDir: File? = listOfNotNull(
    localProps.getProperty("ndi.sdk.dir"),
    System.getenv("NDI_SDK_DIR"),
    "C:/Program Files/NDI/NDI 6 SDK (Android)",
).map(::file).firstOrNull { File(it, "Lib/arm64-v8a/libndi.so").isFile }
val ndiJniDir = layout.buildDirectory.dir("ndi-jni")
val ndiAssetsDir = layout.buildDirectory.dir("ndi-assets")

android {
    namespace = "tv.p2160.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "tv.p2160.player"
        minSdk = 24
        targetSdk = 36
        versionCode = (findProperty("p2160.versionCode") as String?)?.toInt() ?: 17
        versionName = (findProperty("p2160.versionName") as String?) ?: "0.2.5"
        buildConfigField("boolean", "NDI", (ndiSdkDir != null).toString())
        if (ndiSdkDir != null) {
            externalNativeBuild {
                cmake { arguments += "-DNDI_INCLUDE_DIR=${rootProject.file("third-party/ndi/include").invariantSeparatorsPath}" }
            }
        }
    }

    // Мост к NDI (C++) и сама libndi.so — только если SDK найден.
    if (ndiSdkDir != null) {
        ndkVersion = "28.2.13676358"
        externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
        sourceSets.getByName("main").jniLibs.directories.add(ndiJniDir.get().asFile.path)
        sourceSets.getByName("main").assets.directories.add(ndiAssetsDir.get().asFile.path)
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
    // Обычная сборка — APK под каждую архитектуру + universal. С -Pp2160.abi=arm — один APK сразу под
    // arm64-v8a и armeabi-v7a (~31 МБ): ставится на любой телефон и ТВ, поэтому годится для «Поделиться».
    val armOnly = findProperty("p2160.abi") == "arm"
    if (armOnly) defaultConfig.ndk.abiFilters += listOf("arm64-v8a", "armeabi-v7a")
    splits {
        abi {
            isEnable = !armOnly
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

// libndi.so из NDI SDK → build/ndi-jni/<abi>/ (без лицензий и прочих файлов SDK).
val copyNdi = tasks.register<Copy>("copyNdiLibs") {
    if (ndiSdkDir != null) from(File(ndiSdkDir, "Lib")) { include("*/libndi.so") }
    into(ndiJniDir)
}
// Лицензии компонентов libndi — в APK рядом с библиотекой (показываются в «О приложении»).
val copyNdiLicenses = tasks.register<Copy>("copyNdiLicenses") {
    if (ndiSdkDir != null) from(File(ndiSdkDir, "Lib/arm64-v8a")) { include("*.txt") }
    into(ndiAssetsDir.map { it.dir("ndi") })
}
tasks.named("preBuild") { dependsOn(copyNdi, copyNdiLicenses) }

dependencies {
    implementation(project(":player-core"))
    implementation(project(":source-torrent"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.zxing.core)   // QR-код для «Поделиться по Wi-Fi»
    // Трансляция камеры: захват Camera2, аппаратное кодирование H.264/H.265/AAC, RTSP-сервер / SRT / RTMP (Apache-2.0).
    // WHIP (WebRTC) не используем: он тянет BouncyCastle jdk15to18, а у SMB (smbj) — jdk18on с теми же классами.
    implementation(libs.rootencoder.library) {
        exclude(group = "com.github.pedroSG94.RootEncoder", module = "whip")
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15to18")
    }
    implementation(project(":rtsp-server"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
}
