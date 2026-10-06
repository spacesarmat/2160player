package tv.p2160.core.bluray

import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/** Элемент каталога диска. */
data class DiscEntry(val name: String, val isDirectory: Boolean, val size: Long)

/**
 * Файловая система диска: ISO (UDF) или папка BDMV.
 * Пути — относительно корня, через «/», регистр имён не важен ("BDMV/PLAYLIST/00800.mpls").
 */
interface DiscFileSystem : Closeable {
    /** Содержимое каталога или null, если каталога нет. */
    fun list(path: String): List<DiscEntry>?

    /** Сведения о файле/каталоге или null, если его нет. */
    fun stat(path: String): DiscEntry?

    /** Открывает файл. Закрытие результата не закрывает файловую систему. */
    fun open(path: String): RandomAccessSource

    /** Экстенты файла внутри исходного образа (только для образов), иначе null. */
    fun extents(path: String): List<DiscExtent>? = null
}

/** Читает небольшой файл (mpls/clpi/xml) целиком. */
fun DiscFileSystem.readAll(path: String, maxSize: Int = 16 * 1024 * 1024): ByteArray =
    open(path).use { src ->
        if (src.size > maxSize) throw IOException("$path is too large: ${src.size}")
        src.readBytes(0, src.size.toInt())
    }

internal fun splitPath(path: String): List<String> =
    path.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }

/**
 * Поставщик содержимого папки (локальная папка, SMB, SAF).
 * Пути — уже нормализованные, с точным регистром, "" — корень.
 */
interface DirectoryProvider : Closeable {
    fun list(path: String): List<DiscEntry>?
    fun open(path: String): RandomAccessSource
    override fun close() {}
}

/** Папка в локальной файловой системе. */
class FileDirectoryProvider(private val root: File) : DirectoryProvider {
    private fun file(path: String) = if (path.isEmpty()) root else File(root, path)

    override fun list(path: String): List<DiscEntry>? =
        file(path).listFiles()?.map { DiscEntry(it.name, it.isDirectory, if (it.isDirectory) 0 else it.length()) }

    override fun open(path: String): RandomAccessSource = FileRandomAccessSource(file(path))
}

/**
 * Диск в виде папки (корень с BDMV или сама BDMV). Имена ищутся без учёта регистра,
 * листинги кэшируются — у сетевых поставщиков каждый запрос дорогой.
 */
class DirectoryFileSystem(private val provider: DirectoryProvider) : DiscFileSystem {
    private val listings = HashMap<String, List<DiscEntry>?>()

    @Synchronized
    private fun listExact(path: String): List<DiscEntry>? = listings.getOrPut(path) { provider.list(path) }

    /** Переводит путь в точный регистр; null — нет такого элемента. */
    private fun resolve(path: String): Pair<String, DiscEntry>? {
        var exact = ""
        var entry = DiscEntry("", true, 0)
        for (part in splitPath(path)) {
            if (!entry.isDirectory) return null
            val children = listExact(exact) ?: return null
            entry = children.firstOrNull { it.name == part }
                ?: children.firstOrNull { it.name.equals(part, ignoreCase = true) } ?: return null
            exact = if (exact.isEmpty()) entry.name else "$exact/${entry.name}"
        }
        return exact to entry
    }

    override fun list(path: String): List<DiscEntry>? {
        val (exact, entry) = resolve(path) ?: return null
        return if (entry.isDirectory) listExact(exact) else null
    }

    override fun stat(path: String): DiscEntry? = resolve(path)?.second

    override fun open(path: String): RandomAccessSource {
        val (exact, entry) = resolve(path) ?: throw FileNotFoundException(path)
        if (entry.isDirectory) throw FileNotFoundException("$path is a directory")
        return provider.open(exact)
    }

    override fun close() = provider.close()
}
