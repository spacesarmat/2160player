pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Трансляция камеры (RootEncoder, RTSP-Server) публикуется только на JitPack — берём оттуда лишь их.
        maven("https://jitpack.io") {
            content { includeGroupByRegex("com[.]github[.]pedroSG94.*") }
        }
    }
}

rootProject.name = "2160player"
include(":player-core")
include(":source-torrent")
include(":app")
// RTSP-Server (pedroSG94, Apache-2.0) исходниками с правкой — см. third-party/rtsp-server/NOTICE.md
include(":rtsp-server")
project(":rtsp-server").projectDir = file("third-party/rtsp-server")
include(":samples:embed-demo")
