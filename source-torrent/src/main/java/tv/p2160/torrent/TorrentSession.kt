package tv.p2160.torrent

import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.AlertListener
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.TorrentStatus
import org.libtorrent4j.Vectors
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.MetadataReceivedAlert
import org.libtorrent4j.alerts.SaveResumeDataAlert
import org.libtorrent4j.swig.add_torrent_params
import org.libtorrent4j.swig.error_code
import org.libtorrent4j.swig.libtorrent
import org.libtorrent4j.swig.remove_flags_t
import org.libtorrent4j.swig.settings_pack
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Файл внутри торрента. */
data class TorrentFile(val index: Int, val path: String, val size: Long) {
    val name: String get() = path.substringAfterLast('/').substringAfterLast('\\')
    val extension: String get() = name.substringAfterLast('.', "").lowercase()
    val isVideo: Boolean get() = extension in VIDEO
    val isAudio: Boolean get() = extension in AUDIO
    val isSubtitle: Boolean get() = extension in SUBTITLE
    val isPlayable: Boolean get() = isVideo || isAudio

    companion object {
        val VIDEO = setOf("mkv", "mp4", "m4v", "avi", "mov", "wmv", "ts", "m2ts", "mts", "webm", "mpg", "mpeg", "flv", "vob", "3gp", "ogv", "divx", "rmvb", "hevc")
        val AUDIO = setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "ape", "wv", "dts", "ac3", "mka", "alac")
        val SUBTITLE = setOf("srt", "ass", "ssa", "vtt", "ttml", "dfxp")
    }
}

/** Настройки сессии libtorrent. */
data class SessionConfig(
    val maxConnections: Int = 200,
    /** Байт/с, 0 — без ограничения. */
    val uploadLimit: Int = 0,
    val downloadLimit: Int = 0,
    /**
     * POSIX-ввод/вывод вместо mmap. По умолчанию libtorrent сам выбирает mmap на 64-бит и
     * POSIX на 32-бит — это и оставляем.
     */
    val posixDiskIo: Boolean = false,
    val userAgent: String = "2160Player/0.1 libtorrent/2.0",
    /** Публичные трекеры, добавляемые к magnet-ссылкам (ускоряют получение метаданных). */
    val extraTrackers: List<String> = DEFAULT_TRACKERS,
) {
    companion object {
        val DEFAULT_TRACKERS = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.stealth.si:80/announce",
            "udp://tracker.torrent.eu.org:451/announce",
            "udp://exodus.desync.com:6969/announce",
            "udp://open.demonii.com:1337/announce",
        )
    }
}

/** Состояние торрента для UI. */
data class TorrentStats(
    val id: String,
    val name: String,
    val hasMetadata: Boolean,
    val state: State,
    val paused: Boolean,
    val downloadRate: Int,
    val uploadRate: Int,
    val peers: Int,
    val seeds: Int,
    /** Скачано/нужно по выбранным файлам. */
    val wantedDone: Long,
    val wanted: Long,
    /** Основной (выбранный пользователем) файл, -1 — не выбран. */
    val primaryFile: Int,
    val primaryProgress: Float,
    /** Сколько байт подряд скачано от позиции чтения плеера. */
    val bufferedBytes: Long,
    val readers: Int,
    val error: String?,
) {
    enum class State { METADATA, CHECKING, DOWNLOADING, FINISHED, ERROR }
}

/**
 * Сессия libtorrent и потоковое чтение файлов. Без зависимостей от Android — работает и на
 * десктопной JVM (интеграционный тест).
 *
 * Чтение: [openStream] возвращает [FileStream], чей [FileStream.read] блокируется, пока нужный
 * кусок не скачан. Позиции всех открытых потоков определяют дедлайны кусков (см. [PiecePlanner]).
 */
class TorrentSession(
    @Volatile private var config: SessionConfig = SessionConfig(),
    private val callbacks: Callbacks = object : Callbacks {},
) : Closeable {

    interface Callbacks {
        /** Получены метаданные; [torrent] — .torrent-файл (если удалось собрать). */
        fun onMetadata(id: String, torrent: ByteArray?) {}
        /** Готовы данные быстрого возобновления (вместе со словарём info). */
        fun onResumeData(id: String, data: ByteArray) {}
    }

    private val manager = SessionManager(false)
    private val entries = ConcurrentHashMap<String, Entry>()
    private val pieceLock = ReentrantLock()
    private val pieceArrived = pieceLock.newCondition()
    private val readerIds = AtomicLong()

    private val alerts = object : AlertListener {
        override fun types(): IntArray = intArrayOf(
            AlertType.PIECE_FINISHED.swig(),
            AlertType.METADATA_RECEIVED.swig(),
            AlertType.SAVE_RESUME_DATA.swig(),
            AlertType.TORRENT_REMOVED.swig(),
        )

        override fun alert(alert: Alert<*>) {
            when (alert) {
                is MetadataReceivedAlert -> onMetadataReceived(alert.handle())
                is SaveResumeDataAlert -> runCatching {
                    val id = alert.handle().infoHash().toHex()
                    callbacks.onResumeData(id, AddTorrentParams.writeResumeDataBuf(alert.params()))
                }
                else -> Unit
            }
            pieceLock.withLock { pieceArrived.signalAll() }
        }
    }

    val isRunning: Boolean get() = manager.isRunning

    @Synchronized
    fun start() {
        if (manager.isRunning) return
        val params = SessionParams(settingsPack(config))
        if (config.posixDiskIo) params.setPosixDiskIO()
        manager.addListener(alerts)
        manager.start(params)
    }

    fun applyConfig(newConfig: SessionConfig) {
        config = newConfig
        if (manager.isRunning) manager.applySettings(settingsPack(newConfig))
    }

    /** Узлов DHT (для экрана ожидания метаданных). */
    fun dhtNodes(): Long = if (manager.isRunning) manager.dhtNodes() else 0

    fun downloadRate(): Long = if (manager.isRunning) manager.downloadRate() else 0
    fun uploadRate(): Long = if (manager.isRunning) manager.uploadRate() else 0

    private fun settingsPack(c: SessionConfig) = SettingsPack().apply {
        setEnableDht(true)
        setEnableLsd(true)
        setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), true)
        setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), true)
        setBoolean(settings_pack.bool_types.announce_to_all_trackers.swigValue(), true)
        setBoolean(settings_pack.bool_types.announce_to_all_tiers.swigValue(), true)
        setString(settings_pack.string_types.user_agent.swigValue(), c.userAgent)
        connectionsLimit(c.maxConnections.coerceAtLeast(10))
        uploadRateLimit(c.uploadLimit.coerceAtLeast(0))
        downloadRateLimit(c.downloadLimit.coerceAtLeast(0))
        activeDownloads(8)
        activeSeeds(4)
        // Для потока важнее быстро отказаться от медленного пира, чем ждать его.
        setInteger(settings_pack.int_types.piece_timeout.swigValue(), 10)
        setInteger(settings_pack.int_types.request_timeout.swigValue(), 20)
    }

    // ---------------------------------------------------------------- добавление

    /** Добавляет magnet. Возвращает id (hex info-hash). Повторное добавление вернёт тот же id. */
    fun addMagnet(uri: String, saveDir: File): String {
        val ec = error_code()
        val p = libtorrent.parse_magnet_uri(uri, ec)
        if (ec.value() != 0) throw IllegalArgumentException("bad magnet: ${ec.message()}")
        val wrapper = AddTorrentParams(p)
        val trackers = wrapper.trackers
        val extra = config.extraTrackers.filter { it !in trackers }
        if (extra.isNotEmpty()) wrapper.trackers = trackers + extra
        return add(p, saveDir)
    }

    /** Добавляет .torrent-файл (bencode). */
    fun addTorrentFile(bytes: ByteArray, saveDir: File): String {
        val ti = TorrentInfo.bdecode(bytes)
        val p = add_torrent_params()
        p.set_ti(ti.swig())
        // Ничего не качаем, пока пользователь не выбрал файл.
        AddTorrentParams(p).filePriorities(Array(ti.numFiles()) { Priority.IGNORE })
        return add(p, saveDir)
    }

    /** Восстанавливает торрент из данных возобновления (с прогрессом и приоритетами). */
    fun addResumeData(data: ByteArray, saveDir: File): String {
        val ec = error_code()
        val p = libtorrent.read_resume_data_ex(Vectors.bytes2byte_vector(data), ec)
        if (ec.value() != 0) throw IllegalArgumentException("bad resume data: ${ec.message()}")
        return add(p, saveDir)
    }

    private fun add(p: add_torrent_params, saveDir: File): String {
        start()
        saveDir.mkdirs()
        p.setSave_path(saveDir.absolutePath)
        val flags = p.flags
            .or_(TorrentFlags.DEFAULT_DONT_DOWNLOAD)
            .and_(TorrentFlags.AUTO_MANAGED.inv())
            .and_(TorrentFlags.PAUSED.inv())
            .and_(TorrentFlags.UPLOAD_MODE.inv())
        p.flags = flags
        val ec = error_code()
        val th = manager.swig().add_torrent(p, ec)
        if (ec.value() != 0 || th == null || !th.is_valid()) throw IOException("add_torrent: ${ec.message()}")
        val handle = TorrentHandle(th)
        val id = handle.infoHash().toHex()
        entries.computeIfAbsent(id) { Entry(id, handle) }
        if (handle.torrentFile() != null) onMetadataReceived(handle)
        return id
    }

    private fun onMetadataReceived(handle: TorrentHandle) {
        val id = handle.infoHash().toHex()
        val entry = entries[id] ?: return
        if (entry.metadataReported) return
        entry.metadataReported = true
        val bytes = runCatching {
            val ti = handle.torrentFile() ?: return@runCatching null
            val p = add_torrent_params()
            p.set_ti(ti.swig())
            Vectors.byte_vector2bytes(libtorrent.write_torrent_file_buf_ex(p))
        }.getOrNull()
        runCatching { callbacks.onMetadata(id, bytes) }
        requestResumeData(id)
    }

    fun contains(id: String): Boolean = entries.containsKey(id)

    fun ids(): Set<String> = entries.keys.toSet()

    fun hasMetadata(id: String): Boolean = entries[id]?.handle?.torrentFile() != null

    /** Список файлов; null — метаданные ещё не получены. */
    fun files(id: String): List<TorrentFile>? {
        val ti = entries[id]?.handle?.torrentFile() ?: return null
        val fs = ti.files()
        return (0 until fs.numFiles())
            .filterNot { fs.padFileAt(it) }
            .map { TorrentFile(it, fs.filePath(it), fs.fileSize(it)) }
    }

    fun name(id: String): String? = entries[id]?.handle?.let { h -> h.torrentFile()?.name() ?: runCatching { h.name }.getOrNull() }

    fun savePath(id: String, fileIndex: Int): File? {
        val h = entries[id]?.handle ?: return null
        val ti = h.torrentFile() ?: return null
        return File(h.savePath(), ti.files().filePath(fileIndex))
    }

    /** Ждёт метаданные. Блокирующий, прерываемый. */
    fun awaitMetadata(id: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val entry = entries[id] ?: return false
            if (entry.handle.torrentFile() != null) return true
            waitForSignal(250)
        }
        return hasMetadata(id)
    }

    // ---------------------------------------------------------------- выбор файла и приоритеты

    /**
     * Делает файл «основным»: качаем его последовательно, начало и хвост — в первую очередь.
     * [exclusive] — остальные файлы перестают качаться.
     */
    fun select(id: String, fileIndex: Int, exclusive: Boolean = true) {
        val entry = entries[id] ?: throw IOException("torrent $id is not active")
        val ti = entry.handle.torrentFile() ?: throw IOException("no metadata yet")
        require(fileIndex in 0 until ti.numFiles()) { "bad file index $fileIndex" }
        synchronized(entry) {
            if (exclusive) {
                val prios = Array(ti.numFiles()) { Priority.IGNORE }
                prios[fileIndex] = Priority.DEFAULT
                entry.handle.prioritizeFiles(prios)
                entry.selected.clear()
            } else if (fileIndex !in entry.selected) {
                entry.handle.filePriority(fileIndex, Priority.DEFAULT)
            }
            entry.selected += fileIndex
            entry.primary = fileIndex
            activate(entry)
            replan(entry)
        }
    }

    private fun activate(entry: Entry) {
        entry.handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
        entry.handle.unsetFlags(TorrentFlags.AUTO_MANAGED)
        entry.handle.unsetFlags(TorrentFlags.UPLOAD_MODE)
        entry.handle.resume()
        entry.lastActiveAt = System.currentTimeMillis()
        announceNow(entry)
    }

    /**
     * Сразу спросить трекеры и DHT о пирах. Восстановленный из данных возобновления торрент помнит время
     * прошлого анонса и иначе ждёт интервал трекера (15–30 мин) — всё это время «пиров: 0».
     */
    private fun announceNow(entry: Entry) {
        runCatching { entry.handle.forceReannounce(0, -1, TorrentHandle.IGNORE_MIN_INTERVAL) }
        runCatching { entry.handle.forceDHTAnnounce() }
    }

    /** Пересчёт дедлайнов по текущим позициям чтения. Вызывается при смене куска у читателя. */
    private fun replan(entry: Entry) = synchronized(entry) {
        if (entry.removed) return
        val ti = entry.handle.torrentFile() ?: return
        val fs = ti.files()
        // Одно обращение за битовым полем вместо havePiece на каждый кусок окна.
        val bits = runCatching { entry.handle.status(STATUS_FLAGS).pieces() }.getOrNull()
        val have: (Int) -> Boolean = if (bits != null && bits.size() > 0) {
            { it < bits.size() && bits.getBit(it) }
        } else entry.handle::havePiece
        runCatching { entry.handle.clearPieceDeadlines() }
        var sequentialFrom = Int.MAX_VALUE
        var sequentialTo = -1
        for (file in entry.selected) {
            val span = FileSpan(fs.fileOffset(file), fs.fileSize(file), ti.pieceLength())
            val positions = entry.readers.values.filter { it.file == file }.map { it.position }
            val plan = PiecePlanner.deadlines(
                span, positions,
                wantHead = positions.isEmpty() && file == entry.primary,
                wantTail = file == entry.primary || positions.isNotEmpty(),
                have = have,
            )
            plan.forEach { (piece, ms) -> entry.handle.setPieceDeadline(piece, ms) }
            if (file == entry.primary) {
                sequentialFrom = positions.minOfOrNull { span.pieceAt(it) } ?: span.firstPiece
                sequentialTo = span.lastPiece
            }
        }
        // Последовательная докачка — от позиции чтения, а не от начала файла (после перемотки).
        if (sequentialTo >= 0) runCatching { entry.handle.setSequentialRange(sequentialFrom, sequentialTo) }
    }

    // ---------------------------------------------------------------- чтение

    /** Открывает поток чтения файла; файл добавляется в выбранные, если ещё не был. */
    fun openStream(id: String, fileIndex: Int): FileStream {
        val entry = entries[id] ?: throw IOException("torrent $id is not active")
        val ti = entry.handle.torrentFile() ?: throw IOException("no metadata yet")
        if (fileIndex !in 0 until ti.numFiles()) throw IOException("bad file index $fileIndex")
        val fs0 = ti.files()
        synchronized(entry) {
            if (fileIndex !in entry.selected) {
                entry.handle.filePriority(fileIndex, Priority.DEFAULT)
                entry.selected += fileIndex
            }
            // Основной файл — последний открытый видео/аудио (следующая серия плейлиста), не субтитры.
            if (entry.primary < 0 || TorrentFile(fileIndex, fs0.filePath(fileIndex), 0).isPlayable) entry.primary = fileIndex
            activate(entry)
        }
        val fs = ti.files()
        val span = FileSpan(fs.fileOffset(fileIndex), fs.fileSize(fileIndex), ti.pieceLength())
        val path = File(entry.handle.savePath(), fs.filePath(fileIndex))
        return FileStream(entry, fileIndex, span, path)
    }

    inner class FileStream internal constructor(
        private val entry: Entry,
        val fileIndex: Int,
        private val span: FileSpan,
        val path: File,
    ) : Closeable {
        val size: Long get() = span.size
        private val id = readerIds.incrementAndGet()
        private val reader = Reader(fileIndex)
        private var file: RandomAccessFile? = null
        @Volatile private var closed = false

        init {
            entry.readers[id] = reader
            replan(entry)
        }

        /**
         * Читает до [length] байт с позиции [position] (не дальше конца куска).
         * Блокируется, пока кусок не скачан; [stallTimeoutMs] — сколько ждать при полном
         * отсутствии загрузки по торренту. -1 — конец файла.
         */
        fun read(position: Long, buffer: ByteArray, offset: Int, length: Int, stallTimeoutMs: Long = 90_000): Int {
            if (closed) throw IOException("stream closed")
            if (position >= span.size) return -1
            if (length == 0) return 0
            val piece = span.pieceAt(position)
            reader.position = position
            if (reader.piece != piece) {
                reader.piece = piece
                replan(entry)
            }
            awaitPiece(piece, stallTimeoutMs)
            val n = minOf(length.toLong(), span.pieceEnd(piece) - position).toInt()
            val raf = file ?: RandomAccessFile(path, "r").also { file = it }
            raf.seek(position)
            var total = 0
            while (total < n) {
                val r = raf.read(buffer, offset + total, n - total)
                if (r < 0) break
                total += r
            }
            if (total <= 0) throw IOException("short read at $position in ${path.name}")
            entry.lastActiveAt = System.currentTimeMillis()
            return total
        }

        /** Есть ли все байты диапазона (без ожидания). */
        fun isAvailable(position: Long, length: Long): Boolean =
            span.pieces(position, length).all(entry.handle::havePiece)

        private fun awaitPiece(piece: Int, stallTimeoutMs: Long) {
            if (entry.handle.havePiece(piece)) return
            var lastDone = -1L
            var lastCheck = 0L
            var stallSince = System.currentTimeMillis()
            while (!entry.handle.havePiece(piece)) {
                if (closed) throw InterruptedIOException("stream closed")
                if (entry.removed) throw IOException("torrent removed")
                val now = System.currentTimeMillis()
                if (now - lastCheck >= 2_000) {
                    lastCheck = now
                    val done = runCatching { entry.handle.status().totalPayloadDownload() }.getOrDefault(lastDone)
                    if (done != lastDone) {
                        lastDone = done
                        stallSince = now
                    }
                }
                if (now - stallSince > stallTimeoutMs) throw SocketTimeoutException("no data for piece $piece")
                waitForSignal(250)
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            entry.readers.remove(id)
            runCatching { file?.close() }
            file = null
            if (entry.readers.isEmpty()) requestResumeData(entry.id)
            replan(entry)
            pieceLock.withLock { pieceArrived.signalAll() }
        }
    }

    private fun waitForSignal(ms: Long) {
        try {
            pieceLock.withLock { pieceArrived.await(ms, TimeUnit.MILLISECONDS) }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("interrupted")
        }
    }

    // ---------------------------------------------------------------- состояние и управление

    fun stats(id: String): TorrentStats? {
        val entry = entries[id] ?: return null
        val s: TorrentStatus = runCatching { entry.handle.status(STATUS_FLAGS) }.getOrNull() ?: return null
        val ti = entry.handle.torrentFile()
        val primary = entry.primary
        var primaryProgress = 0f
        var buffered = 0L
        if (ti != null && primary >= 0) {
            val fs = ti.files()
            val size = fs.fileSize(primary)
            val progress = runCatching { entry.handle.fileProgress(TorrentHandle.PIECE_GRANULARITY) }.getOrNull()
            primaryProgress = if (size > 0 && progress != null && primary < progress.size) (progress[primary].toDouble() / size).toFloat() else 0f
            val pos = entry.readers.values.filter { it.file == primary }.minOfOrNull { it.position }
            if (pos != null) {
                val bits = s.pieces()
                val span = FileSpan(fs.fileOffset(primary), size, ti.pieceLength())
                buffered = PiecePlanner.contiguousBytes(span, pos) { it < bits.size() && bits.getBit(it) }
            }
        }
        val error = s.errorCode()?.takeIf { it.value != 0 }?.message
        val state = when {
            error != null -> TorrentStats.State.ERROR
            !s.hasMetadata() || s.state() == TorrentStatus.State.DOWNLOADING_METADATA -> TorrentStats.State.METADATA
            s.state() == TorrentStatus.State.CHECKING_FILES || s.state() == TorrentStatus.State.CHECKING_RESUME_DATA -> TorrentStats.State.CHECKING
            s.isFinished || s.isSeeding -> TorrentStats.State.FINISHED
            else -> TorrentStats.State.DOWNLOADING
        }
        return TorrentStats(
            id = id,
            name = ti?.name() ?: s.name().orEmpty().ifBlank { id },
            hasMetadata = s.hasMetadata(),
            state = state,
            paused = s.flags().and_(TorrentFlags.PAUSED).non_zero(),
            downloadRate = s.downloadPayloadRate(),
            uploadRate = s.uploadPayloadRate(),
            peers = s.numPeers(),
            seeds = s.numSeeds(),
            wantedDone = s.totalWantedDone(),
            wanted = s.totalWanted(),
            primaryFile = primary,
            primaryProgress = primaryProgress,
            bufferedBytes = buffered,
            readers = entry.readers.size,
            error = error,
        )
    }

    /** Время последнего чтения/выбора; для автопаузы неиспользуемых торрентов. */
    fun lastActiveAt(id: String): Long = entries[id]?.lastActiveAt ?: 0
    fun readerCount(id: String): Int = entries[id]?.readers?.size ?: 0

    fun pause(id: String) {
        val e = entries[id] ?: return
        e.handle.pause()
        requestResumeData(id)
    }

    /** Остановить раздачу по просьбе пользователя: ни загрузки, ни отдачи. */
    fun stop(id: String) {
        val e = entries[id] ?: return
        e.keepActive = false
        pause(id)
    }

    /** Продолжить раздачу по просьбе пользователя: качает и раздаёт, автопауза без просмотра её не трогает. */
    fun start(id: String) {
        val e = entries[id] ?: return
        e.keepActive = true
        e.handle.unsetFlags(TorrentFlags.AUTO_MANAGED)
        e.handle.unsetFlags(TorrentFlags.UPLOAD_MODE)
        e.handle.resume()
        e.lastActiveAt = System.currentTimeMillis()
        announceNow(e)
    }

    /** Пользователь сам продолжил раздачу — не ставить её на автопаузу. */
    fun keepActive(id: String): Boolean = entries[id]?.keepActive == true

    fun requestResumeData(id: String) {
        val e = entries[id] ?: return
        if (e.handle.torrentFile() == null) return
        runCatching { e.handle.saveResumeData(TorrentHandle.SAVE_INFO_DICT) }
    }

    /** Убирает торрент из сессии; [deleteFiles] — вместе со скачанными данными. */
    fun remove(id: String, deleteFiles: Boolean) {
        val e = entries.remove(id) ?: return
        e.removed = true
        pieceLock.withLock { pieceArrived.signalAll() }
        runCatching {
            if (deleteFiles) manager.remove(e.handle, SessionHandle.DELETE_FILES) else manager.remove(e.handle, remove_flags_t())
        }
    }

    override fun close() {
        entries.keys.forEach(::requestResumeData)
        entries.values.forEach { it.removed = true }
        pieceLock.withLock { pieceArrived.signalAll() }
        manager.removeListener(alerts)
        manager.stop()
        entries.clear()
    }

    private companion object {
        // status() без флагов не заполняет битовое поле кусков (и кэширует результат) —
        // getBit на пустом поле роняет нативный код.
        val STATUS_FLAGS = TorrentHandle.QUERY_PIECES.or_(TorrentHandle.QUERY_NAME)
    }

    internal class Reader(val file: Int) {
        @Volatile var position: Long = 0
        @Volatile var piece: Int = -1
    }

    inner class Entry internal constructor(val id: String, val handle: TorrentHandle) {
        internal val readers = ConcurrentHashMap<Long, Reader>()
        internal val selected = LinkedHashSet<Int>()
        @Volatile internal var primary = -1
        @Volatile internal var removed = false
        @Volatile internal var metadataReported = false
        @Volatile internal var lastActiveAt = System.currentTimeMillis()
        @Volatile internal var keepActive = false
    }
}
