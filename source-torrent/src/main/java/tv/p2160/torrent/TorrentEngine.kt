package tv.p2160.torrent

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.libtorrent4j.TorrentInfo
import tv.p2160.core.api.ExternalSubtitle
import tv.p2160.core.api.MediaEntry
import tv.p2160.core.api.PlaybackRequest
import tv.p2160.core.source.RoutingDataSource
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Торрент для UI: сохранённая запись + живое состояние, если он сейчас в сессии. */
data class TorrentItem(val stored: StoredTorrent, val stats: TorrentStats?) {
    val id: String get() = stored.id
    val name: String get() = stats?.name?.takeIf { stats.hasMetadata } ?: stored.name
}

/**
 * Точка входа модуля: сессия libtorrent, список торрентов, выбор файла, URI для плеера,
 * очистка кэша. Сессия запускается лениво — при первом добавлении/открытии торрента.
 *
 * Плеер читает файлы через `torrent://<id>/<fileIndex>/<имя>` — см. [install] и [TorrentDataSource].
 */
class TorrentEngine private constructor(context: Context) {
    private val appContext = context.applicationContext
    val settings = TorrentSettings(appContext)
    private val store = TorrentStore(File(appContext.filesDir, "torrents"))
    private val dataRoot: File = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "torrent-data")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val session: TorrentSession = TorrentSession(settings.state.value.sessionConfig(), object : TorrentSession.Callbacks {
        override fun onMetadata(id: String, torrent: ByteArray?) {
            torrent?.let { runCatching { store.torrentFile(id).writeBytes(it) } }
            val files = session.files(id).orEmpty()
            store.update(id) { it.copy(name = session.name(id) ?: it.name, files = files, totalSize = files.sumOf(TorrentFile::size)) }
            refresh()
        }

        override fun onResumeData(id: String, data: ByteArray) {
            if (store[id]?.hasData == true) runCatching { store.resumeFile(id).writeBytes(data) }
        }
    })

    private val _torrents = MutableStateFlow(store.all().map { TorrentItem(it, null) }.sortedByDescending { it.stored.lastOpenedAt })
    /** Все известные торренты, свежие сверху. Обновляется раз в секунду, пока сессия работает. */
    val torrents: StateFlow<List<TorrentItem>> = _torrents.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    /** Ошибка запуска движка (например, нет нативной библиотеки под эту ABI). */
    val engineError: StateFlow<String?> = _error.asStateFlow()

    @Volatile private var ticker = false

    init {
        // Новые лимиты применяем к работающей сессии.
        scope.launch {
            settings.state.drop(1).collect { prefs ->
                runCatching { session.applyConfig(prefs.sessionConfig()) }
                cleanup()
            }
        }
    }

    // ---------------------------------------------------------------- запуск

    @Synchronized
    private fun ensureStarted() {
        if (session.isRunning) return
        try {
            session.start()
        } catch (t: Throwable) {
            _error.value = t.message ?: t.javaClass.simpleName
            throw IOException("torrent engine failed to start: ${t.message}", t)
        }
        _error.value = null
        if (!ticker) {
            ticker = true
            scope.launch { tick() }
        }
        scope.launch { cleanup() }
    }

    private suspend fun tick() {
        var n = 0
        while (scope.isActive) {
            runCatching {
                refresh()
                val now = System.currentTimeMillis()
                session.ids().forEach { id ->
                    val stats = session.stats(id) ?: return@forEach
                    // Неиспользуемые торренты ставим на паузу — не качать фильм целиком «в фоне».
                    if (!stats.paused && stats.readers == 0 && now - session.lastActiveAt(id) > IDLE_PAUSE_MS) session.pause(id)
                    if (n % 15 == 0 && stats.hasMetadata) store.update(id) { it.copy(bytesDone = stats.wantedDone) }
                }
                if (n % 3600 == 0 && n > 0) cleanup()
            }
            n++
            delay(1_000)
        }
    }

    private fun refresh() {
        val live = session.ids().associateWith { session.stats(it) }
        _torrents.value = store.all()
            .map { TorrentItem(it, live[it.id]) }
            .sortedByDescending { it.stored.lastOpenedAt }
    }

    // ---------------------------------------------------------------- добавление

    /** Добавляет magnet (или «голый» info-hash). Возвращает id. */
    suspend fun addMagnet(text: String): String = withContext(Dispatchers.IO) {
        val magnet = MagnetLink.parse(text) ?: throw IllegalArgumentException("not a magnet link")
        val uri = if (text.trim().startsWith("magnet:", ignoreCase = true)) text.trim() else "magnet:?xt=urn:btih:${magnet.id}"
        ensureStarted()
        val id = magnet.id
        if (!session.contains(id) && !(store[id] != null && ensureActive(id))) session.addMagnet(uri, dataDir(id))
        val now = System.currentTimeMillis()
        store.update(id) { it.copy(lastOpenedAt = now) } ?: store.put(
            StoredTorrent(id, magnet.displayName ?: session.name(id) ?: id, uri, now, now)
        )
        // Метаданные могли уже быть (повторное добавление) — сохраним список файлов.
        session.files(id)?.let { files -> store.update(id) { it.copy(files = files, totalSize = files.sumOf(TorrentFile::size)) } }
        refresh()
        id
    }

    /** Добавляет .torrent-файл. */
    suspend fun addTorrentBytes(bytes: ByteArray): String = withContext(Dispatchers.IO) {
        ensureStarted()
        val info = runCatching { TorrentInfo.bdecode(bytes) }.getOrElse { throw IllegalArgumentException("not a .torrent file", it) }
        val id = info.infoHash().toHex()
        runCatching { store.torrentFile(id).writeBytes(bytes) }
        if (!session.contains(id) && !(store[id]?.hasData == true && ensureActive(id))) session.addTorrentFile(bytes, dataDir(id))
        val now = System.currentTimeMillis()
        val files = session.files(id).orEmpty()
        store.update(id) { it.copy(lastOpenedAt = now, files = files, totalSize = files.sumOf(TorrentFile::size)) }
            ?: store.put(StoredTorrent(id, info.name(), null, now, now, totalSize = files.sumOf(TorrentFile::size), files = files))
        refresh()
        id
    }

    /** magnet:, content://, file://, http(s):// на .torrent. */
    suspend fun addFromUri(uri: Uri): String = when (uri.scheme?.lowercase()) {
        "magnet" -> addMagnet(uri.toString())
        "http", "https" -> addTorrentBytes(withContext(Dispatchers.IO) { download(uri.toString()) })
        else -> addTorrentBytes(withContext(Dispatchers.IO) {
            appContext.contentResolver.openInputStream(uri)?.use { it.readLimited(MAX_TORRENT_BYTES) }
                ?: throw IOException("cannot read $uri")
        })
    }

    private fun download(url: String): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.instanceFollowRedirects = true
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.use { it.readLimited(MAX_TORRENT_BYTES) }
        } finally {
            conn.disconnect()
        }
    }

    private fun java.io.InputStream.readLimited(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > limit) throw IOException("file too large")
        }
        return out.toByteArray()
    }

    /** Возвращает сохранённый торрент в сессию (после перезапуска приложения). */
    @Synchronized
    private fun ensureActive(id: String): Boolean {
        if (session.contains(id)) return true
        val stored = store[id] ?: return false
        ensureStarted()
        val dir = dataDir(id)
        val resume = store.resumeFile(id).takeIf { stored.hasData && it.exists() }?.readBytes()
        val torrent = store.torrentFile(id).takeIf { it.exists() }?.readBytes()
        val ok = (resume != null && runCatching { session.addResumeData(resume, dir) }.isSuccess) ||
            (torrent != null && runCatching { session.addTorrentFile(torrent, dir) }.isSuccess) ||
            (stored.magnet != null && runCatching { session.addMagnet(stored.magnet, dir) }.isSuccess)
        if (ok) refresh()
        return ok
    }

    // ---------------------------------------------------------------- метаданные и файлы

    /** Ждёт метаданные (блокирует корутину на IO). null — не дождались за [timeoutMs]. */
    suspend fun awaitFiles(id: String, timeoutMs: Long): List<TorrentFile>? = withContext(Dispatchers.IO) {
        if (!ensureActive(id)) throw IOException("unknown torrent $id")
        if (session.awaitMetadata(id, timeoutMs)) session.files(id) else null
    }

    /** Файлы торрента: из сессии или из сохранённой записи. */
    fun files(id: String): List<TorrentFile>? = session.files(id) ?: store[id]?.files?.takeIf { it.isNotEmpty() }

    fun dhtNodes(): Long = runCatching { session.dhtNodes() }.getOrDefault(0)

    /**
     * Выбирает файл для просмотра (остальные файлы перестают качаться) и возвращает запрос
     * для плеера: все видео торрента плейлистом, старт — с выбранного; субтитры из торрента
     * подключаются к видео с тем же базовым именем.
     */
    suspend fun prepare(id: String, fileIndex: Int): PlaybackRequest = withContext(Dispatchers.IO) {
        if (!ensureActive(id)) throw IOException("unknown torrent $id")
        if (!session.awaitMetadata(id, METADATA_TIMEOUT_MS)) throw IOException("no metadata")
        val files = session.files(id).orEmpty()
        val chosen = files.firstOrNull { it.index == fileIndex } ?: throw IOException("bad file index")
        cleanup(exceptId = id)
        session.select(id, fileIndex, exclusive = true)
        val now = System.currentTimeMillis()
        store.update(id) { it.copy(lastOpenedAt = now, primaryFile = fileIndex, hasData = true) }
        refresh()
        buildRequest(id, files, chosen)
    }

    private fun buildRequest(id: String, files: List<TorrentFile>, chosen: TorrentFile): PlaybackRequest {
        val subtitles = files.filter { it.isSubtitle }
        val playlist = if (chosen.isVideo) files.filter { it.isVideo }.sortedWith(NaturalOrder) else listOf(chosen)
        val videos = playlist.size
        val items = playlist.map { f ->
            val base = f.name.substringBeforeLast('.').lowercase()
            val subs = subtitles.filter { s -> videos == 1 || s.name.lowercase().startsWith(base) }
            MediaEntry(
                uri = uriFor(id, f),
                title = f.name.substringBeforeLast('.'),
                subtitles = subs.map { s -> ExternalSubtitle(uriFor(id, s), s.name) },
            )
        }
        return PlaybackRequest(items, startIndex = playlist.indexOf(chosen).coerceAtLeast(0))
    }

    /** Открывает поток для [TorrentDataSource]. Блокирующий, с потока загрузчика плеера. */
    internal fun openStream(id: String, fileIndex: Int): TorrentSession.FileStream {
        if (!ensureActive(id)) throw IOException("unknown torrent $id")
        if (!session.awaitMetadata(id, METADATA_TIMEOUT_MS)) throw IOException("no metadata for $id")
        val now = System.currentTimeMillis()
        store.update(id) { it.copy(lastOpenedAt = now, hasData = true) }
        return session.openStream(id, fileIndex)
    }

    // ---------------------------------------------------------------- удаление и очистка

    /** Удаляет торрент из списка; [deleteFiles] — вместе со скачанными данными. */
    suspend fun remove(id: String, deleteFiles: Boolean) = withContext(Dispatchers.IO) {
        session.remove(id, deleteFiles)
        if (deleteFiles) deleteDataDir(id)
        store.delete(id)
        refresh()
    }

    /** Удаляет только скачанные данные, запись (метаданные) остаётся. */
    suspend fun deleteData(id: String) = withContext(Dispatchers.IO) { dropData(id) }

    private fun dropData(id: String) {
        session.remove(id, deleteFiles = true)
        deleteDataDir(id)
        store.resumeFile(id).delete()
        store.update(id) { it.copy(hasData = false, bytesDone = 0) }
        refresh()
    }

    private fun deleteDataDir(id: String) {
        val dir = dataDir(id)
        // libtorrent удаляет файлы асинхронно — добиваем остатки с небольшой задержкой.
        scope.launch {
            repeat(3) {
                delay(1_500)
                if (dir.exists()) dir.deleteRecursively()
            }
        }
    }

    @Synchronized
    private fun cleanup(exceptId: String? = null) {
        val now = System.currentTimeMillis()
        val items = store.all().map { t ->
            val active = t.id == exceptId || session.readerCount(t.id) > 0 ||
                (session.contains(t.id) && now - session.lastActiveAt(t.id) < IDLE_PAUSE_MS)
            CleanupPolicy.Item(t.id, t.lastOpenedAt, if (t.hasData) maxOf(t.bytesDone, 1) else 0, active)
        }
        settings.state.value.cleanupPolicy().plan(items, now).forEach { (id, action) ->
            runCatching {
                when (action) {
                    CleanupPolicy.Action.REMOVE -> {
                        session.remove(id, deleteFiles = true)
                        deleteDataDir(id)
                        store.delete(id)
                    }
                    CleanupPolicy.Action.DELETE_DATA -> dropData(id)
                }
            }
        }
        // Папки без записи в индексе (остались после сбоя).
        val known = store.all().map { it.id }.toSet()
        dataRoot.listFiles()?.filter { it.isDirectory && it.name !in known && !session.contains(it.name) }?.forEach { it.deleteRecursively() }
        refresh()
    }

    private fun dataDir(id: String) = File(dataRoot, id)

    companion object {
        const val SCHEME = "torrent"
        private const val MAX_TORRENT_BYTES = 16 * 1024 * 1024
        private const val METADATA_TIMEOUT_MS = 120_000L
        /** Торрент без чтения дольше этого времени ставится на паузу. */
        const val IDLE_PAUSE_MS = 10 * 60 * 1000L

        @Volatile private var instance: TorrentEngine? = null

        fun get(context: Context): TorrentEngine = instance ?: synchronized(this) {
            instance ?: TorrentEngine(context).also { instance = it }
        }

        /**
         * Регистрирует схему `torrent://` в плеере. Вызывать в Application.onCreate — тогда
         * и «Продолжить просмотр» из истории откроет торрент. Нативную сессию не запускает.
         */
        fun install(context: Context) {
            RoutingDataSource.registerScheme(SCHEME, TorrentDataSource.Factory(context.applicationContext))
        }

        fun uriFor(id: String, file: TorrentFile): Uri =
            Uri.Builder().scheme(SCHEME).authority(id).appendPath(file.index.toString()).appendPath(file.name).build()

        /** (id, индекс файла) из `torrent://` URI. */
        fun parseUri(uri: Uri): Pair<String, Int>? {
            if (uri.scheme?.lowercase() != SCHEME) return null
            val id = uri.authority?.lowercase()?.takeIf { it.matches(Regex("[0-9a-f]{40}")) } ?: return null
            val index = uri.pathSegments.firstOrNull()?.toIntOrNull() ?: return null
            return id to index
        }
    }
}

/** Естественная сортировка имён: «S01E2» < «S01E10». */
object NaturalOrder : Comparator<TorrentFile> {
    private val chunk = Regex("\\d+|\\D+")
    override fun compare(a: TorrentFile, b: TorrentFile): Int {
        val x = chunk.findAll(a.path.lowercase()).map { it.value }.toList()
        val y = chunk.findAll(b.path.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(x.size, y.size)) {
            val p = x[i]
            val q = y[i]
            val c = if (p[0].isDigit() && q[0].isDigit()) {
                p.trimStart('0').length.compareTo(q.trimStart('0').length).takeIf { it != 0 } ?: p.trimStart('0').compareTo(q.trimStart('0'))
            } else p.compareTo(q)
            if (c != 0) return c
        }
        return x.size.compareTo(y.size)
    }
}
