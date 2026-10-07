package tv.p2160.core.source.dlna

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Сообщение SSDP: ответ на M-SEARCH, NOTIFY или сам M-SEARCH. */
data class SsdpMessage(
    val kind: Kind,
    /** Заголовки в нижнем регистре. */
    val headers: Map<String, String>,
) {
    enum class Kind { RESPONSE, NOTIFY, SEARCH }

    val location: String? get() = headers["location"]
    val usn: String? get() = headers["usn"]
    /** Тип: ST в ответе, NT в NOTIFY. */
    val type: String? get() = headers["st"] ?: headers["nt"]
    val nts: String? get() = headers["nts"]
    val isByeBye: Boolean get() = kind == Kind.NOTIFY && nts.equals("ssdp:byebye", true)
    /** `uuid:...` из USN (`uuid:xxx::urn:...`). */
    val udn: String? get() = usn?.substringBefore("::")?.trim()?.takeIf { it.startsWith("uuid:", true) }

    /** Похоже на медиасервер по ST/NT — без загрузки описания. */
    val looksLikeMediaServer: Boolean
        get() = type?.let { it.contains(":MediaServer:", true) || it.contains(":ContentDirectory:", true) } == true

    companion object {
        fun parse(text: String): SsdpMessage? {
            val lines = text.split("\r\n", "\n").map { it.trimEnd() }
            val first = lines.firstOrNull()?.trim() ?: return null
            val kind = when {
                first.startsWith("HTTP/", true) -> {
                    if (first.split(' ').getOrNull(1) != "200") return null
                    Kind.RESPONSE
                }
                first.startsWith("NOTIFY", true) -> Kind.NOTIFY
                first.startsWith("M-SEARCH", true) -> Kind.SEARCH
                else -> return null
            }
            val headers = HashMap<String, String>()
            lines.drop(1).forEach { line ->
                val colon = line.indexOf(':')
                if (colon > 0) headers.putIfAbsent(line.substring(0, colon).trim().lowercase(), line.substring(colon + 1).trim())
            }
            return SsdpMessage(kind, headers)
        }

        fun search(st: String, mx: Int = 2): String =
            "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: $GROUP:$PORT\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: $mx\r\n" +
                "ST: $st\r\n" +
                "USER-AGENT: ${UrlConnectionHttp.USER_AGENT}\r\n" +
                "\r\n"

        const val GROUP = "239.255.255.250"
        const val PORT = 1900
        const val ST_MEDIA_SERVER = "urn:schemas-upnp-org:device:MediaServer:1"
        const val ST_CONTENT_DIRECTORY = "urn:schemas-upnp-org:service:ContentDirectory:1"
        const val ST_ALL = "ssdp:all"
    }
}

/**
 * Захват ресурсов платформы на время поиска. На Android — `WifiManager.MulticastLock`
 * (без него многие устройства отбрасывают входящий multicast), на JVM — ничего.
 */
fun interface MulticastLockProvider {
    /** Захватывает блокировку; возвращает действие освобождения. */
    fun acquire(): () -> Unit

    companion object {
        val NONE = MulticastLockProvider { {} }
    }
}

/** Получатель найденных серверов. Вызывается из фоновых потоков. */
interface DlnaDiscoveryListener {
    fun onFound(server: DlnaServer)
    fun onLost(udn: String) {}
}

/**
 * Поиск медиасерверов: M-SEARCH (MediaServer, ContentDirectory, ssdp:all) по всем IPv4-интерфейсам
 * и приём NOTIFY alive/byebye в течение [timeoutMs]. Для каждого нового LOCATION загружается описание;
 * в результат попадают только устройства с ContentDirectory.
 */
class SsdpDiscovery(
    private val lock: MulticastLockProvider = MulticastLockProvider.NONE,
    private val http: DlnaHttp = UrlConnectionHttp(connectTimeoutMs = 2500, readTimeoutMs = 4000),
    /** Отладка: каждое полученное сообщение SSDP. */
    private val onMessage: ((SsdpMessage) -> Unit)? = null,
) {
    @Volatile private var cancelled = false

    fun cancel() { cancelled = true }

    /** Блокирующий поиск. Возвращает все найденные серверы (без ушедших по byebye). */
    @Suppress("DEPRECATION")
    fun search(timeoutMs: Long = 4000, listener: DlnaDiscoveryListener? = null): List<DlnaServer> {
        cancelled = false
        val release = lock.acquire()
        val found = ConcurrentHashMap<String, DlnaServer>()
        val lost = ConcurrentHashMap.newKeySet<String>()
        val seenLocations = ConcurrentHashMap.newKeySet<String>()
        val fetchers = Executors.newFixedThreadPool(4)
        val sockets = ArrayList<DatagramSocket>()
        try {
            val group = InetAddress.getByName(SsdpMessage.GROUP)
            val interfaces = multicastInterfaces()
            // Сокеты для M-SEARCH: ответы приходят unicast на тот же порт.
            interfaces.forEach { (ni, addr) ->
                runCatching {
                    MulticastSocket(InetSocketAddress(addr, 0)).apply {
                        networkInterface = ni
                        timeToLive = 4
                    }
                }.getOrNull()?.let(sockets::add)
            }
            if (sockets.isEmpty()) runCatching { MulticastSocket(0).apply { timeToLive = 4 } }.getOrNull()?.let(sockets::add)
            // Слушатель NOTIFY на 1900 — порт может быть занят системной службой, тогда работаем без него.
            val notify = runCatching {
                MulticastSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(SsdpMessage.PORT))
                    val targets = interfaces.map { it.first }.ifEmpty { listOf(null) }
                    targets.forEach { ni ->
                        runCatching {
                            if (ni != null) joinGroup(InetSocketAddress(group, SsdpMessage.PORT), ni) else joinGroup(group)
                        }
                    }
                }
            }.getOrNull()

            fun handle(msg: SsdpMessage) {
                onMessage?.invoke(msg)
                if (msg.isByeBye) {
                    msg.udn?.let { udn ->
                        lost += udn
                        if (found.remove(udn) != null) listener?.onLost(udn)
                    }
                    return
                }
                if (msg.kind == SsdpMessage.Kind.SEARCH) return
                val location = msg.location ?: return
                if (!location.startsWith("http", true) || !seenLocations.add(location)) return
                fetchers.execute {
                    if (cancelled) return@execute
                    val server = runCatching {
                        val res = http.get(location)
                        if (res.code in 200..299) DeviceDescriptionParser.parse(res.body, location) else null
                    }.getOrNull() ?: return@execute
                    if (server.udn in lost) return@execute
                    if (found.putIfAbsent(server.udn, server) == null) listener?.onFound(server)
                }
            }

            val deadline = System.currentTimeMillis() + timeoutMs
            val threads = (sockets + listOfNotNull(notify)).map { socket ->
                Thread({ receiveLoop(socket, deadline, ::handle) }, "ssdp-recv").apply { isDaemon = true; start() }
            }
            // Несколько повторов: UDP теряется, а часть серверов отвечает только на свой ST.
            val targets = listOf(SsdpMessage.ST_MEDIA_SERVER, SsdpMessage.ST_CONTENT_DIRECTORY, SsdpMessage.ST_ALL)
            repeat(2) { round ->
                targets.forEach { st ->
                    val bytes = SsdpMessage.search(st).toByteArray(Charsets.US_ASCII)
                    sockets.forEach { s -> runCatching { s.send(DatagramPacket(bytes, bytes.size, group, SsdpMessage.PORT)) } }
                }
                if (round == 0 && !cancelled) Thread.sleep(minOf(700L, timeoutMs / 3))
            }
            threads.forEach { it.join(timeoutMs + 500) }
            notify?.close()
        } finally {
            sockets.forEach { runCatching { it.close() } }
            fetchers.shutdown()
            runCatching { fetchers.awaitTermination(6, TimeUnit.SECONDS) }
            fetchers.shutdownNow()
            runCatching { release() }
        }
        return found.values.sortedBy { it.friendlyName.lowercase() }
    }

    private fun receiveLoop(socket: DatagramSocket, deadline: Long, handle: (SsdpMessage) -> Unit) {
        val buf = ByteArray(8192)
        while (!cancelled) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) break
            try {
                socket.soTimeout = left.toInt().coerceIn(1, 500)
                val packet = DatagramPacket(buf, buf.size)
                socket.receive(packet)
                val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                SsdpMessage.parse(text)?.let(handle)
            } catch (_: SocketTimeoutException) {
            } catch (_: Exception) {
                break
            }
        }
    }

    /** Активные IPv4-интерфейсы с multicast (Wi-Fi, Ethernet), без loopback и VPN-туннелей. */
    private fun multicastInterfaces(): List<Pair<NetworkInterface, InetAddress>> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { runCatching { it.isUp && !it.isLoopback && it.supportsMulticast() && !it.isPointToPoint }.getOrDefault(false) }
            .mapNotNull { ni -> ni.inetAddresses.toList().firstOrNull { it is Inet4Address && !it.isLinkLocalAddress }?.let { ni to it } }
    }.getOrDefault(emptyList())
}
