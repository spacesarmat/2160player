package tv.p2160.core.iptv

import java.net.URI

/** Канал из IPTV-плейлиста. */
data class IptvChannel(
    /** Стабильный ключ внутри плейлиста (для избранного и «последнего канала»): URL, у повторов — с суффиксом. */
    val id: String,
    val name: String,
    val url: String,
    /** Группы из `group-title` (через `;`) или `#EXTGRP`; пусто — без группы. */
    val groups: List<String> = emptyList(),
    val tvgId: String? = null,
    val tvgName: String? = null,
    val logo: String? = null,
    /** Номер из `tvg-chno`. */
    val number: Int? = null,
    val userAgent: String? = null,
    val referrer: String? = null,
    /** Прочие HTTP-заголовки (`#EXTHTTP`, `#KODIPROP`, `url|Header=…`). */
    val headers: Map<String, String> = emptyMap(),
    /** Архив: `catchup`, `catchup-days`, `catchup-source`. */
    val catchup: String? = null,
    val catchupDays: Int? = null,
    val catchupSource: String? = null,
    /** Все атрибуты `#EXTINF` как есть (ключи в нижнем регистре). */
    val attributes: Map<String, String> = emptyMap(),
) {
    val group: String? get() = groups.firstOrNull()

    /** Все заголовки запроса к потоку, включая User-Agent и Referer. */
    fun httpHeaders(): Map<String, String> = buildMap {
        putAll(headers)
        userAgent?.let { put("User-Agent", it) }
        referrer?.let { put("Referer", it) }
    }
}

/** Разобранный плейлист. */
data class M3uPlaylist(
    val channels: List<IptvChannel>,
    /** Адреса EPG из заголовка (`url-tvg`, `x-tvg-url`, `tvg-url`), могут быть перечислены через запятую. */
    val epgUrls: List<String> = emptyList(),
    /** Атрибуты строки `#EXTM3U`. */
    val headerAttributes: Map<String, String> = emptyMap(),
    /** Файл похож на HLS-манифест (сам поток), а не на список каналов. */
    val looksLikeHls: Boolean = false,
) {
    /** Группы в порядке первого появления. */
    val groups: List<String> get() = channels.flatMap { it.groups }.distinct()
}

/**
 * Разбор M3U/M3U8 (расширенный формат IPTV). Терпим к реальным спискам:
 * BOM, CRLF, атрибуты без кавычек и с запятыми внутри, заголовки `#EXTVLCOPT`/`#KODIPROP`/`#EXTHTTP`,
 * `#EXTGRP`, название, перенесённое на следующую строку, относительные URL, повторы.
 */
object M3uParser {

    private val EPG_KEYS = listOf("url-tvg", "x-tvg-url", "tvg-url")
    private val UA_KEYS = setOf("user-agent", "http-user-agent", "tvg-user-agent")
    private val REFERRER_KEYS = setOf("http-referrer", "http-referer", "referrer", "referer")

    /** @param baseUrl адрес самого плейлиста — для относительных ссылок на потоки. */
    fun parse(text: String, baseUrl: String? = null): M3uPlaylist {
        val lines = text.removePrefix("\uFEFF").split("\r\n", "\n", "\r")
        val channels = ArrayList<IptvChannel>()
        val epg = LinkedHashSet<String>()
        var header = emptyMap<String, String>()
        var looksLikeHls = false

        // Состояние текущей записи (между #EXTINF и строкой URL).
        var info: ExtInf? = null
        var pendingGroup: String? = null
        var userAgent: String? = null
        var referrer: String? = null
        val extraHeaders = LinkedHashMap<String, String>()
        val seenIds = HashMap<String, Int>()

        fun reset() {
            info = null; pendingGroup = null; userAgent = null; referrer = null; extraHeaders.clear()
        }

        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#")) {
                val upper = line.uppercase()
                when {
                    upper.startsWith("#EXTM3U") -> {
                        header = parseAttributes(line.substring(7)).first
                        EPG_KEYS.forEach { key -> header[key]?.let { v -> splitUrls(v).forEach(epg::add) } }
                    }
                    upper.startsWith("#EXTINF:") -> {
                        info = parseExtInf(line.substring(8))
                    }
                    upper.startsWith("#EXTGRP:") -> pendingGroup = line.substring(8).trim().ifEmpty { null }
                    upper.startsWith("#EXTVLCOPT:") -> {
                        val (k, v) = keyValue(line.substring(11)) ?: continue
                        when (k.lowercase()) {
                            "http-user-agent" -> userAgent = v
                            "http-referrer", "http-referer" -> referrer = v
                            "http-origin" -> extraHeaders["Origin"] = v
                        }
                    }
                    upper.startsWith("#KODIPROP:") -> {
                        val (k, v) = keyValue(line.substring(10)) ?: continue
                        if (k.endsWith("stream_headers", ignoreCase = true) || k.endsWith("manifest_headers", ignoreCase = true)) {
                            parseHeaderQuery(v).forEach { (hk, hv) -> extraHeaders[hk] = hv }
                        }
                    }
                    upper.startsWith("#EXTHTTP:") -> parseJsonObject(line.substring(9)).forEach { (hk, hv) -> extraHeaders[hk] = hv }
                    upper.startsWith("#EXT-X-TARGETDURATION") || upper.startsWith("#EXT-X-STREAM-INF") ||
                        upper.startsWith("#EXT-X-MEDIA-SEQUENCE") -> looksLikeHls = true
                }
                continue
            }

            // Не URL: продолжение названия, перенесённое на новую строку, или мусор (HTML-ошибка и т. п.).
            val current = info
            if (!looksLikeUrl(line)) {
                if (current != null) info = current.copy(title = (current.title + " " + line).trim())
                continue
            }

            // Строка URL: «адрес|User-Agent=…&Referer=…» (синтаксис Kodi).
            var url = line
            val pipeHeaders = LinkedHashMap<String, String>()
            val pipe = line.indexOf('|')
            if (pipe > 0 && "://" in line.substring(0, pipe)) {
                url = line.substring(0, pipe).trim()
                parseHeaderQuery(line.substring(pipe + 1)).forEach { (k, v) -> pipeHeaders[k] = v }
            }
            url = resolve(url, baseUrl)

            val attrs = current?.attributes.orEmpty()
            val headers = LinkedHashMap<String, String>().apply { putAll(extraHeaders); putAll(pipeHeaders) }
            val ua = headers.removeKey("User-Agent") ?: userAgent ?: UA_KEYS.firstNotNullOfOrNull { attrs[it] }
            val ref = headers.removeKey("Referer") ?: referrer ?: REFERRER_KEYS.firstNotNullOfOrNull { attrs[it] }

            val tvgName = attrs["tvg-name"]?.ifBlank { null }
            val name = current?.title?.ifBlank { null } ?: tvgName ?: nameFromUrl(url)
            val groups = (attrs["group-title"] ?: pendingGroup).orEmpty()
                .split(';').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

            val n = seenIds.merge(url, 1, Int::plus)!!
            channels += IptvChannel(
                id = if (n == 1) url else "$url#$n",
                name = name,
                url = url,
                groups = groups,
                tvgId = attrs["tvg-id"]?.ifBlank { null },
                tvgName = tvgName,
                logo = attrs["tvg-logo"]?.ifBlank { null }?.let { resolve(it, baseUrl) },
                number = attrs["tvg-chno"]?.trim()?.toIntOrNull(),
                userAgent = ua?.ifBlank { null },
                referrer = ref?.ifBlank { null },
                headers = headers,
                catchup = attrs["catchup"] ?: attrs["catchup-type"],
                catchupDays = (attrs["catchup-days"] ?: attrs["timeshift"])?.trim()?.toIntOrNull(),
                catchupSource = attrs["catchup-source"],
                attributes = attrs,
            )
            reset()
        }
        return M3uPlaylist(channels, epg.toList(), header, looksLikeHls && channels.none { it.attributes.isNotEmpty() })
    }

    private data class ExtInf(val title: String, val attributes: Map<String, String>)

    /** `-1 tvg-id="x" group-title="A, B",Название, с запятой` */
    private fun parseExtInf(body: String): ExtInf {
        // Длительность — до первого пробела или запятой.
        var i = 0
        while (i < body.length && body[i] != ' ' && body[i] != ',' && body[i] != '\t') i++
        val (attrs, end) = parseAttributes(body.substring(i))
        val rest = body.substring(i).substring(end)
        val title = if (rest.startsWith(",")) rest.substring(1).trim() else rest.trim()
        return ExtInf(title, attrs)
    }

    /**
     * Атрибуты `key="value"`, `key='value'` или `key=value` до первой запятой вне кавычек.
     * Возвращает атрибуты и позицию, на которой разбор остановился.
     */
    internal fun parseAttributes(s: String): Pair<Map<String, String>, Int> {
        val result = LinkedHashMap<String, String>()
        var i = 0
        val n = s.length
        while (i < n) {
            while (i < n && s[i].isWhitespace()) i++
            if (i >= n || s[i] == ',') break
            val keyStart = i
            while (i < n && s[i] != '=' && s[i] != ',' && !s[i].isWhitespace()) i++
            val key = s.substring(keyStart, i).lowercase()
            if (i >= n || s[i] != '=') {
                // Слово без значения — пропускаем.
                continue
            }
            i++ // '='
            val value: String
            if (i < n && (s[i] == '"' || s[i] == '\'')) {
                val quote = s[i]
                val close = s.indexOf(quote, i + 1)
                if (close < 0) {
                    // Незакрытая кавычка: значение до запятой (или до конца).
                    val comma = s.indexOf(',', i + 1).let { if (it < 0) n else it }
                    value = s.substring(i + 1, comma)
                    i = comma
                } else {
                    value = s.substring(i + 1, close)
                    i = close + 1
                }
            } else {
                val start = i
                while (i < n && !s[i].isWhitespace() && s[i] != ',') i++
                value = s.substring(start, i)
            }
            if (key.isNotEmpty()) result[key] = value.trim()
        }
        return result to i.coerceAtMost(n)
    }

    private fun keyValue(s: String): Pair<String, String>? {
        val eq = s.indexOf('=')
        if (eq <= 0) return null
        return s.substring(0, eq).trim() to s.substring(eq + 1).trim()
    }

    /** `User-Agent=abc&Referer=https%3A%2F%2Fx` → пары с раскодированными значениями. */
    private fun parseHeaderQuery(s: String): List<Pair<String, String>> =
        s.split('&').mapNotNull { part ->
            val (k, v) = keyValue(part) ?: return@mapNotNull null
            canonicalHeader(k) to decode(v)
        }

    /** Минимальный разбор плоского JSON-объекта со строковыми значениями (`#EXTHTTP`). */
    private fun parseJsonObject(s: String): List<Pair<String, String>> =
        Regex("\"((?:[^\"\\\\]|\\\\.)*)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(s).map {
            canonicalHeader(unescapeJson(it.groupValues[1])) to unescapeJson(it.groupValues[2])
        }.toList()

    private fun unescapeJson(s: String) = s.replace("\\/", "/").replace("\\\"", "\"").replace("\\\\", "\\")

    private fun canonicalHeader(k: String): String = when (k.trim().lowercase()) {
        "user-agent" -> "User-Agent"
        "referer", "referrer" -> "Referer"
        "origin" -> "Origin"
        "cookie" -> "Cookie"
        else -> k.trim()
    }

    private fun decode(v: String): String =
        if ('%' in v) runCatching { java.net.URLDecoder.decode(v, "UTF-8") }.getOrDefault(v) else v

    private fun MutableMap<String, String>.removeKey(key: String): String? {
        val k = keys.firstOrNull { it.equals(key, ignoreCase = true) } ?: return null
        return remove(k)
    }

    private fun splitUrls(v: String): List<String> =
        v.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }

    /** Строка после #EXTINF — адрес, а не перенесённое название. */
    private fun looksLikeUrl(line: String): Boolean {
        if (line.any { it == '<' || it == '>' || it == '"' }) return false
        return "://" in line || line.startsWith("/") || (' ' !in line && ('.' in line || '/' in line))
    }

    internal fun resolve(url: String, base: String?): String {
        if (base == null || "://" in url) return url
        if (!base.startsWith("http://", true) && !base.startsWith("https://", true) && !base.startsWith("file:", true)) return url
        return runCatching { URI(base.replace(" ", "%20")).resolve(url.replace(" ", "%20")).toString() }.getOrDefault(url)
    }

    private fun nameFromUrl(url: String): String =
        url.substringBefore('?').trimEnd('/').substringAfterLast('/').substringBeforeLast('.').ifBlank { url }
}
