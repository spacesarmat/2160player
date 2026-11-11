package tv.p2160.desktop.player

import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.WString
import java.io.File

internal object NativeLibs {
    fun loadMpv(): MpvLib {
        val os = System.getProperty("os.name").lowercase()
        val dir = nativeDir()
        if (dir != null) {
            log("libmpv directory: ${dir.absolutePath}")
            if (os.contains("win")) setDllDirectory(dir)
        } else {
            log("libmpv directory not staged, trying the system library")
        }
        val names = when {
            os.contains("win") -> listOf("libmpv-2", "mpv-2", "mpv")
            else -> listOf("mpv", "mpv.2", "libmpv")
        }
        var last: Throwable? = null
        for (name in names) {
            if (dir != null) NativeLibrary.addSearchPath(name, dir.absolutePath)
            try {
                return Native.load(name, MpvLib::class.java)
            } catch (t: UnsatisfiedLinkError) {
                last = t
                log("failed to load $name: ${t.message}")
            }
        }
        val hint = if (os.contains("linux")) {
            " Нужны библиотеки рабочего стола: libegl1, libvulkan1, libasound2, libpulse0, libx11-6, libva2."
        } else {
            ""
        }
        throw IllegalStateException("libmpv не загрузилась.${hint}", last)
    }

    fun nativeDir(): File? {
        val names = libraryNames()
        val candidates = buildList {
            System.getProperty("compose.application.resources.dir")?.let { add(File(it, "natives")) }
            System.getProperty("p2160.natives")?.let { add(File(it)) }
            add(File("packaging/natives"))
            add(File("packaging/linux/natives"))
            add(File("packaging/macos/natives"))
            add(File("packaging/windows/natives"))
            add(File("natives/${platformFolder()}"))
            add(File("desktop/packaging/natives"))
            add(File("desktop/packaging/linux/natives"))
            add(File("desktop/packaging/macos/natives"))
            add(File("desktop/packaging/windows/natives"))
            add(File("desktop/natives/${platformFolder()}"))
        }
        return candidates.firstOrNull { dir ->
            dir.isDirectory && names.any { File(dir, it).exists() }
        }
    }

    private fun libraryNames(): List<String> {
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("win") -> listOf("libmpv-2.dll")
            os.contains("mac") -> listOf("libmpv.dylib", "libmpv.2.dylib")
            else -> listOf("libmpv.so", "libmpv.so.2")
        }
    }

    fun platformFolder(): String {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        return when {
            os.contains("win") -> "windows-x64"
            os.contains("mac") -> if (arch.contains("aarch64") || arch.contains("arm")) "macos-arm64" else "macos-x64"
            else -> "linux-x64"
        }
    }

    private fun setDllDirectory(dir: File) {
        val kernel = Native.load("kernel32", Kernel32::class.java)
        if (!kernel.SetDllDirectoryW(WString(dir.absolutePath))) {
            log("SetDllDirectoryW failed for ${dir.absolutePath}")
        }
    }

    private interface Kernel32 : com.sun.jna.Library {
        fun SetDllDirectoryW(path: WString): Boolean
    }
}

internal fun log(message: String) {
    println("2160: $message")
}
