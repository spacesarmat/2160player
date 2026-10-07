package tv.p2160.core.source.dlna

import android.content.Context
import androidx.core.net.toUri
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import tv.p2160.core.api.ExternalSubtitle
import tv.p2160.core.api.MediaEntry
import tv.p2160.core.api.PlaybackRequest

/** Внешние субтитры для воспроизведения: [name] с расширением — по нему плеер определяет формат. */
data class DlnaSubtitleSpec(val url: String, val name: String, val language: String?)

/** Платформонезависимое описание того, что играть (для тестов без android.net.Uri). */
data class DlnaPlaybackSpec(
    val url: String,
    val title: String,
    val mimeType: String?,
    val subtitles: List<DlnaSubtitleSpec>,
)

/** Перевод объектов DLNA в запросы воспроизведения: файлы идут обычным HTTP-путём плеера. */
object DlnaPlayback {

    fun spec(item: DlnaItem): DlnaPlaybackSpec? {
        val res = item.bestResource ?: return null
        val subs = item.subtitles.mapIndexed { i, s ->
            val label = s.language?.uppercase() ?: if (item.subtitles.size > 1) "${s.format.uppercase()} ${i + 1}" else s.format.uppercase()
            DlnaSubtitleSpec(s.url, "$label.${s.format}", s.language)
        }
        return DlnaPlaybackSpec(res.url, item.title, res.mimeType?.takeUnless { it == "*" }, subs)
    }

    fun mediaEntry(item: DlnaItem): MediaEntry? = spec(item)?.let { s ->
        MediaEntry(
            uri = s.url.toUri(),
            title = s.title,
            subtitles = s.subtitles.map { ExternalSubtitle(it.url.toUri(), it.name, it.language) },
            mimeType = s.mimeType,
            artworkUri = item.albumArtUrl?.toUri(),
        )
    }

    /** Индекс в отфильтрованном плейлисте: элементы без ресурса пропускаются. */
    fun playlistIndex(items: List<DlnaItem>, index: Int): Pair<List<DlnaItem>, Int>? {
        val selected = items.getOrNull(index) ?: return null
        val playable = items.filter { it.bestResource != null }
        val start = playable.indexOf(selected).takeIf { it >= 0 } ?: return null
        return playable to start
    }

    /** Плейлист из элементов папки, начиная с [index]. */
    fun request(items: List<DlnaItem>, index: Int): PlaybackRequest? {
        val (playable, start) = playlistIndex(items, index) ?: return null
        return PlaybackRequest(playable.mapNotNull(::mediaEntry), startIndex = start)
    }
}

/** MulticastLock Wi-Fi на время поиска (нужно разрешение CHANGE_WIFI_MULTICAST_STATE). */
class AndroidMulticastLock(context: Context) : MulticastLockProvider {
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    override fun acquire(): () -> Unit {
        val lock = runCatching {
            wifi?.createMulticastLock("p2160-ssdp")?.apply { setReferenceCounted(false); acquire() }
        }.getOrNull()
        return { runCatching { if (lock?.isHeld == true) lock.release() } }
    }
}

/** Поиск DLNA-серверов на Android. */
object DlnaDiscovery {

    sealed class Event {
        data class Found(val server: DlnaServer) : Event()
        data class Lost(val udn: String) : Event()
    }

    /** Поток событий одного цикла поиска длительностью [timeoutMs]; завершается по окончании. */
    fun events(context: Context, timeoutMs: Long = 5000): Flow<Event> = events(AndroidMulticastLock(context), timeoutMs)

    fun events(lock: MulticastLockProvider, timeoutMs: Long = 5000): Flow<Event> = callbackFlow {
        val discovery = SsdpDiscovery(lock)
        val listener = object : DlnaDiscoveryListener {
            override fun onFound(server: DlnaServer) { trySend(Event.Found(server)) }
            override fun onLost(udn: String) { trySend(Event.Lost(udn)) }
        }
        val thread = Thread({
            runCatching { discovery.search(timeoutMs, listener) }
            close()
        }, "dlna-discovery").apply { isDaemon = true; start() }
        awaitClose { discovery.cancel(); thread.interrupt() }
    }.flowOn(Dispatchers.IO)
}
