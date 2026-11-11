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
    private val session: TorrentSession by lazy {
        TorrentNatives.ensureLoaded()
        TorrentSession(SessionConfig(userAgent = "2160Player/0.2.26 libtorrent/2.0"))
    }

    fun start() {
        saveDir.mkdirs()
        session.start()
    }

    val running: Boolean get() = session.isRunning

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
        return TorrentByteSource(session.openStream(id, fileIndex))
    }

    override fun close() {
        session.close()
    }
}

class TorrentByteSource(private val stream: TorrentSession.FileStream) : ByteSource {
    override val length: Long = stream.size
    private val lock = Any()

    override fun read(offset: Long, dst: ByteArray, dstOff: Int, len: Int): Int = synchronized(lock) {
        stream.read(offset, dst, dstOff, len, 120_000)
    }

    override fun close() {
        stream.close()
    }
}
