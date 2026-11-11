package tv.p2160.desktop.media

import java.io.File

/**
 * libtorrent4j loads `torrent4j` from the filesystem during class init.
 * The bytes live inside the platform jar, so the path property has to be set
 * before `SessionManager` is constructed.
 */
object TorrentNatives {
    @Volatile private var prepared = false

    fun resourcePath(osName: String, osArch: String): String {
        val os = osName.lowercase()
        val arch = osArch.lowercase()
        return when {
            os.contains("win") -> "lib/x86_64/libtorrent4j.dll"
            os.contains("mac") -> "lib/arm64/libtorrent4j.dylib"
            arch == "arm" || arch.startsWith("armv") -> "lib/arm/libtorrent4j.so"
            else -> "lib/x86_64/libtorrent4j.so"
        }
    }

    fun ensureLoaded() {
        if (prepared && File(System.getProperty("libtorrent4j.jni.path").orEmpty()).isFile) return
        val resource = resourcePath(System.getProperty("os.name"), System.getProperty("os.arch"))
        val url = TorrentNatives::class.java.classLoader.getResource(resource)
            ?: error("libtorrent4j native not in the classpath: $resource")
        val dir = File(System.getProperty("java.io.tmpdir"), "p2160-libtorrent4j")
        dir.mkdirs()
        val out = File(dir, resource.substringAfterLast('/'))
        if (!out.isFile || out.length() == 0L) {
            url.openStream().use { input -> out.outputStream().use { input.copyTo(it) } }
            out.setExecutable(true, false)
        }
        System.setProperty("libtorrent4j.jni.path", out.absolutePath)
        prepared = true
    }
}
