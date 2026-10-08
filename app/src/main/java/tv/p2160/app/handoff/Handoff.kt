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
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Другой экземпляр 2160 Player в локальной сети. [locked] — на нём включена защита кодом. */
data class Peer(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val locked: Boolean = false,
    /** Сейчас транслирует камеру (TXT `cam=1`): видно и без сопряжения, адрес — после него. */
    val camera: Boolean = false,
)

/** Камера, которую сейчас транслирует другое устройство с 2160 Player ([tv.p2160.app.camera.CameraStream]). */
data class RemoteCamera(
    val peer: Peer,
    /** Адрес потока; null — нужен код/разрешение ([Handoff.needsCode]), адрес узнаем после сопряжения. */
    val uri: Uri?,
    val viewers: Int,
)

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
 * - `GET /camera` — адрес трансляции камеры этого устройства (`rtsp://…`), 204 — камера не транслируется;
 * - `GET /watch?k=код` — страница «Смотреть трансляцию» для QR-кода (камера телефона откроет её в браузере,
 *   кнопка — 2160 Player или скачивание APK); `k` — текущий код защиты (без защиты не нужен);
 * - `GET /hello?id&name&port&auth&cam` — «я тоже здесь»: найдя устройство по mDNS, плеер сообщает о себе
 *   напрямую. Так обнаружение работает и там, где роутер пропускает multicast только в одну сторону
 *   (Wi-Fi ↔ провод): записи без «привета» и mDNS дольше [HELLO_TTL_MS] удаляются;
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
    /** Когда последний раз слышали устройство (mDNS или /hello), по id. */
    private val lastSeen = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** Флаг «транслирует камеру» из последнего «привета»: свежее TXT mDNS, которое может прийти из кэша. */
    private val helloCamera = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private const val HELLO_INTERVAL_MS = 60_000L
    private const val HELLO_TTL_MS = 3 * 60_000L
    @Volatile private var helloLoop: java.util.concurrent.Future<*>? = null
    /** Неверные коды на `/watch`: после 5 подряд — минута ожидания (перебор 6 цифр по сети). */
    private var watchFailures = 0
    private var watchLockedUntil = 0L
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
        // Раз в минуту — «привет» всем известным и чистка тех, кого давно не слышно.
        helloLoop = pool.submit {
            while (server === socket && !socket.isClosed) {
                runCatching { Thread.sleep(HELLO_INTERVAL_MS) }.onFailure { return@submit }
                val now = System.currentTimeMillis()
                _peers.value = _peers.value.filter { now - (lastSeen[it.id] ?: now) < HELLO_TTL_MS }
                _peers.value.forEach(::hello)
            }
        }
    }

    @Synchronized
    fun stop() {
        runCatching { registration?.let { nsd?.unregisterService(it) } }
        runCatching { discovery?.let { nsd?.stopServiceDiscovery(it) } }
        registration = null
        discovery = null
        runCatching { server?.close() }
        server = null
        helloLoop?.cancel(true)
        helloLoop = null
        _peers.value = emptyList()
        lastSeen.clear()
        helloCamera.clear()
    }

    /** Обновить TXT-запись mDNS без перезапуска сервера (порт тот же): например, началась/кончилась трансляция камеры. */
    @Synchronized
    fun reannounce() {
        val ctx = app ?: return
        val port = server?.localPort ?: return
        runCatching { registration?.let { nsd?.unregisterService(it) } }
        registration = null
        register(port, deviceName(ctx))
        // Тем, до кого не доходит наш multicast, — напрямую.
        _peers.value.forEach { peer -> pool.execute { hello(peer) } }
    }

    /** Сообщить [peer] о себе (`GET /hello`). Блокирующий вызов. */
    private fun hello(peer: Peer) {
        val ctx = app ?: return
        val port = server?.localPort ?: return
        runCatching {
            val q = "/hello?id=${Uri.encode(deviceId)}&name=${Uri.encode(deviceName(ctx))}&port=$port" +
                "&auth=${if (HandoffAuth.required) 1 else 0}&cam=${if (tv.p2160.app.camera.CameraStream.isServing) 1 else 0}"
            val conn = connect(peer, q)
            val code = conn.responseCode
            conn.disconnect()
            Log.d(TAG, "hello -> ${peer.name} ${peer.host}:${peer.port}: $code")
        }.onFailure {
            Log.d(TAG, "hello -> ${peer.name} ${peer.host}:${peer.port} failed: ${it.message}")
            // Недоступно (ушло из сети, чужая подсеть эмулятора): убираем, вернётся с mDNS или «приветом».
            _peers.value = _peers.value.filterNot { p -> p.id == peer.id && p.host == peer.host && p.port == peer.port }
        }
    }

    /** Добавить или обновить устройство в списке. */
    private fun upsertPeer(peer: Peer) {
        lastSeen[peer.id] = System.currentTimeMillis()
        _peers.value = _peers.value.filterNot { it.id == peer.id } + peer
    }

    /** Список камер в сети: у сопряжённых — с адресом, у остальных — только «есть камера» (адрес после сопряжения). */
    fun fetchCameras(): List<RemoteCamera> = _peers.value.filter { it.camera }.mapNotNull { peer ->
        if (needsCode(peer)) RemoteCamera(peer, null, 0) else fetchCamera(peer)
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

    /** Трансляция камеры на [peer]. Блокирующий вызов. null — не транслирует или нет доступа (нужен код). */
    fun fetchCamera(peer: Peer): RemoteCamera? = runCatching {
        if (needsCode(peer)) return null
        val conn = connect(peer, "/camera")
        try {
            if (conn.responseCode == 401) { HandoffAuth.dropToken(peer.id); return null }
            if (conn.responseCode != 200) return null
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val url = json.optString("url").takeIf { it.startsWith("rtsp://") } ?: return null
            RemoteCamera(peer, Uri.parse(url), json.optInt("viewers"))
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
        // Только локальная сеть: подключение, пришедшее через мобильный интерфейс, не обслуживаем.
        if (!Lan.isLocal(app ?: return, client.localAddress)) {
            Log.w(TAG, "not LAN: ${client.localAddress}")
            return
        }
        val remoteHost = client.inetAddress?.hostAddress
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
        if ((path == "/now" || path == "/play" || path == "/camera") && !HandoffAuth.isAuthorized(headers[TOKEN_HEADER.lowercase()])) {
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
            method == "GET" && path == "/hello" -> {
                val id = query.getQueryParameter("id").orEmpty()
                val port = query.getQueryParameter("port")?.toIntOrNull()
                if (id.isBlank() || id == deviceId || port == null || remoteHost == null) return respond(out, 400, "")
                val known = _peers.value.any { it.id == id }
                Log.d(TAG, "hello <- ${query.getQueryParameter("name")} $remoteHost:$port cam=${query.getQueryParameter("cam")}")
                helloCamera[id] = query.getQueryParameter("cam") == "1"
                upsertPeer(
                    Peer(
                        id = id,
                        name = query.getQueryParameter("name").orEmpty().ifBlank { "?" }.take(64),
                        host = remoteHost,
                        port = port,
                        locked = query.getQueryParameter("auth") == "1",
                        camera = query.getQueryParameter("cam") == "1",
                    )
                )
                respond(out, 204, "")
                // Он нас знает, а мы его только что узнали — ответный «привет», чтобы он узнал наш порт/флаги.
                if (!known) _peers.value.firstOrNull { it.id == id }?.let { p -> pool.execute { hello(p) } }
            }
            method == "GET" && path == "/watch" -> {
                val cam = (tv.p2160.app.camera.CameraStream.state.value as? tv.p2160.app.camera.CameraStreamState.Streaming)
                    ?.takeIf { it.protocol == tv.p2160.app.camera.StreamProtocol.RTSP }
                val t = tv.p2160.core.i18n.I18n.get(ctx).current
                if (cam == null) return respond(out, 404, simplePage(t["camera.watch_none"]), "text/html; charset=utf-8")
                val now = System.currentTimeMillis()
                val ok = synchronized(this) {
                    if (now < watchLockedUntil) return respond(out, 429, simplePage(t["camera.watch_wait"]), "text/html; charset=utf-8")
                    val good = !HandoffAuth.required || query.getQueryParameter("k") == HandoffAuth.currentCode()
                    if (good) watchFailures = 0 else if (++watchFailures >= 5) { watchFailures = 0; watchLockedUntil = now + 60_000 }
                    good
                }
                if (!ok) return respond(out, 403, simplePage(t["camera.watch_code"]), "text/html; charset=utf-8")
                val page = tv.p2160.app.camera.WatchPage.html(t, deviceName(ctx), cam.urlWithAuth, tv.p2160.app.share.ShareApp.wifiUrl())
                respond(out, 200, page, "text/html; charset=utf-8")
            }
            method == "GET" && path == "/camera" -> {
                val cam = (tv.p2160.app.camera.CameraStream.state.value as? tv.p2160.app.camera.CameraStreamState.Streaming)
                    ?.takeIf { it.protocol == tv.p2160.app.camera.StreamProtocol.RTSP }
                if (cam == null) respond(out, 204, "")
                // Адрес с паролем: сюда доходят только сопряжённые устройства (или защита выключена).
                else respond(out, 200, JSONObject().put("url", cam.urlWithAuth).put("viewers", cam.clients).toString(), "application/json")
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

    /** Короткая страница с одним сообщением (для `/watch`). */
    private fun simplePage(text: String) =
        "<!doctype html><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<body style=\"background:#0e0e10;color:#eee;font-family:system-ui;padding:24px\"><p>" +
            text.replace("<", "&lt;") + "</p></body>"

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
        "smb", "http", "https", "rtsp" -> now
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

    /** IPv4 в локальной сети (Wi-Fi, Ethernet, точка доступа); адреса мобильной сети не отдаём. */
    private fun localIpv4(): String? = app?.let { Lan.ipv4(it) }

    // region mDNS

    private fun register(port: Int, name: String) {
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = SERVICE_TYPE
            this.port = port
            setAttribute("id", deviceId)
            setAttribute("auth", if (HandoffAuth.required) "1" else "0")
            setAttribute("cam", if (tv.p2160.app.camera.CameraStream.isServing) "1" else "0")
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
                            val camera = helloCamera[id] ?: (r.attributes["cam"]?.let { String(it) } == "1")
                            val peer = Peer(id, r.serviceName, host, r.port, locked, camera)
                            upsertPeer(peer)
                            // Нашли (или оно перезапустилось на новом порту) — сразу «привет»: вдруг наш multicast до него не доходит.
                            pool.execute { hello(peer) }
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
