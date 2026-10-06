package tv.p2160.app.handoff

import android.content.Context
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import tv.p2160.core.api.NowPlaying
import tv.p2160.core.api.Player2160
import tv.p2160.core.source.RandomAccessSources
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Другой экземпляр 2160 Player в локальной сети. */
data class Peer(val id: String, val name: String, val host: String, val port: Int)

/** Что играет (или стояло на паузе) на другом устройстве. */
data class RemoteSession(
    val peer: Peer,
    val uri: Uri,
    val title: String,
    val positionMs: Long,
    val durationMs: Long,
    val isPlaying: Boolean,
    val headers: Map<String, String>,
)

/**
 * «Продолжить на другом устройстве» без облака.
 *
 * Каждый запущенный плеер поднимает маленький HTTP-сервер и объявляет себя по mDNS (`_p2160._tcp`):
 * - `GET /now` — что сейчас играет (URI, доступный другим устройствам, и позиция);
 * - `POST /play` — предложение продолжить просмотр здесь (показываем диалог подтверждения);
 * - `GET /stream/<token>` — раздача локального файла (content://, file://) с поддержкой Range,
 *   чтобы ТВ мог досмотреть видео, лежащее в памяти телефона.
 * Сервер работает, пока приложение на экране.
 */
object Handoff {
    private const val TAG = "Handoff"
    private const val SERVICE_TYPE = "_p2160._tcp."

    val deviceId: String = UUID.randomUUID().toString().take(8)

    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    private var app: Context? = null
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private var nsd: NsdManager? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private val shared = ConcurrentHashMap<String, Uri>()

    fun deviceName(context: Context): String =
        Settings.Global.getString(context.contentResolver, "device_name")?.takeIf { it.isNotBlank() }
            ?: Build.MODEL

    @Synchronized
    fun start(context: Context) {
        if (server != null) return
        val ctx = context.applicationContext
        app = ctx
        val socket = runCatching { ServerSocket(0) }.getOrElse { Log.w(TAG, "server", it); return }
        server = socket
        pool.execute { acceptLoop(socket) }
        nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
        register(socket.localPort, deviceName(ctx))
        discover()
    }

    @Synchronized
    fun stop() {
        runCatching { registration?.let { nsd?.unregisterService(it) } }
        runCatching { discovery?.let { nsd?.stopServiceDiscovery(it) } }
        registration = null
        discovery = null
        runCatching { server?.close() }
        server = null
        _peers.value = emptyList()
    }

    // region Клиент

    /** Что играет на [peer]. Блокирующий вызов. */
    fun fetchSession(peer: Peer): RemoteSession? = runCatching {
        val conn = URL("http://${peer.host}:${peer.port}/now").openConnection() as HttpURLConnection
        conn.connectTimeout = 3_000
        conn.readTimeout = 3_000
        try {
            if (conn.responseCode != 200) return null
            sessionFromJson(peer, JSONObject(conn.inputStream.bufferedReader().readText()))
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    /** Предложить [peer] продолжить текущий просмотр. Блокирующий вызов. */
    fun push(context: Context, peer: Peer, now: NowPlaying): Boolean = runCatching {
        val body = toJson(shareable(context, now) ?: return false).toString().toByteArray()
        val conn = URL("http://${peer.host}:${peer.port}/play").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 3_000
        conn.readTimeout = 5_000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(body) }
        val ok = conn.responseCode == 200
        conn.disconnect()
        ok
    }.getOrDefault(false)

    // endregion

    // region Сервер

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            pool.execute { runCatching { handle(client) }.onFailure { Log.d(TAG, "request", it) }; runCatching { client.close() } }
        }
    }

    private fun handle(client: Socket) {
        client.soTimeout = 15_000
        val input = BufferedInputStream(client.getInputStream())
        val requestLine = readLine(input) ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val (method, path) = requestLine.split(' ').let { it.getOrNull(0).orEmpty() to it.getOrNull(1).orEmpty() }
        val out = client.getOutputStream()
        val ctx = app ?: return respond(out, 503, "")
        when {
            method == "GET" && path == "/now" -> {
                val now = Player2160.nowPlaying.value?.let { shareable(ctx, it) }
                if (now == null) respond(out, 204, "") else respond(out, 200, toJson(now).toString(), "application/json")
            }
            method == "POST" && path == "/play" -> {
                val length = headers["content-length"]?.toIntOrNull()?.coerceAtMost(64 * 1024) ?: 0
                val body = ByteArray(length).also { var r = 0; while (r < length) { val n = input.read(it, r, length - r); if (n < 0) break; r += n } }
                val json = JSONObject(String(body))
                respond(out, 200, "{}", "application/json")
                val from = json.optString("from", "?")
                HandoffReceiveActivity.show(ctx, json.toString(), from)
            }
            method == "GET" && path.startsWith("/stream/") -> {
                val token = path.removePrefix("/stream/").substringBefore('/')
                val uri = shared[token] ?: return respond(out, 404, "")
                serveFile(ctx, uri, headers["range"], out)
            }
            else -> respond(out, 404, "")
        }
    }

    private fun serveFile(context: Context, uri: Uri, range: String?, out: OutputStream) {
        RandomAccessSources.open(context, uri).use { src ->
            val size = src.size
            var start = 0L
            var end = size - 1
            val m = range?.let { Regex("bytes=(\\d*)-(\\d*)").find(it) }
            if (m != null) {
                val a = m.groupValues[1]
                val b = m.groupValues[2]
                if (a.isNotEmpty()) { start = a.toLong(); if (b.isNotEmpty()) end = minOf(b.toLong(), size - 1) }
                else if (b.isNotEmpty()) start = size - b.toLong()
            }
            val status = if (m != null) "206 Partial Content" else "200 OK"
            val head = buildString {
                append("HTTP/1.1 $status\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Content-Type: application/octet-stream\r\n")
                append("Content-Length: ${end - start + 1}\r\n")
                if (m != null) append("Content-Range: bytes $start-$end/$size\r\n")
                append("Connection: close\r\n\r\n")
            }
            out.write(head.toByteArray())
            val buf = ByteArray(256 * 1024)
            var pos = start
            while (pos <= end) {
                val n = src.read(pos, buf, 0, minOf(buf.size.toLong(), end - pos + 1).toInt())
                if (n <= 0) break
                out.write(buf, 0, n)
                pos += n
            }
            out.flush()
        }
    }

    private fun respond(out: OutputStream, code: Int, body: String, type: String = "text/plain") {
        val bytes = body.toByteArray()
        val reason = when (code) { 200 -> "OK"; 204 -> "No Content"; 404 -> "Not Found"; else -> "Error" }
        out.write("HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 8192) return null
            sb.append(c.toChar())
        }
    }

    // endregion

    /**
     * URI, по которому другое устройство сможет открыть то же видео:
     * сетевые ссылки — как есть, локальные файлы — через наш /stream.
     */
    private fun shareable(context: Context, now: NowPlaying): NowPlaying? = when (now.uri.scheme?.lowercase()) {
        "smb", "http", "https", "rtsp", "rtmp" -> now
        "content", "file" -> {
            val ip = localIpv4() ?: return null
            val port = server?.localPort ?: return null
            val token = shared.entries.firstOrNull { it.value == now.uri }?.key
                ?: UUID.randomUUID().toString().replace("-", "").also { shared[it] = now.uri }
            val name = Uri.encode(now.title.ifBlank { "video" })
            now.copy(uri = Uri.parse("http://$ip:$port/stream/$token/$name"), headers = emptyMap())
        }
        else -> null
    }

    private fun toJson(now: NowPlaying) = JSONObject()
        .put("uri", now.uri.toString())
        .put("title", now.title)
        .put("position", now.positionMs)
        .put("duration", now.durationMs)
        .put("playing", now.isPlaying)
        .put("headers", JSONObject(now.headers))
        .put("from", app?.let(::deviceName) ?: "?")

    internal fun sessionFromJson(peer: Peer?, json: JSONObject): RemoteSession {
        val headers = json.optJSONObject("headers")?.let { h -> h.keys().asSequence().associateWith { h.getString(it) } }.orEmpty()
        return RemoteSession(
            peer = peer ?: Peer("", json.optString("from"), "", 0),
            uri = Uri.parse(json.getString("uri")),
            title = json.optString("title"),
            positionMs = json.optLong("position"),
            durationMs = json.optLong("duration"),
            isPlaying = json.optBoolean("playing"),
            headers = headers,
        )
    }

    private fun localIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()

    // region mDNS

    private fun register(port: Int, name: String) {
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = SERVICE_TYPE
            this.port = port
            setAttribute("id", deviceId)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        registration = listener
        runCatching { nsd?.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    @Suppress("DEPRECATION")
    private fun discover() {
        val manager = nsd ?: return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                runCatching {
                    manager.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onServiceResolved(r: NsdServiceInfo) {
                            val id = r.attributes["id"]?.let { String(it) } ?: return
                            if (id == deviceId) return
                            val host = (if (Build.VERSION.SDK_INT >= 34) r.hostAddresses.firstOrNull() else r.host)?.hostAddress ?: return
                            val peer = Peer(id, r.serviceName, host, r.port)
                            _peers.value = _peers.value.filterNot { it.id == id } + peer
                        }
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit
                    })
                }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                _peers.value = _peers.value.filterNot { it.name == info.serviceName }
            }
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        discovery = listener
        runCatching { manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    // endregion

    /** Открыть сессию другого устройства здесь, с той же позиции. */
    fun play(context: Context, session: RemoteSession) {
        Player2160.play(
            context,
            tv.p2160.core.api.PlaybackRequest(
                items = listOf(tv.p2160.core.api.MediaEntry(session.uri, session.title)),
                startPositionMs = session.positionMs,
                headers = session.headers,
            ),
        )
    }
}
