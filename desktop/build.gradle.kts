import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.compose") version "2.4.20"
    id("org.jetbrains.compose") version "1.12.2"
}

// Версия десктопа совпадает со следующим номером Android-приложения (сейчас в релизе 0.2.25).
// Сам Android-модуль здесь не трогаем: номер ему ставит владелец в момент релиза.
val appVersion = "0.2.26"

group = "tv.p2160"
version = appVersion

kotlin {
    jvmToolchain(21)
    sourceSets.named("main") {
        kotlin.srcDir(layout.buildDirectory.dir("shared-src"))
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")

    implementation("net.java.dev.jna:jna:5.17.0")
    implementation("org.json:json:20240303")

    // Те же координаты, что у Android-модулей: SMB 2/3 и libtorrent4j.
    implementation("com.hierynomus:smbj:0.15.0")
    implementation("com.rapid7.client:dcerpc:0.12.13")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    val os = System.getProperty("os.name").lowercase()
    val torrentNative = when {
        os.contains("win") -> "org.libtorrent4j:libtorrent4j-windows:2.1.0-39"
        os.contains("mac") -> "org.libtorrent4j:libtorrent4j-macos:2.1.0-39"
        else -> "org.libtorrent4j:libtorrent4j-linux:2.1.0-39"
    }
    implementation(torrentNative)

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

// Чистые JVM-файлы Android-модулей. Исходники остаются там, десктоп только компилирует копию.
val syncShared by tasks.registering(Copy::class) {
    into(layout.buildDirectory.dir("shared-src"))
    from(rootProject.projectDir.resolve("../source-torrent/src/main/java")) {
        include("tv/p2160/torrent/TorrentSession.kt")
        include("tv/p2160/torrent/PiecePlanner.kt")
        include("tv/p2160/torrent/Magnet.kt")
    }
    from(rootProject.projectDir.resolve("../player-core/src/main/java")) {
        include("tv/p2160/core/iptv/M3uParser.kt")
        include("tv/p2160/core/source/dlna/ContentDirectory.kt")
        include("tv/p2160/core/source/dlna/Didl.kt")
        include("tv/p2160/core/source/dlna/DlnaHttp.kt")
        include("tv/p2160/core/source/dlna/DlnaProbe.kt")
        include("tv/p2160/core/source/dlna/DlnaServer.kt")
        include("tv/p2160/core/source/dlna/Ssdp.kt")
        include("tv/p2160/core/source/dlna/XmlLite.kt")
    }
}

tasks.withType<KotlinCompile>().configureEach {
    dependsOn(syncShared)
}

val stageLibmpv by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Собрать каталог нативных библиотек libmpv для этой ОС."
    workingDir = projectDir
    val python = if (System.getProperty("os.name").lowercase().contains("win")) "python" else "python3"
    commandLine(python, "scripts/stage-libmpv.py")
}

val stagePackagedNatives by tasks.registering(Copy::class) {
    group = "distribution"
    description = "Положить libmpv в ресурсы упакованного приложения."
    dependsOn(stageLibmpv)
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val folder = when {
        os.contains("win") -> "windows-x64"
        os.contains("mac") -> if (arch.contains("aarch64") || arch.contains("arm")) "macos-arm64" else "macos-x64"
        else -> "linux-x64"
    }
    val osId = when {
        os.contains("win") -> "windows"
        os.contains("mac") -> "macos"
        else -> "linux"
    }
    // Compose copies only packaging/<os>/ and packaging/common into the image.
    from(layout.projectDirectory.dir("natives/$folder"))
    into(layout.projectDirectory.dir("packaging/$osId/natives"))
}

tasks.configureEach {
    if (name == "run" || name == "runRelease" || name == "prepareAppResources" ||
        name.startsWith("package") || name.startsWith("createDistributable") ||
        name.startsWith("createRelease")
    ) {
        dependsOn(stagePackagedNatives)
    }
}

compose.desktop {
    application {
        mainClass = "tv.p2160.desktop.MainKt"
        // Release-упаковка иначе гоняет ProGuard и падает на необязательных
        // классах Android/IBM внутри smbj, Guava и BouncyCastle.
        buildTypes.release.proguard {
            isEnabled.set(false)
        }
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "2160player"
            // jpackage on macOS rejects an app-version whose first number is 0.
            // Compose also checks the MSI format on that machine, so the bundle
            // version needs three numbers. The window still shows 0.2.26.
            packageVersion = if (System.getProperty("os.name").lowercase().contains("mac")) "2.26.0" else appVersion
            description = "2160 Player"
            vendor = "2160 Player"
            // jlink не видит часть JDK, без которой HTTPS, JNA и AWT молча ломаются.
            modules(
                "jdk.unsupported",
                "jdk.crypto.ec",
                "jdk.httpserver",
                "java.instrument",
                "java.management",
                "java.naming",
                "java.sql",
                "java.desktop",
                "java.logging",
                "java.xml",
                "java.prefs",
                "jdk.zipfs",
            )
            appResourcesRootDir.set(project.layout.projectDirectory.dir("packaging"))
            val linuxIcon = rootProject.projectDir.resolve("../design/logo-v2/final/play_store_icon.png")
            val windowsIcon = rootProject.projectDir.resolve("icons/2160player.ico")
            linux {
                // Debian package names must start with a letter. The app name stays 2160player.
                packageName = "p2160player"
                if (linuxIcon.exists()) iconFile.set(linuxIcon)
                shortcut = true
            }
            macOS {
                // .dmg без подписи. Иконка .icns не лежит в репозитории, берётся стандартная.
                bundleID = "tv.p2160.player.desktop"
            }
            windows {
                menu = true
                shortcut = true
                if (windowsIcon.exists()) iconFile.set(windowsIcon)
            }
        }
    }
}

tasks.test {
    useJUnit()
    testLogging { showStandardStreams = true }
}
