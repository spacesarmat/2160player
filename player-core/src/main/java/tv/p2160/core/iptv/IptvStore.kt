package tv.p2160.core.iptv

import android.content.Context
import android.net.Uri
import android.util.Xml
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import tv.p2160.core.api.LiveGuide
import tv.p2160.core.api.MediaEntry
import tv.p2160.core.api.PlaybackRequest
import tv.p2160.core.api.PlayerExtensions
import tv.p2160.core.engine.PlayerFactory
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Сохранённый IPTV-плейлист. */
data class IptvPlaylist(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    /** URL (`http(s)://…`) или локальный файл (`content://…`, `file://…`). */
    val source: String,
    /** Свой адрес EPG вместо `url-tvg` из плейлиста (можно несколько через запятую). */
    val epgUrl: String? = null,
    /** User-Agent для загрузки плейлиста, EPG и потоков без своего UA. */
    val userAgent: String? = null,
    val updatedAt: Long = 0,
    val epgUpdatedAt: Long = 0,
    val channelCount: Int = 0,
    /** Последняя ошибка обновления (текст исключения) или null. */
    val error: String? = null,
) {
    val isLocal: Boolean get() = source.startsWith("content:", true) || source.startsWith("file:", true)
}

/**
 * IPTV: плейлисты, кэш каналов и телепрограммы, избранное, последний канал.
 *
 * Плейлист кэшируется как исходный текст (`filesDir/iptv/<id>.m3u`) и разбирается заново при
 * загрузке — это быстро. EPG хранится уже отфильтрованным (`<id>.epg`, см. [EpgCodec]).
 * Также служит встроенным [LiveGuide] для плеера: подпись «текущая передача» по URL канала.
 */
class IptvStore private constructor(context: Context) : LiveGuide {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("p2160_iptv", Context.MODE_PRIVATE)
    private val dir = File(appContext.filesDir, "iptv").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val locks = ConcurrentHashMap<String, Mutex>()

    private val _playlists = MutableStateFlow(readPlaylists())
    val playlists: StateFlow<List<IptvPlaylist>> = _playlists.asStateFlow()

    private val _favourites = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    /** Избранные каналы: id плейлиста → id каналов. */
    val favourites: StateFlow<Map<String, Set<String>>> = _favourites.asStateFlow()

    private val channelsCache = ConcurrentHashMap<String, M3uPlaylist>()
    private val guides = ConcurrentHashMap<String, EpgGuide>()
    private val _guideVersion = MutableStateFlow(0)
    /** Меняется при каждой загрузке телепрограммы — повод перерисовать «сейчас/далее». */
    val guideVersion: StateFlow<Int> = _guideVersion.asStateFlow()

    /** Текущий сеанс просмотра: URL канала → (плейлист, канал) — для подписи в плеере. */
    @Volatile private var session: Map<String, Pair<String, IptvChannel>> = emptyMap()
    @Volatile private var sessionRestoring = false
    private val guideAttempts = ConcurrentHashMap<String, Long>()

    init {
        // Переключили канал в плеере — он и становится «последним».
        scope.launch {
            PlayerExtensions.nowPlaying.map { it?.uri?.toString() }.distinctUntilChanged().collect { uri ->
                val (playlistId, channel) = uri?.let { session[it] } ?: return@collect
                if (lastChannel(playlistId) != channel.id) setLastChannel(playlistId, channel.id)
            }
        }
        _favourites.value =_playlists.value.associate { it.id to (prefs.getStringSet(favKey(it.id), null)?.toSet() ?: emptySet()) }
    }

    fun get(id: String): IptvPlaylist? = _playlists.value.firstOrNull { it.id == id }

    // region Плейлисты

    fun save(playlist: IptvPlaylist) {
        val old = get(playlist.id)
        // Сменился источник или EPG — кэш устарел.
        if (old != null && (old.source != playlist.source || old.userAgent != playlist.userAgent)) {
            channelsCache.remove(playlist.id)
            cacheFile(playlist.id).delete()
        }
        if (old != null && (old.epgUrl != playlist.epgUrl || old.source != playlist.source)) {
            guides.remove(playlist.id)
            epgFile(playlist.id).delete()
        }
        val list = _playlists.value.map { if (it.id == playlist.id) playlist else it }
            .let { if (old == null) it + playlist else it }
        writePlaylists(list)
    }

    fun delete(id: String) {
        channelsCache.remove(id)
        guides.remove(id)
        cacheFile(id).delete()
        epgFile(id).delete()
        prefs.edit().remove(favKey(id)).remove(lastKey(id)).apply()
        _favourites.value = _favourites.value - id
        writePlaylists(_playlists.value.filterNot { it.id == id })
    }

    /** Каналы из памяти (без загрузки), если плейлист уже открывался. */
    fun cachedChannels(id: String): M3uPlaylist? = channelsCache[id]

    /**
     * Каналы плейлиста: из кэша, если он свежий, иначе загрузка. При ошибке сети остаётся
     * старый кэш (ошибка записывается в [IptvPlaylist.error]); без кэша — исключение.
     */
    suspend fun channels(id: String, forceRefresh: Boolean = false, maxAgeHours: Int = PLAYLIST_MAX_AGE_HOURS): M3uPlaylist =
        lock(id).withLock {
            withContext(Dispatchers.IO) {
                val playlist = get(id) ?: throw IOException("playlist not found")
                val file = cacheFile(id)
                val fresh = file.exists() && !isStale(playlist.updatedAt, System.currentTimeMillis(), maxAgeHours)
                // Локальный файл не «устаревает»: перечитываем только по запросу.
                val useCache = !forceRefresh && file.exists() && (fresh || playlist.isLocal)
                if (useCache) {
                    channelsCache[id]?.let { return@withContext it }
                    return@withContext parseAndRemember(id, file.readText(), playlist.source)
                }
                val text = runCatching { download(playlist.source, playlist.userAgent).use { it.readBytes() }.toString(Charsets.UTF_8) }
                    .getOrElse { e ->
                        update(id) { it.copy(error = e.message ?: e.javaClass.simpleName) }
                        if (file.exists()) return@withContext channelsCache[id] ?: parseAndRemember(id, file.readText(), playlist.source)
                        throw e
                    }
                val parsed = M3uParser.parse(text, playlist.source.takeUnless { playlist.isLocal })
                if (parsed.channels.isEmpty()) {
                    val message = if (parsed.looksLikeHls) ERROR_HLS else ERROR_EMPTY
                    update(id) { it.copy(error = message) }
                    if (file.exists()) return@withContext channelsCache[id] ?: parseAndRemember(id, file.readText(), playlist.source)
                    throw IOException(message)
                }
                file.writeText(text)
                channelsCache[id] = parsed
                update(id) { it.copy(updatedAt = System.currentTimeMillis(), channelCount = parsed.channels.size, error = null) }
                parsed
            }
        }

    private fun parseAndRemember(id: String, text: String, source: String): M3uPlaylist {
        val local = get(id)?.isLocal == true
        return M3uParser.parse(text, source.takeUnless { local }).also { channelsCache[id] = it }
    }

    // endregion

    // region Телепрограмма

    /** Телепрограмма из памяти (без загрузки). */
    fun cachedGuide(id: String): EpgGuide? = guides[id]

    /** Адреса EPG: свой из настроек плейлиста или из заголовка `#EXTM3U`. */
    fun epgUrls(playlist: IptvPlaylist, parsed: M3uPlaylist?): List<String> =
        playlist.epgUrl?.split(',', ' ', '\n')?.map { it.trim() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }
            ?: parsed?.epgUrls.orEmpty()

    /**
     * Телепрограмма плейлиста: с диска, если свежая и покрывает ближайшие часы, иначе загрузка
     * всех источников EPG. null — источников нет или все недоступны.
     */
    suspend fun guide(id: String, forceRefresh: Boolean = false): EpgGuide? {
        val parsed = runCatching { channels(id) }.getOrNull() ?: return null
        return lock("epg:$id").withLock {
            withContext(Dispatchers.IO) {
                val playlist = get(id) ?: return@withContext null
                val now = System.currentTimeMillis()
                val file = epgFile(id)
                if (!forceRefresh) {
                    guides[id]?.takeIf { !needsEpgRefresh(playlist.epgUpdatedAt, it.data, now) }?.let { return@withContext it }
                    if (file.exists()) {
                        val data = runCatching { file.bufferedReader().useLines { EpgCodec.decode(it) } }.getOrNull()?.trimmed(now)
                        if (data != null && !needsEpgRefresh(playlist.epgUpdatedAt, data, now)) return@withContext remember(id, data)
                    }
                }
                val urls = epgUrls(playlist, parsed)
                if (urls.isEmpty()) return@withContext null
                val filter = EpgFilter(parsed.channels)
                var merged = EpgData.EMPTY
                var lastError: Throwable? = null
                urls.forEach { url ->
                    runCatching {
                        download(url, playlist.userAgent).use { XmltvParser.parse(it, Xml.newPullParser(), now, filter) }
                    }.onSuccess { merged += it }.onFailure { lastError = it }
                }
                if (merged.isEmpty) {
                    // Сеть недоступна — показываем старое, если есть.
                    val old = guides[id] ?: runCatching { file.bufferedReader().useLines { EpgCodec.decode(it) } }.getOrNull()
                        ?.trimmed(now)?.takeUnless { it.isEmpty }?.let { remember(id, it) }
                    if (old == null && lastError != null) throw lastError!!
                    return@withContext old
                }
                runCatching { file.bufferedWriter().use { EpgCodec.encode(merged, it) } }
                update(id) { it.copy(epgUpdatedAt = now) }
                remember(id, merged)
            }
        }
    }

    private fun remember(id: String, data: EpgData): EpgGuide =
        EpgGuide(data).also { guides[id] = it; _guideVersion.value++ }

    // endregion

    // region Избранное и последний канал

    fun isFavourite(playlistId: String, channelId: String): Boolean = channelId in _favourites.value[playlistId].orEmpty()

    fun toggleFavourite(playlistId: String, channelId: String) {
        val current = _favourites.value[playlistId].orEmpty()
        val updated = if (channelId in current) current - channelId else current + channelId
        prefs.edit().putStringSet(favKey(playlistId), updated).apply()
        _favourites.value = _favourites.value + (playlistId to updated)
    }

    fun lastChannel(playlistId: String): String? = prefs.getString(lastKey(playlistId), null)

    private val _lastChannelChanges = MutableStateFlow(0)
    /** Меняется при смене последнего канала (в том числе переключением в плеере). */
    val lastChannelChanges: StateFlow<Int> = _lastChannelChanges.asStateFlow()

    fun setLastChannel(playlistId: String, channelId: String) {
        prefs.edit().putString(lastKey(playlistId), channelId).putString(KEY_LAST_PLAYLIST, playlistId).apply()
        _lastChannelChanges.value++
    }

    /** Плейлист, из которого смотрели последним. */
    fun lastPlaylist(): String? = prefs.getString(KEY_LAST_PLAYLIST, null)?.takeIf { get(it) != null }

    // endregion

    // region Воспроизведение

    /**
     * Запрос для плеера: элементы — каналы [channels] (обычно видимый список группы),
     * старт с [index]. Запоминает последний канал и включает подпись из EPG в плеере.
     */
    fun playbackRequest(playlistId: String, channels: List<IptvChannel>, index: Int): PlaybackRequest {
        require(index in channels.indices) { "index out of range" }
        val playlist = get(playlistId)
        val window = IptvPlayback.window(channels.size, index, IptvPlayback.MAX_ITEMS)
        val items = channels.subList(window.first, window.last + 1)
        val start = index - window.first
        val chosen = channels[index]
        setLastChannel(playlistId, chosen.id)
        session = items.associate { it.url to (playlistId to it) }
        return PlaybackRequest(
            items = items.map { MediaEntry(Uri.parse(it.url), it.name) },
            startIndex = start,
            headers = IptvPlayback.headersFor(chosen, playlist?.userAgent),
            liveTv = true,
        )
    }

    /** Подпись в плеере: «Передача · 18:00–19:00». */
    override fun describe(entry: MediaEntry, nowMs: Long): String? {
        val key = entry.uri.toString()
        val hit = session[key]
        if (hit == null) {
            restoreSession()
            return null
        }
        val (playlistId, channel) = hit
        val guide = guides[playlistId]
        if (guide == null) {
            // После перезапуска процесса программа ещё не в памяти — подгружаем в фоне (не чаще раза в 5 мин).
            val last = guideAttempts[playlistId] ?: 0L
            if (nowMs - last > 5 * 60_000L) {
                guideAttempts[playlistId] = nowMs
                scope.launch { runCatching { guide(playlistId) } }
            }
            return null
        }
        val now = guide.nowNext(channel, nowMs)?.now ?: return null
        return "${now.title} · ${formatClock(now.startMs)}–${formatClock(now.stopMs)}"
    }

    /** Плеер открыт после перезапуска процесса: восстанавливаем соответствие URL → канал. */
    private fun restoreSession() {
        if (sessionRestoring) return
        val id = lastPlaylist() ?: return
        sessionRestoring = true
        scope.launch {
            runCatching {
                val parsed = channels(id)
                if (session.isEmpty()) session = parsed.channels.associate { it.url to (id to it) }
            }
            sessionRestoring = false
        }
    }

    // endregion

    private fun lock(key: String) = locks.getOrPut(key) { Mutex() }

    private fun cacheFile(id: String) = File(dir, "$id.m3u")
    private fun epgFile(id: String) = File(dir, "$id.epg")

    /** Открывает поток источника: content/file — через ContentResolver, http(s) — с редиректами между схемами. */
    private fun download(source: String, userAgent: String?): InputStream {
        val uri = Uri.parse(source)
        when (uri.scheme?.lowercase()) {
            "content", "file", "android.resource" ->
                return appContext.contentResolver.openInputStream(uri) ?: throw IOException("cannot open $source")
        }
        var url = URL(source)
        repeat(MAX_REDIRECTS) {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", userAgent?.ifBlank { null } ?: PlayerFactory.USER_AGENT)
                setRequestProperty("Accept", "*/*")
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location") ?: throw IOException("HTTP $code")
                conn.disconnect()
                url = URL(url, location)
                return@repeat
            }
            if (code !in 200..299) {
                conn.disconnect()
                throw IOException("HTTP $code")
            }
            return conn.inputStream
        }
        throw IOException("too many redirects")
    }

    private fun update(id: String, transform: (IptvPlaylist) -> IptvPlaylist) {
        writePlaylists(_playlists.value.map { if (it.id == id) transform(it) else it })
    }

    @Synchronized
    private fun writePlaylists(list: List<IptvPlaylist>) {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(
                JSONObject().put("id", p.id).put("name", p.name).put("source", p.source)
                    .put("epg", p.epgUrl.orEmpty()).put("ua", p.userAgent.orEmpty())
                    .put("updated", p.updatedAt).put("epgUpdated", p.epgUpdatedAt)
                    .put("count", p.channelCount).put("error", p.error.orEmpty())
            )
        }
        prefs.edit().putString("playlists", arr.toString()).apply()
        _playlists.value = list
        _favourites.value = list.associate { it.id to (_favourites.value[it.id] ?: prefs.getStringSet(favKey(it.id), null)?.toSet() ?: emptySet()) }
    }

    private fun readPlaylists(): List<IptvPlaylist> = runCatching {
        val arr = JSONArray(prefs.getString("playlists", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            IptvPlaylist(
                id = o.getString("id"),
                name = o.optString("name"),
                source = o.getString("source"),
                epgUrl = o.optString("epg").ifBlank { null },
                userAgent = o.optString("ua").ifBlank { null },
                updatedAt = o.optLong("updated"),
                epgUpdatedAt = o.optLong("epgUpdated"),
                channelCount = o.optInt("count"),
                error = o.optString("error").ifBlank { null },
            )
        }
    }.getOrDefault(emptyList())

    companion object {
        const val PLAYLIST_MAX_AGE_HOURS = 24
        const val EPG_MAX_AGE_HOURS = 12
        /** Тексты ошибок-маркеры: UI показывает понятное сообщение. */
        const val ERROR_EMPTY = "iptv:empty"
        const val ERROR_HLS = "iptv:hls"
        private const val MAX_REDIRECTS = 6
        private const val KEY_LAST_PLAYLIST = "last_playlist"

        private fun favKey(id: String) = "fav:$id"
        private fun lastKey(id: String) = "last:$id"

        /** Пора обновлять: ни разу не загружали, прошло больше [maxAgeHours] или часы «в будущем». */
        fun isStale(updatedAt: Long, nowMs: Long, maxAgeHours: Int): Boolean =
            updatedAt <= 0 || nowMs - updatedAt > maxAgeHours * 3_600_000L || updatedAt > nowMs + 3_600_000L

        /** EPG устарела по возрасту или в ней почти не осталось будущих передач. */
        fun needsEpgRefresh(updatedAt: Long, data: EpgData, nowMs: Long): Boolean {
            if (isStale(updatedAt, nowMs, EPG_MAX_AGE_HOURS)) return true
            val latest = data.programmes.values.maxOfOrNull { list -> list.lastOrNull()?.stopMs ?: 0L } ?: return true
            return latest < nowMs + 3 * 3_600_000L
        }

        private fun formatClock(ms: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

        @Volatile private var instance: IptvStore? = null

        fun get(context: Context): IptvStore = instance ?: synchronized(this) {
            instance ?: IptvStore(context).also { instance = it }
        }
    }
}

/** Чистая логика сборки запроса — без Android, проверяется JVM-тестами. */
object IptvPlayback {
    /** Ограничение размера плейлиста в Intent (лимит Binder ~1 МБ). */
    const val MAX_ITEMS = 1000

    /** Окно индексов не больше [max] элементов вокруг [index]. */
    fun window(size: Int, index: Int, max: Int): IntRange {
        if (size <= max) return 0 until size
        val start = (index - max / 2).coerceIn(0, size - max)
        return start until start + max
    }

    /** Заголовки запроса: свои у канала, User-Agent плейлиста — если у канала его нет. */
    fun headersFor(channel: IptvChannel, playlistUserAgent: String?): Map<String, String> {
        val headers = LinkedHashMap(channel.httpHeaders())
        if (headers.keys.none { it.equals("User-Agent", true) }) playlistUserAgent?.ifBlank { null }?.let { headers["User-Agent"] = it }
        return headers
    }
}
