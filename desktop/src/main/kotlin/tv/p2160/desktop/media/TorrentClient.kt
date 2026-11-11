package tv.p2160.desktop.media

import tv.p2160.torrent.SessionConfig
import tv.p2160.torrent.TorrentFile
import tv.p2160.torrent.TorrentSession
import tv.p2160.torrent.TorrentStats
import java.io.File

/**
 * Тонкая обёртка над [TorrentSession] из модуля source-torrent.
 * Чтение файла блокируется, пока libtorrent не скачает нужный кусок, и этот поток
 * отдаётся плееру через локальный HTTP. Так ролик идёт, не дожидаясь всего файла.
 */
class TorrentClient(private val saveDir: File) : AutoCloseable {
    @Volatile private var active = false
    private val session: TorrentSession by lazy {
        TorrentNatives.ensureLoaded()
        TorrentSession(SessionConfig(userAgent = "2160Player/0.2.26 libtorrent/2.0"))
    }

    fun start() {
        saveDir.mkdirs()
        session.start()
        active = true
    }

    val running: Boolean get() = active

    fun addMagnet(magnet: String): String = session.addMagnet(magnet, saveDir)

    fun addTorrentFile(bytes: ByteArray): String = session.addTorrentFile(bytes, saveDir)

    fun awaitMetadata(id: String, timeoutMs: Long = 1_000): Boolean = session.awaitMetadata(id, timeoutMs)

    fun files(id: String): List<TorrentFile>? = session.files(id)

    fun name(id: String): String? = session.name(id)

    fun stats(id: String): TorrentStats? = session.stats(id)

    fun ids(): Set<String> = session.ids()

    fun remove(id: String) = session.remove(id, deleteFiles = true)

    fun open(id: String, fileIndex: Int): TorrentByteSource {
        session.select(id, fileIndex, true)
        val known = session.files(id)?.firstOrNull { it.index == fileIndex }?.size
        val length = known ?: session.openStream(id, fileIndex).use { it.size }
        return TorrentByteSource(length) { session.openStream(id, fileIndex) }
    }

    override fun close() {
        session.close()
    }
}

/**
 * Each HTTP request gets its own libtorrent stream. One shared lock around
 * [TorrentSession.FileStream.read] would stall every other Range request,
 * including a seek, for as long as the first piece takes to arrive.
 */
class TorrentByteSource(
    override val length: Long,
    private val openStream: () -> TorrentSession.FileStream,
) : ByteSource {
    private val readers = ReaderSet()

    override fun read(offset: Long, dst: ByteArray, dstOff: Int, len: Int): Int =
        error("Каждый запрос должен открыть свой поток через openReader")

    override fun openReader(): ByteSource {
        val reader = StreamReader(openStream())
        readers.track(reader)
        return reader
    }

    override fun close() {
        readers.close()
    }
}

/**
 * Readers opened for HTTP stay reachable until the published source is closed.
 * Closing the parent closes them too, which wakes a read blocked on a missing piece.
 */
internal class ReaderSet {
    private val readers = java.util.Collections.synchronizedSet(mutableSetOf<AutoCloseable>())
    @Volatile private var closed = false

    fun track(reader: AutoCloseable) {
        val closeNow = synchronized(readers) {
            if (closed) true else {
                readers.add(reader)
                false
            }
        }
        if (closeNow) reader.close()
    }

    fun close() {
        val snapshot = synchronized(readers) {
            closed = true
            readers.toList().also { readers.clear() }
        }
        snapshot.forEach { runCatching { it.close() } }
    }
}

private class StreamReader(private val stream: TorrentSession.FileStream) : ByteSource {
    override val length: Long = stream.size

    override fun read(offset: Long, dst: ByteArray, dstOff: Int, len: Int): Int =
        stream.read(offset, dst, dstOff, len, 120_000)

    override fun close() {
        stream.close()
    }
}
