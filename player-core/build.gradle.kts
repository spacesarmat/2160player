plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    `maven-publish`
}

// Версия публикуемого артефакта: ./gradlew -PplayerCoreVersion=1.2.3 ...
group = "tv.p2160"
version = providers.gradleProperty("playerCoreVersion").getOrElse("0.1.7")

android {
    namespace = "tv.p2160.core"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.exoplayer.smoothstreaming)
    implementation(libs.media3.exoplayer.rtsp)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    implementation(libs.nextlib.media3ext)
    implementation(libs.nextlib.mediainfo)
    implementation(libs.smbj)
    // Список общих папок сервера (SRVSVC NetShareEnum)
    implementation(libs.smbj.rpc)

    implementation(libs.androidx.core.ktx)
    // Типы из этих библиотек видны в публичном API (ActivityResultContract, StateFlow,
    // @Composable, ImageVector, ColorScheme) — поэтому api, а не implementation.
    api(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    api(libs.kotlinx.coroutines.android)

    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    // XmlPullParser для JVM-тестов разбора XMLTV (на устройстве — android.util.Xml)
    testImplementation(libs.kxml2)
}

// Публикация: mavenLocal (./gradlew :player-core:publishReleasePublicationToMavenLocal)
// и GitHub Packages (./gradlew :player-core:publishReleasePublicationToGitHubPackagesRepository).
// Учётные данные — только из gradle-свойств (gpr.user / gpr.key) или окружения (GITHUB_ACTOR / GITHUB_TOKEN).
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "tv.p2160"
                artifactId = "player-core"
                version = project.version.toString()
                pom {
                    name.set("2160 Player core")
                    description.set("Embeddable Android media player: Media3 + FFmpeg, Blu-ray ISO/BDMV, SMB, Compose UI.")
                    url.set("https://github.com/spacesarmat/2160player")
                    licenses {
                        license {
                            name.set("GNU General Public License v3.0")
                            url.set("https://www.gnu.org/licenses/gpl-3.0.html")
                        }
                    }
                }
            }
        }
        repositories {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/spacesarmat/2160player")
                credentials {
                    username = providers.gradleProperty("gpr.user")
                        .orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
                    password = providers.gradleProperty("gpr.key")
                        .orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
                }
            }
        }
    }
}
