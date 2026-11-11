package tv.p2160.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.p2160.core.iptv.IptvChannel
import tv.p2160.core.iptv.M3uParser
import tv.p2160.core.source.dlna.ContentDirectory
import tv.p2160.core.source.dlna.DidlParser
import tv.p2160.core.source.dlna.DlnaItem
import tv.p2160.core.source.dlna.DlnaObject
import tv.p2160.core.source.dlna.DlnaProbe
import tv.p2160.core.source.dlna.DlnaServer
import tv.p2160.core.source.dlna.SsdpDiscovery
import tv.p2160.desktop.media.LibraryStore
import tv.p2160.desktop.media.playlistOpensAsStream
import tv.p2160.desktop.media.RangeServer
import tv.p2160.desktop.media.RecentItem
import tv.p2160.desktop.media.SmbBrowser
import tv.p2160.desktop.media.SmbEntry
import tv.p2160.desktop.media.SmbSaved
import tv.p2160.desktop.media.TorrentClient
import tv.p2160.desktop.media.isMediaFile
import tv.p2160.desktop.media.subtitleSidecars
import tv.p2160.desktop.player.MpvPlayer
import tv.p2160.desktop.ui.TorrentRow
import tv.p2160.torrent.TorrentFile
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

data class PlayRequest(
    val url: String,
    val title: String,
    val key: String,
    val live: Boolean = false,
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<String> = emptyList(),
    val resumeSec: Double? = null,
    val proxy: String? = null,
)

sealed interface Route {
    data object Home : Route
    data object Smb : Route
    data object Dlna : Route
    data object Iptv : Route
    data object Torrent : Route
    data class Player(val request: PlayRequest) : Route
}

class DesktopController(
    val library: LibraryStore,
    val player: MpvPlayer?,
    val playerError: String?,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ranges = RangeServer()
    private val smb = SmbBrowser()
    private val torrents = TorrentClient(library.torrentDir)
    private var proxyUrl: String? = null

    var stack by mutableStateOf(listOf<Route>(Route.Home))
        private set
    val route: Route get() = stack.last()

    var typing by mutableStateOf(false)
    var smbShares by mutableStateOf<List<String>>(emptyList())
    var smbEntries by mutableStateOf<List<SmbEntry>>(emptyList())
    var smbLabel by mutableStateOf("")
    var smbError by mutableStateOf<String?>(null)
    var smbBusy by mutableStateOf(false)
    private var smbServer: SmbSaved? = null
    private var smbShare = ""
    private var smbPath = ""

    var dlnaServers by mutableStateOf<List<DlnaServer>>(emptyList())
    var dlnaObjects by mutableStateOf<List<DlnaObject>>(emptyList())
    var dlnaCrumbs by mutableStateOf<List<String>>(emptyList())
    var dlnaError by mutableStateOf<String?>(null)
    var dlnaBusy by mutableStateOf(false)
    private val dlnaStack = ArrayList<Pair<DlnaServer, String>>()

    var iptvChannels by mutableStateOf<List<IptvChannel>>(emptyList())
    var iptvError by mutableStateOf<String?>(null)
    var iptvBusy by mutableStateOf(false)

    var torrentRows by mutableStateOf<List<TorrentRow>>(emptyList())
    var torrentError by mutableStateOf<String?>(null)
    private val torrentSources = HashMap<String, String>()

    init {
        player?.setVolume(library.volume)
        scope.launch {
            while (true) {
                delay(1000)
                if (torrents.running) runCatching { refreshTorrents() }
            }
        }
    }

    private suspend fun onUi(block: () -> Unit) = withContext(Dispatchers.Main) { block() }

    fun back() {
        val current = route
        if (current is Route.Player) {
            player?.let { engine ->
                if (!current.request.live) library.rememberPosition(current.request.key, engine.state.value.positionSec)
                engine.stop()
            }
            library.flush()
            proxyUrl?.let { ranges.unpublish(it) }
            proxyUrl = null
        }
        if (stack.size > 1) stack = stack.dropLast(1)
    }

    fun home() {
        stack = listOf(Route.Home)
    }

    fun openSmb() { stack = stack + Route.Smb }
    fun openDlna() { stack = stack + Route.Dlna }
    fun openIptv() { stack = stack + Route.Iptv }
    fun openTorrent() {
        scope.launch {
            runCatching { torrents.start() }.onFailure { onUi { torrentError = it.message ?: "Торрент" } }
        }
        stack = stack + Route.Torrent
    }

    fun openFile(file: File) {
        if (!file.isFile) return
        val subs = subtitleSidecars(file).map { it.absolutePath }
        val key = file.absolutePath
        library.rememberRecent(RecentItem("file", file.name, key, key))
        play(file.absolutePath, file.name, key, live = false, subtitles = subs)
    }

    fun openUrl(url: String, title: String = url, live: Boolean = false, headers: Map<String, String> = emptyMap(), kind: String = "url") {
        val clean = url.trim()
        if (clean.isEmpty()) return
        library.rememberRecent(RecentItem(kind, title, clean, if (kind == "iptv") iptvPayload(clean, headers) else clean))
        play(clean, title, clean, live, headers)
    }

    fun openRecent(item: RecentItem) {
        when (item.kind) {
            "file" -> openFile(File(item.payload))
            "smb" -> {
                val parts = item.payload.split('\n')
                if (parts.size >= 3) {
                    val server = library.servers().firstOrNull { it.host.equals(parts[0], true) }
                        ?: SmbSaved(parts[0])
                    openSmbFile(server, parts[1], parts[2], parts.getOrElse(3) { "0" }.toLongOrNull() ?: 0L, item.title)
                }
            }
            "torrent" -> {
                val parts = item.payload.split('\n')
                if (parts.size >= 2) reopenTorrent(parts[0], parts[1].toIntOrNull() ?: 0, item.title)
            }
            "iptv" -> {
                val lines = item.payload.lines()
                val url = lines.first()
                val headers = lines.drop(1).mapNotNull { line ->
                    val eq = line.indexOf('=')
                    if (eq <= 0) null else line.substring(0, eq) to line.substring(eq + 1)
                }.toMap()
                openUrl(url, item.title, live = true, headers = headers, kind = "iptv")
            }
            else -> openUrl(item.payload, item.title, live = false, kind = item.kind)
        }
    }

    fun connectSmb(server: SmbSaved) {
        smbBusy = true
        smbError = null
        scope.launch {
            val result = runCatching {
                library.rememberServer(server)
                smbServer = server
                smbShare = ""
                smbPath = ""
                smb.listShares(server)
            }
            onUi { smbBusy = false }
            result.onSuccess {
                onUi {
                    smbShares = it
                    smbEntries = emptyList()
                    smbLabel = "\\\\${server.host}"
                }
            }.onFailure { onUi { smbError = it.message ?: "SMB" } }
        }
    }

    fun openShare(share: String) {
        val server = smbServer ?: return
        smbShare = share
        smbPath = ""
        reloadSmb()
    }

    fun openSmbEntry(entry: SmbEntry) {
        val server = smbServer ?: return
        if (entry.directory) {
            smbPath = entry.path
            reloadSmb()
        } else if (File(entry.name).isMediaFile() || entry.name.substringAfterLast('.', "").isNotEmpty()) {
            openSmbFile(server, smbShare, entry.path, entry.size, entry.name)
        }
    }

    fun smbUp() {
        if (smbPath.isEmpty()) {
            smbShare = ""
            smbEntries = emptyList()
            smbLabel = smbServer?.let { "\\\\${it.host}" }.orEmpty()
            return
        }
        smbPath = smbPath.replace('/', '\\').substringBeforeLast('\\', "")
        reloadSmb()
    }

    private fun reloadSmb() {
        val server = smbServer ?: return
        val share = smbShare
        val path = smbPath
        smbBusy = true
        scope.launch {
            val result = runCatching { smb.list(server, share, path) }
            onUi { smbBusy = false }
            result.onSuccess {
                onUi {
                    smbShares = emptyList()
                    smbEntries = it
                    smbLabel = "\\\\${server.host}\\$share\\$path"
                }
            }.onFailure { onUi { smbError = it.message ?: "SMB" } }
        }
    }

    private fun openSmbFile(server: SmbSaved, share: String, path: String, size: Long, title: String) {
        scope.launch {
            val result = runCatching {
                val source = smb.openRead(server, share, path, size)
                val url = ranges.publish(source)
                val key = "smb://${server.host}/$share/$path"
                library.rememberRecent(RecentItem("smb", title, key, "${server.host}\n$share\n$path\n$size"))
                play(url, title, key, proxy = url)
            }
            result.onFailure { onUi { smbError = it.message ?: "SMB" } }
        }
    }

    fun searchDlna() {
        dlnaBusy = true
        dlnaError = null
        scope.launch {
            val result = runCatching { SsdpDiscovery().search(4_000) }
            onUi { dlnaBusy = false }
            result.onSuccess {
                onUi {
                    dlnaServers = it
                    if (it.isEmpty()) dlnaError = "Серверы не найдены. Укажите адрес вручную."
                }
            }.onFailure { onUi { dlnaError = it.message ?: "DLNA" } }
        }
    }

    fun probeDlna(address: String) {
        dlnaBusy = true
        dlnaError = null
        scope.launch {
            val server = runCatching { DlnaProbe.resolve(address) }.getOrElse {
                onUi {
                    dlnaBusy = false
                    dlnaError = it.message ?: "DLNA"
                }
                return@launch
            }
            onUi { dlnaBusy = false }
            if (server == null) onUi { dlnaError = "По адресу нет ContentDirectory" } else openDlnaServer(server)
        }
    }

    fun openDlnaServer(server: DlnaServer) {
        dlnaStack.clear()
        dlnaStack += server to "0"
        dlnaCrumbs = listOf(server.friendlyName)
        browseDlna()
    }

    fun openDlnaObject(obj: DlnaObject) {
        val current = dlnaStack.lastOrNull()?.first ?: return
        if (obj is DlnaItem) {
            val resource = DidlParser.bestResource(obj)
            if (resource == null) {
                dlnaError = "У элемента нет HTTP-адреса"
                return
            }
            val subs = obj.subtitles.map { it.url }
            library.rememberRecent(RecentItem("dlna", obj.title, resource.url, resource.url))
            play(resource.url, obj.title, resource.url, subtitles = subs)
        } else {
            dlnaStack += current to obj.id
            dlnaCrumbs = dlnaCrumbs + obj.title
            browseDlna()
        }
    }

    fun dlnaUp() {
        if (dlnaStack.size <= 1) {
            dlnaStack.clear()
            dlnaCrumbs = emptyList()
            dlnaObjects = emptyList()
            return
        }
        dlnaStack.removeAt(dlnaStack.lastIndex)
        dlnaCrumbs = dlnaCrumbs.dropLast(1)
        browseDlna()
    }

    private fun browseDlna() {
        val (server, id) = dlnaStack.lastOrNull() ?: return
        dlnaBusy = true
        scope.launch {
            val result = runCatching { ContentDirectory(server).browseAll(id) }
            onUi { dlnaBusy = false }
            result.onSuccess { onUi { dlnaObjects = it } }.onFailure { onUi { dlnaError = it.message ?: "DLNA" } }
        }
    }

    fun openIptvFile(file: File) {
        scope.launch {
            val text = runCatching { file.readText() }.getOrElse {
                onUi { iptvError = it.message }
                return@launch
            }
            applyPlaylist(text, file.toURI().toString())
        }
    }

    fun openIptvUrl(url: String) {
        iptvBusy = true
        iptvError = null
        scope.launch {
            val bytes = runCatching { httpGetBytes(url) }.getOrElse {
                onUi {
                    iptvBusy = false
                    iptvError = it.message ?: "IPTV"
                }
                return@launch
            }
            onUi { iptvBusy = false }
            applyPlaylist(bytes.toString(Charsets.UTF_8), url)
        }
    }

    private fun applyPlaylist(text: String, base: String) {
        val playlist = M3uParser.parse(text, base)
        if (playlistOpensAsStream(text, base)) {
            val headers = playlist.channels.firstOrNull()?.httpHeaders().orEmpty()
            openUrl(base, base.substringAfterLast('/'), live = true, headers = headers, kind = "iptv")
        } else {
            scope.launch { onUi { iptvChannels = playlist.channels } }
        }
    }

    fun playChannel(channel: IptvChannel) {
        val payload = iptvPayload(channel.url, channel.httpHeaders())
        library.rememberRecent(RecentItem("iptv", channel.name, channel.url, payload))
        play(channel.url, channel.name, channel.url, live = true, headers = channel.httpHeaders())
    }

    fun addMagnet(text: String) {
        val value = text.trim()
        if (value.isEmpty()) return
        scope.launch {
            val result = runCatching {
                torrents.start()
                if (value.startsWith("magnet:", true)) {
                    torrents.addMagnet(value) to value
                } else {
                    torrents.addTorrentFile(httpGetBytes(value)) to value
                }
            }
            result.onSuccess { (id, source) ->
                torrentSources[id] = source
                refreshTorrents()
            }.onFailure { onUi { torrentError = it.message ?: "Торрент" } }
        }
    }

    fun addTorrentFile(file: File) {
        scope.launch {
            val result = runCatching {
                torrents.start()
                torrents.addTorrentFile(file.readBytes())
            }
            result.onSuccess {
                torrentSources[it] = file.absolutePath
                refreshTorrents()
            }.onFailure { onUi { torrentError = it.message ?: "Торрент" } }
        }
    }

    fun playTorrent(id: String, file: TorrentFile) {
        scope.launch {
            val result = runCatching {
                val source = torrents.open(id, file.index)
                val url = ranges.publish(source)
                val origin = torrentSources[id].orEmpty()
                val key = "torrent:$origin:${file.index}"
                library.rememberRecent(RecentItem("torrent", file.name, key, "$origin\n${file.index}"))
                play(url, file.name, key, proxy = url)
            }
            result.onFailure { onUi { torrentError = it.message ?: "Торрент" } }
        }
    }

    fun removeTorrent(id: String) {
        runCatching { torrents.remove(id) }
        torrentSources.remove(id)
        refreshTorrents()
    }

    private fun reopenTorrent(source: String, index: Int, title: String) {
        scope.launch {
            val existing = torrentSources.entries.firstOrNull { it.value == source }?.key
            val id = existing ?: runCatching {
                torrents.start()
                if (source.startsWith("magnet:", true)) torrents.addMagnet(source)
                else if (source.startsWith("http://", true) || source.startsWith("https://", true)) {
                    torrents.addTorrentFile(httpGetBytes(source))
                } else torrents.addTorrentFile(File(source).readBytes())
            }.getOrElse {
                onUi { torrentError = it.message }
                return@launch
            }
            torrentSources[id] = source
            repeat(40) {
                if (torrents.files(id) != null) return@repeat
                delay(500)
            }
            val file = torrents.files(id)?.firstOrNull { it.index == index }
            if (file == null) onUi { torrentError = "Нет файла в торренте" } else playTorrent(id, file)
        }
    }

    private fun refreshTorrents() {
        val rows = torrents.ids().map { id ->
            val stats = torrents.stats(id)
            TorrentRow(
                id = id,
                title = torrents.name(id) ?: stats?.name ?: id.take(12),
                stats = stats,
                files = torrents.files(id).orEmpty(),
            )
        }
        scope.launch { onUi { torrentRows = rows } }
    }

    private fun play(
        url: String,
        title: String,
        key: String,
        live: Boolean = false,
        headers: Map<String, String> = emptyMap(),
        subtitles: List<String> = emptyList(),
        proxy: String? = null,
    ) {
        scope.launch { onUi { startPlayback(url, title, key, live, headers, subtitles, proxy) } }
    }

    private fun startPlayback(
        url: String,
        title: String,
        key: String,
        live: Boolean,
        headers: Map<String, String>,
        subtitles: List<String>,
        proxy: String?,
    ) {
        val engine = player ?: return
        proxyUrl?.let { ranges.unpublish(it) }
        proxyUrl = proxy
        val resume = if (live) null else library.position(key)?.takeIf { it >= 10.0 }
        val request = PlayRequest(url, title, key, live, headers, subtitles, resume, proxy)
        engine.open(url, title, paused = resume != null, headers = headers)
        stack = stack.filterNot { it is Route.Player } + Route.Player(request)
        if (subtitles.isNotEmpty()) {
            scope.launch {
                repeat(50) {
                    if (engine.state.value.fileLoaded) return@repeat
                    delay(100)
                }
                subtitles.forEach { path -> runCatching { engine.addSubtitle(path) } }
            }
        }
    }

    fun savePosition(request: PlayRequest, seconds: Double) {
        if (!request.live) library.rememberPosition(request.key, seconds)
    }

    fun noteVolume(volume: Int) = library.rememberVolume(volume)

    override fun close() {
        proxyUrl?.let { ranges.unpublish(it) }
        player?.close()
        ranges.close()
        smb.close()
        torrents.close()
    }
}

private fun iptvPayload(url: String, headers: Map<String, String>): String =
    if (headers.isEmpty()) url else url + "\n" + headers.entries.joinToString("\n") { "${it.key}=${it.value}" }

private fun httpGetBytes(url: String): ByteArray {
    val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = true
        connectTimeout = 15_000
        readTimeout = 30_000
        setRequestProperty("User-Agent", "2160Player/$APP_VERSION")
    }
    return connection.inputStream.use { it.readBytes() }
}
