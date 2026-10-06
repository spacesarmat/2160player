package tv.p2160.core.bluray

import android.content.Context
import android.net.Uri
import tv.p2160.core.source.RandomAccessSources
import tv.p2160.core.source.SmbRandomAccessSource
import tv.p2160.core.source.smb.SmbConnections
import tv.p2160.core.source.smb.SmbPath
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Открытый диск и выбранный фильм. Клипы адресуются как `p2160disc://<id>/<номер клипа>`. */
class DiscSession internal constructor(
    val id: String,
    val sourceUri: Uri,
    val disc: BlurayDisc,
    val title: BlurayTitle,
) {
    val titleUri: Uri get() = Uri.parse("$SCHEME://$id")

    fun clipUri(index: Int): Uri = Uri.parse("$SCHEME://$id/$index")

    /** Язык дорожки по PID из таблицы STN плейлиста (в самом потоке Blu-ray языков обычно нет). */
    fun languageForPid(pid: Int): String? =
        (title.audioStreams + title.subtitleStreams).firstOrNull { it.pid == pid }?.language?.takeIf { it.isNotBlank() }

    fun openClip(index: Int): RandomAccessSource = disc.fs.open(title.items[index].m2tsPath)

    companion object {
        const val SCHEME = "p2160disc"
    }
}

/**
 * Реестр открытых дисков. ISO читается через кэширующий источник (SMB/HTTP — сеть с задержкой),
 * папка BDMV — как обычные файлы.
 */
object DiscSessions {
    private val sessions = ConcurrentHashMap<String, DiscSession>()
    private val counter = AtomicInteger()

    /** Похоже ли на диск: образ .iso или папка (без расширения), в которой может лежать BDMV. */
    fun isCandidate(uri: Uri): Boolean {
        val name = uri.lastPathSegment.orEmpty()
        return name.endsWith(".iso", ignoreCase = true) ||
            name.equals("BDMV", ignoreCase = true) ||
            (uri.scheme in setOf("smb", "file") && !name.contains('.'))
    }

    /** Открывает диск и основной фильм. Блокирующий вызов — только с фонового потока. */
    fun open(context: Context, uri: Uri, headers: Map<String, String> = emptyMap()): DiscSession {
        val fs: DiscFileSystem = if (uri.lastPathSegment.orEmpty().endsWith(".iso", ignoreCase = true)) {
            UdfFileSystem(CachedRandomAccessSource(RandomAccessSources.open(context, uri, headers)))
        } else {
            DirectoryFileSystem(directoryProvider(context, uri))
        }
        val disc = BlurayDisc.open(fs)
        val title = disc.mainTitle() ?: run { disc.close(); throw BlurayFormatException("no playable titles") }
        val session = DiscSession("d${counter.incrementAndGet()}", uri, disc, title)
        sessions[session.id] = session
        return session
    }

    operator fun get(uri: Uri): DiscSession? = uri.host?.let(sessions::get)

    fun close(session: DiscSession) {
        sessions.remove(session.id)
        runCatching { session.disc.close() }
    }

    private fun directoryProvider(context: Context, uri: Uri): DirectoryProvider = when (uri.scheme?.lowercase()) {
        "smb" -> SmbDirectoryProvider(context, SmbPath.fromUri(uri) ?: throw BlurayFormatException("bad uri"))
        "file" -> FileDirectoryProvider(File(uri.path!!))
        else -> throw BlurayFormatException("folders are supported only for file:// and smb://")
    }
}

/** Папка BDMV на SMB-сервере. */
class SmbDirectoryProvider(private val context: Context, private val root: SmbPath) : DirectoryProvider {
    private fun resolve(path: String): SmbPath =
        path.trim('/').split('/').filter { it.isNotEmpty() }.fold(root) { acc, part -> acc.child(part) }

    override fun list(path: String): List<DiscEntry>? = runCatching {
        SmbConnections.list(context, resolve(path)).map { DiscEntry(it.name, it.isDirectory, it.size) }
    }.getOrNull()

    override fun open(path: String): RandomAccessSource =
        CachedRandomAccessSource(SmbRandomAccessSource(SmbConnections.openRead(context, resolve(path))))

    override fun close() = Unit
}
