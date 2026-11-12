package tv.p2160.desktop.media

import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * libtorrent4j loads `torrent4j` from the filesystem during class init.
 * The bytes live inside the platform jar, so the path property has to be set
 * before `SessionManager` is constructed.
 * The library is extracted into a fresh owner-only directory. A fixed path
 * under java.io.tmpdir can be planted by another user before we write it.
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
        val bytes = url.openStream().use { it.readBytes() }
        val out = extractNative(bytes, resource.substringAfterLast('/'))
        System.setProperty("libtorrent4j.jni.path", out.absolutePath)
        prepared = true
    }
}

/** Writes the native into a new private directory. Existing files are never reused. */
fun extractNative(bytes: ByteArray, fileName: String): File {
    val safeName = fileName.substringAfterLast('/').substringAfterLast('\\').ifBlank { "libtorrent4j" }
    val dir = Files.createTempDirectory("p2160-libtorrent4j-")
    ownerOnlyDirectory(dir)
    val out = dir.resolve(safeName)
    if (posix()) {
        val perms = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        )
        Files.createFile(out, PosixFilePermissions.asFileAttribute(perms))
        Files.write(out, bytes)
    } else {
        Files.write(out, bytes)
        val file = out.toFile()
        file.setReadable(false, false)
        file.setReadable(true, true)
        file.setWritable(false, false)
        file.setWritable(true, true)
        file.setExecutable(false, false)
        file.setExecutable(true, true)
    }
    return out.toFile()
}

private fun ownerOnlyDirectory(dir: Path) {
    if (!posix()) return
    Files.setPosixFilePermissions(
        dir,
        setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        ),
    )
}

private fun posix(): Boolean =
    FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
