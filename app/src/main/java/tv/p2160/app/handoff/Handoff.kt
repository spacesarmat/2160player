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

/** Другой экземпляр 2160 Player в локальной сети. [locked] — на нём включена защита кодом. */
data class Peer(val id: String, val name: String, val host: String, val port: Int, val locked: Boolean = false)

/** Итог отправки просмотра на другое устройство. */
enum class PushResult { OK, NEED_CODE, FAILED }

/** Запрос подключения: [nonce] — для кода и опроса ответа; [denied] — хозяин недавно отклонил нас. */
data class PairRequest(val nonce: String?, val denied: Boolean = false)

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
 *   чтобы ТВ мог досмотреть видео, лежащее в памяти телефона;
 * - `GET /pair/challenge`, `GET /pair/status`, `POST /pair` — сопряжение: хозяин отвечает в уведомлении
 *   «Разрешить/Отклонить» ([PairRequests]) или клиент вводит код ([HandoffAuth]); при включённой защите
 *   `/now` и `/play` требуют заголовок `X-P2160-Token`, иначе 401.
 * Работает только в одной локальной сети (Wi-Fi, точка доступа телефона): mDNS и прямые соединения
 * через мобильную сеть (NAT оператора) не проходят.
 * Сервер работает, пока приложение на экране.
 */
object Handoff {
    private const val TAG = "Handoff"
    private const val SERVICE_TYPE = "_p2160._tcp."

    private const val TOKEN_HEADER = "X-P2160-Token"

    /** Постоянный: по нему другие плееры помнят сопряжение. */
    val deviceId: String get() = HandoffAuth.deviceId

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
        HandoffAuth.init(ctx)
        val socket = runCatching { ServerSocket(0) }.getOrElse { Log.w(TAG, "server", it); return }
        server = socket
        Log.i(TAG, "listening on ${socket.localPort}, protection ${HandoffAuth.mode}")
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

    /** Перезапуск объявления (например, после смены режима защиты — меняется TXT `auth`). */
    fun restart(context: Context) {
        val running = synchronized(this) { server != null }
        if (!running) return
        stop()
        start(context)
    }

    /** Нужно ли вводить код, чтобы работать с [peer]. */
    fun needsCode(peer: Peer): Boolean = peer.locked && HandoffAuth.tokenFor(peer.id) == null

    // region Клиент

    private fun connect(peer: Peer, path: String, readTimeout: Int = 3_000): HttpURLConnection =
        (URL("http://${peer.host}:${peer.port}$path").openConnection() as HttpURLConnection).apply {
            connectTimeout = 3_000
            this.readTimeout = readTimeout
            HandoffAuth.tokenFor(peer.id)?.let { setRequestProperty(TOKEN_HEADER, it) }
        }

    /** Что играет на [peer]. Блокирующий вызов. null — ничего или нет доступа (нужен код). */
    fun fetchSession(peer: Peer): RemoteSession? = runCatching {
        if (needsCode(peer)) return null
        val conn = connect(peer, "/now")
        try {
            if (conn.responseCode == 401) { HandoffAuth.dropToken(peer.id); return null }
            if (conn.responseCode != 200) return null
            sessionFromJson(peer, JSONObject(conn.inputStream.bufferedReader().readText()))
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    /** Предложить [peer] продолжить текущий просмотр. Блокирующий вызов. */
    fun push(context: Context, peer: Peer, now: NowPlaying): PushResult = runCatching {
        if (needsCode(peer)) return PushResult.NEED_CODE
        val body = toJson(shareable(context, now) ?: return PushResult.FAILED).toString().toByteArray()
        val conn = connect(peer, "/play", readTimeout = 5_000)
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(body) }
        val code = conn.responseCode
        conn.disconnect()
        when (code) {
            200 -> PushResult.OK
            401 -> { HandoffAuth.dropToken(peer.id); PushResult.NEED_CODE }
            else -> PushResult.FAILED
        }
    }.getOrDefault(PushResult.FAILED)

/**
     * Запрос подключения к [peer]: на нём появится уведомление «Разрешить / Отклонить».
     * Возвращает nonce запроса; null — не удалось или [peer] недавно отклонил нас (тогда [denied] = true).
     */
    fun requestPairing(context: Context, peer: Peer): PairRequest = runCatching {
        val name = Uri.encode(deviceName(context))
        val conn = connect(peer, "/pair/challenge?id=$deviceId&name=$name")
        try {
            when (conn.responseCode) {
                200 -> PairRequest(JSONObject(conn.inputStream.bufferedReader().readText()).getString("nonce"))
                403 -> PairRequest(null, denied = true)
                else -> PairRequest(null)
            }
        } finally {
            conn.disconnect()
        }
    }.getOrDefault(PairRequest(null))

    /** Ответил ли хозяин [peer] на запрос: APPROVED — токен уже сохранён. null — запрос истёк или потерян. */
    fun pairStatus(peer: Peer, nonce: String): HandoffAuth.Decision? = runCatching {
        val conn = connect(peer, "/pair/status?nonce=$nonce")
        try {
            if (conn.responseCode != 200) return null
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            when (json.optString("state")) {
                "approved" -> {
                    HandoffAuth.saveToken(peer.id, json.getString("token"))
                    HandoffAuth.Decision.APPROVED
                }
                "denied" -> HandoffAuth.Decision.DENIED
                else -> HandoffAuth.Decision.PENDING
            }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    /** Сопряжение по коду, который показывает [peer] (в уведомлении или в его настройках). Блокирующий вызов. */
    fun pairWithCode(context: Context, peer: Peer, nonce: String, code: String): HandoffAuth.PairResult = runCatching {
        val body = JSONObject()
            .put("id", deviceId)
            .put("name", deviceName(context))
            .put("nonce", nonce)
            .put("proof", HandoffAuth.proofFor(code.trim(), nonce, deviceId))
            .toString().toByteArray()
        val conn = connect(peer, "/pair", readTimeout = 5_000)
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(body) }
        try {
            when (conn.responseCode) {
                200 -> {
                    HandoffAuth.saveToken(peer.id, JSONObject(conn.inputStream.bufferedReader().readText()).getString("token"))
                    HandoffAuth.PairResult.OK
                }
                403 -> HandoffAuth.PairResult.WRONG_CODE
                429 -> HandoffAuth.PairResult.LOCKED
                else -> HandoffAuth.PairResult.FAILED
            }
        } finally {
            conn.disconnect()
        }
    }.getOrDefault(HandoffAuth.PairResult.FAILED)

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
        val (method, target) = requestLine.split(' ').let { it.getOrNull(0).orEmpty() to it.getOrNull(1).orEmpty() }
        val path = target.substringBefore('?')
        val query = Uri.parse("http://x$target")
        val out = client.getOutputStream()
        val ctx = app ?: return respond(out, 503, "")
        // Защита кодом: что играет и «продолжить здесь» — только сопряжённым устройствам.
        if ((path == "/now" || path == "/play") && !HandoffAuth.isAuthorized(headers[TOKEN_HEADER.lowercase()])) {
            return respond(out, 401, "")
        }
        when {
            method == "GET" && path == "/pair/challenge" -> {
                val clientId = query.getQueryParameter("id").orEmpty()
                val name = query.getQueryParameter("name").orEmpty().ifBlank { "?" }.take(64)
                val nonce = HandoffAuth.newChallenge(clientId, name)
                if (nonce == null) {
                    respond(out, 403, "") // недавно отклонён — не беспокоим хозяина
                } else {
                    respond(out, 200, JSONObject().put("nonce", nonce).toString(), "application/json")
                    PairRequests.show(ctx, nonce, name)
                }
            }
            method == "GET" && path == "/pair/status" -> {
                val nonce = query.getQueryParameter("nonce").orEmpty()
                val (decision, token) = HandoffAuth.status(nonce) ?: return respond(out, 404, "")
                val json = JSONObject().put("state", decision.name.lowercase())
                token?.let { json.put("token", it) }
                if (decision != HandoffAuth.Decision.PENDING) PairRequests.cancel(ctx, nonce)
                respond(out, 200, json.toString(), "application/json")
            }
            method == "POST" && path == "/pair" -> {
                val json = JSONObject(readBody(input, headers))
                val (result, token) = HandoffAuth.verify(
                    nonce = json.optString("nonce"),
                    clientId = json.optString("id"),
                    clientName = json.optString("name").take(64),
                    proof = json.optString("proof"),
                )
                when (result) {
                    HandoffAuth.PairResult.OK -> {
                        PairRequests.cancel(ctx, json.optString("nonce"))
                        respond(out, 200, JSONObject().put("token", token).toString(), "application/json")
                    }
                    HandoffAuth.PairResult.WRONG_CODE -> respond(out, 403, "")
                    HandoffAuth.PairResult.LOCKED -> respond(out, 429, "")
                    HandoffAuth.PairResult.FAILED -> respond(out, 400, "")
                }
            }
            method == "GET" && path == "/now" -> {
                val now = Player2160.nowPlaying.value?.let { shareable(ctx, it) }
                if (now == null) respond(out, 204, "") else respond(out, 200, toJson(now).toString(), "application/json")
            }
            method == "POST" && path == "/play" -> {
                val json = JSONObject(readBody(input, headers))
                respond(out, 200, "{}", "application/json")
                val from = json.optString("from", "?")
                HandoffReceiveActivity.show(ctx, json.toString(), from)
            }
            // «Поделиться приложением» по Wi-Fi: сам APK, без защиты кодом (это приложение, не просмотр).
            method == "GET" && path.startsWith("/app") -> {
                serveFile(ctx, Uri.fromFile(tv.p2160.app.share.ShareApp.sharedApk(ctx)), headers["range"], out,
                    type = "application/vnd.android.package-archive", fileName = tv.p2160.app.share.ShareApp.fileName())
            }
            method == "GET" && path.startsWith("/stream/") -> {
                val token = path.removePrefix("/stream/").substringBefore('/')
                val uri = shared[token] ?: return respond(out, 404, "")
                serveFile(ctx, uri, headers["range"], out)
            }
            else -> respond(out, 404, "")
        }
    }

    private fun readBody(input: BufferedInputStream, headers: Map<String, String>): String {
        val length = headers["content-length"]?.toIntOrNull()?.coerceAtMost(64 * 1024) ?: 0
        val body = ByteArray(length)
        var r = 0
        while (r < length) { val n = input.read(body, r, length - r); if (n < 0) break; r += n }
        return String(body, 0, r)
    }

    private fun serveFile(
        context: Context,
        uri: Uri,
        range: String?,
        out: OutputStream,
        type: String = "application/octet-stream",
        fileName: String? = null,
    ) {
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
                append("Content-Type: $type\r\n")
                if (fileName != null) append("Content-Disposition: attachment; filename=\"$fileName\"\r\n")
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
        val reason = when (code) {
            200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"
            404 -> "Not Found"; 429 -> "Too Many Requests"; else -> "Error"
        }
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

    /** `http://<IPv4>:<порт>` сервера этого устройства или null, если сервер не запущен / нет сети. */
    fun baseUrl(): String? {
        val port = server?.localPort ?: return null
        val ip = localIpv4() ?: return null
        return "http://$ip:$port"
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
            setAttribute("auth", if (HandoffAuth.required) "1" else "0")
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
                            val locked = r.attributes["auth"]?.let { String(it) } == "1"
                            val peer = Peer(id, r.serviceName, host, r.port, locked)
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
