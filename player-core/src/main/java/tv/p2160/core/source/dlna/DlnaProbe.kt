package tv.p2160.core.source.dlna

/**
 * Поиск медиасервера по введённому адресу — для серверов, не видимых по SSDP
 * (Docker с bridge-сетью, другая подсеть, VPN). Принимает:
 * - URL описания (`http://nas:8200/rootDesc.xml`);
 * - `host:port` — проверяются известные пути описаний на этом порту;
 * - `host` — перебираются стандартные порты популярных серверов.
 */
object DlnaProbe {

    /** Известные порты и пути описаний. */
    private val KNOWN = listOf(
        8200 to "/rootDesc.xml",              // MiniDLNA / ReadyMedia
        8096 to JELLYFIN,                     // Jellyfin / Emby (DLNA-плагин)
        32469 to "/DeviceDescription.xml",    // Plex Media Server
        5001 to "/description/fetch",         // Universal Media Server
        50001 to "/desc/device.xml",          // Synology Media Server
        8895 to "/description.xml",           // Serviio (часть версий)
    )
    private val PATHS = listOf("/rootDesc.xml", JELLYFIN, "/DeviceDescription.xml", "/description/fetch", "/desc/device.xml", "/description.xml", "/")

    private const val JELLYFIN = "#jellyfin"

    fun resolve(input: String, http: DlnaHttp = UrlConnectionHttp(connectTimeoutMs = 1500, readTimeoutMs = 4000)): DlnaServer? {
        val raw = input.trim().trimEnd('/')
        if (raw.isEmpty()) return null
        if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) {
            val path = runCatching { java.net.URI(raw).path }.getOrNull().orEmpty()
            if (path.length > 1) return tryLoad(raw, http)
            val uri = runCatching { java.net.URI(raw) }.getOrNull() ?: return null
            val host = uri.host ?: return null
            val scheme = uri.scheme.lowercase()
            return if (uri.port > 0) probePort(scheme, host, uri.port, http) else probeHost(scheme, host, http)
        }
        val hostPort = raw.substringBefore('/')
        val (host, port) = splitHostPort(hostPort) ?: return null
        return if (port != null) probePort("http", host, port, http) else probeHost("http", host, http)
    }

    private fun probeHost(scheme: String, host: String, http: DlnaHttp): DlnaServer? =
        KNOWN.firstNotNullOfOrNull { (port, path) -> candidate(scheme, host, port, path, http) }

    private fun probePort(scheme: String, host: String, port: Int, http: DlnaHttp): DlnaServer? {
        // Сначала путь, типичный для этого порта, затем остальные.
        val preferred = KNOWN.firstOrNull { it.first == port }?.second
        return (listOfNotNull(preferred) + PATHS).distinct().firstNotNullOfOrNull { path -> candidate(scheme, host, port, path, http) }
    }

    private fun candidate(scheme: String, host: String, port: Int, path: String, http: DlnaHttp): DlnaServer? {
        val base = "$scheme://${if (':' in host) "[$host]" else host}:$port"
        if (path == JELLYFIN) {
            // Jellyfin: идентификатор сервера из публичного API, описание — /dlna/<id>/description.xml
            val info = runCatching { http.get("$base/System/Info/Public") }.getOrNull() ?: return null
            if (info.code !in 200..299) return null
            val id = Regex("\"Id\"\\s*:\\s*\"([0-9a-fA-F-]+)\"").find(info.body)?.groupValues?.get(1) ?: return null
            return tryLoad("$base/dlna/$id/description.xml", http)
        }
        return tryLoad(base + path, http)
    }

    private fun tryLoad(url: String, http: DlnaHttp): DlnaServer? = runCatching {
        val res = http.get(url)
        if (res.code in 200..299) DeviceDescriptionParser.parse(res.body, url) else null
    }.getOrNull()

    internal fun splitHostPort(s: String): Pair<String, Int?>? {
        if (s.isBlank()) return null
        // [IPv6]:port
        if (s.startsWith("[")) {
            val host = s.substringAfter('[').substringBefore(']')
            val port = s.substringAfter("]:", "").toIntOrNull()
            return host to port
        }
        val colon = s.lastIndexOf(':')
        if (colon > 0 && s.indexOf(':') == colon) {
            val port = s.substring(colon + 1).toIntOrNull() ?: return null
            return s.substring(0, colon) to port
        }
        return s to null
    }
}
