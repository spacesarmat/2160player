package tv.p2160.core.source.dlna

import java.util.Locale

/** Ресурс элемента (`<res>`): ссылка на файл/поток с параметрами. */
data class DlnaResource(
    val url: String,
    /** `http-get:*:video/x-matroska:DLNA.ORG_OP=01;...` */
    val protocolInfo: String,
    val size: Long? = null,
    val durationMs: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    /** Байт/с (по спецификации UPnP AV). */
    val bitrate: Long? = null,
) {
    private val parts: List<String> = protocolInfo.split(':', limit = 4)
    val protocol: String get() = parts.getOrNull(0).orEmpty().lowercase()
    val mimeType: String? get() = parts.getOrNull(2)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != "*" }
    private val additional: String get() = parts.getOrNull(3).orEmpty()

    /** DLNA.ORG_PN (профиль), например `AVC_MP4_HP_HD_AAC` или `JPEG_TN`. */
    val dlnaProfile: String? get() = dlnaParam("DLNA.ORG_PN")

    /** DLNA.ORG_CI=1 — сервер перекодирует на лету (не оригинал). */
    val isConverted: Boolean get() = dlnaParam("DLNA.ORG_CI") == "1"

    val isSubtitle: Boolean get() = subtitleFormat(mimeType) != null || mimeType?.let { it.startsWith("text/") && !it.contains("html") } == true
    val isImage: Boolean get() = mimeType?.startsWith("image/") == true
    val isVideo: Boolean get() = mimeType?.startsWith("video/") == true
    val isAudio: Boolean get() = mimeType?.startsWith("audio/") == true

    private fun dlnaParam(name: String): String? =
        additional.split(';').firstOrNull { it.startsWith("$name=", true) }?.substringAfter('=')?.trim()
}

/** Внешние субтитры элемента. [format] — расширение (`srt`, `ass`, `vtt`…). */
data class DlnaSubtitle(val url: String, val format: String, val language: String? = null)

enum class DlnaMediaKind { VIDEO, AUDIO, IMAGE, OTHER }

/** Объект ContentDirectory: папка или элемент. */
sealed class DlnaObject {
    abstract val id: String
    abstract val parentId: String?
    abstract val title: String
    abstract val upnpClass: String
    abstract val albumArtUrl: String?
}

data class DlnaContainer(
    override val id: String,
    override val parentId: String?,
    override val title: String,
    override val upnpClass: String,
    val childCount: Int? = null,
    override val albumArtUrl: String? = null,
) : DlnaObject()

data class DlnaItem(
    override val id: String,
    override val parentId: String?,
    override val title: String,
    override val upnpClass: String,
    val resources: List<DlnaResource>,
    val subtitles: List<DlnaSubtitle> = emptyList(),
    override val albumArtUrl: String? = null,
    val date: String? = null,
    val artist: String? = null,
    val album: String? = null,
) : DlnaObject() {

    val kind: DlnaMediaKind
        get() {
            val c = upnpClass.lowercase()
            return when {
                c.startsWith("object.item.videoitem") -> DlnaMediaKind.VIDEO
                c.startsWith("object.item.audioitem") -> DlnaMediaKind.AUDIO
                c.startsWith("object.item.imageitem") -> DlnaMediaKind.IMAGE
                else -> {
                    // Класс не указан или нестандартный — судим по MIME ресурсов.
                    val res = resources.filterNot { it.isSubtitle }
                    when {
                        res.any { it.isVideo } -> DlnaMediaKind.VIDEO
                        res.any { it.isAudio } -> DlnaMediaKind.AUDIO
                        res.any { it.isImage } -> DlnaMediaKind.IMAGE
                        else -> DlnaMediaKind.OTHER
                    }
                }
            }
        }

    /** Ресурс для воспроизведения — см. [DidlParser.bestResource]. */
    val bestResource: DlnaResource? by lazy { DidlParser.bestResource(this) }

    val durationMs: Long? get() = bestResource?.durationMs ?: resources.firstNotNullOfOrNull { it.durationMs }
    val size: Long? get() = bestResource?.size
}

/** Разбор DIDL-Lite (результат Browse). */
object DidlParser {

    fun parse(didl: String): List<DlnaObject> {
        val doc = XmlLite.parse(didl)
        val root = doc.find("DIDL-Lite") ?: doc
        return parse(root)
    }

    /** Разбор уже распарсенного узла `DIDL-Lite` (сервер вложил DIDL в Result без экранирования). */
    fun parse(root: XmlNode): List<DlnaObject> = root.children.mapNotNull { node ->
        when (node.name.lowercase()) {
            "container" -> parseContainer(node)
            "item" -> parseItem(node)
            else -> null
        }
    }

    private fun parseContainer(n: XmlNode): DlnaContainer? {
        val id = n.attr("id") ?: return null
        return DlnaContainer(
            id = id,
            parentId = n.attr("parentID"),
            title = n.childText("title") ?: id,
            upnpClass = n.childText("class") ?: "object.container",
            childCount = n.attr("childCount")?.trim()?.toIntOrNull(),
            albumArtUrl = albumArt(n),
        )
    }

    private fun parseItem(n: XmlNode): DlnaItem? {
        val id = n.attr("id") ?: return null
        val resources = n.children("res").mapNotNull(::parseRes)
        val subtitles = LinkedHashMap<String, DlnaSubtitle>()
        // Samsung: <sec:CaptionInfoEx sec:type="srt">url</sec:CaptionInfoEx> (MiniDLNA, Jellyfin, Serviio, UMS)
        n.children.filter { it.name.equals("CaptionInfoEx", true) || it.name.equals("CaptionInfo", true) }.forEach { c ->
            val url = c.text.trim().takeIf { it.startsWith("http", true) } ?: return@forEach
            val format = subtitleFormat(c.attr("type")) ?: subtitleFormat(extensionOf(url)) ?: return@forEach
            subtitles.putIfAbsent(url, DlnaSubtitle(url, format, language(c)))
        }
        // PacketVideo/Twonky: <pv:subtitleFileUri pv:subtitleFileType="SRT">url</pv:subtitleFileUri>
        n.children("subtitleFileUri").forEach { c ->
            val url = c.text.trim().takeIf { it.startsWith("http", true) } ?: return@forEach
            val format = subtitleFormat(c.attr("subtitleFileType")) ?: subtitleFormat(extensionOf(url)) ?: return@forEach
            subtitles.putIfAbsent(url, DlnaSubtitle(url, format, language(c)))
        }
        // <res protocolInfo="http-get:*:text/srt:*">url</res>
        n.children("res").forEach { r ->
            val res = parseRes(r) ?: return@forEach
            if (!res.isSubtitle) return@forEach
            val format = subtitleFormat(res.mimeType) ?: subtitleFormat(extensionOf(res.url)) ?: return@forEach
            subtitles.putIfAbsent(res.url, DlnaSubtitle(res.url, format, language(r)))
        }
        return DlnaItem(
            id = id,
            parentId = n.attr("parentID"),
            title = n.childText("title") ?: id,
            upnpClass = n.childText("class") ?: "object.item",
            resources = resources,
            subtitles = subtitles.values.toList(),
            albumArtUrl = albumArt(n),
            date = n.childText("date"),
            artist = n.childText("artist") ?: n.childText("creator"),
            album = n.childText("album"),
        )
    }

    private fun parseRes(n: XmlNode): DlnaResource? {
        val url = n.text.trim().takeIf { it.isNotEmpty() } ?: return null
        val (w, h) = parseResolution(n.attr("resolution"))
        return DlnaResource(
            url = url,
            protocolInfo = n.attr("protocolInfo").orEmpty(),
            size = n.attr("size")?.trim()?.toLongOrNull()?.takeIf { it > 0 },
            durationMs = parseDuration(n.attr("duration")),
            width = w,
            height = h,
            bitrate = n.attr("bitrate")?.trim()?.toLongOrNull()?.takeIf { it > 0 },
        )
    }

    private fun albumArt(n: XmlNode): String? =
        n.children("albumArtURI").firstNotNullOfOrNull { it.text.trim().takeIf { t -> t.startsWith("http", true) } }
            ?: n.children("icon").firstNotNullOfOrNull { it.text.trim().takeIf { t -> t.startsWith("http", true) } }

    private fun language(n: XmlNode): String? =
        (n.attr("language") ?: n.attr("lang"))?.trim()?.takeIf { it.isNotEmpty() }

    private fun extensionOf(url: String): String =
        url.substringBefore('?').substringBefore('#').substringAfterLast('/').substringAfterLast('.', "")

    /**
     * Длительность UPnP: `H+:MM:SS[.F+]` или `H+:MM:SS.F0/F1`. Встречается и `MM:SS`, и просто секунды.
     */
    fun parseDuration(raw: String?): Long? {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val parts = s.split(':')
        val secPart = parts.last()
        val seconds: Double = if ('/' in secPart) {
            // SS.F0/F1
            val whole = secPart.substringBefore('.')
            val frac = secPart.substringAfter('.', "").split('/')
            val num = frac.getOrNull(0)?.toDoubleOrNull() ?: 0.0
            val den = frac.getOrNull(1)?.toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0
            (whole.toDoubleOrNull() ?: return null) + num / den
        } else {
            secPart.toDoubleOrNull() ?: return null
        }
        var total = seconds
        val rest = parts.dropLast(1).reversed()
        rest.forEachIndexed { i, p ->
            val v = p.toDoubleOrNull() ?: return null
            total += v * if (i == 0) 60 else 3600
        }
        if (rest.size > 2 || total < 0) return null
        return Math.round(total * 1000).takeIf { it > 0 }
    }

    fun parseResolution(raw: String?): Pair<Int?, Int?> {
        val m = raw?.trim()?.let { Regex("(\\d+)\\s*[xX×]\\s*(\\d+)").find(it) } ?: return null to null
        return m.groupValues[1].toIntOrNull()?.takeIf { it > 0 } to m.groupValues[2].toIntOrNull()?.takeIf { it > 0 }
    }

    /**
     * Лучший ресурс для воспроизведения: только http, без субтитров и миниатюр; предпочтение — оригинал
     * (без DLNA.ORG_CI=1), затем наибольшее разрешение, размер и битрейт, затем порядок сервера.
     */
    fun bestResource(item: DlnaItem): DlnaResource? {
        val http = item.resources.filter { (it.protocol == "http-get" || it.protocol.isEmpty()) && it.url.startsWith("http", true) && !it.isSubtitle }
        val wanted = when (item.kind) {
            DlnaMediaKind.VIDEO -> http.filter { it.isVideo || it.mimeType == null || it.mimeType == "application/octet-stream" || isStream(it) }
            DlnaMediaKind.AUDIO -> http.filter { it.isAudio || it.mimeType == null || it.mimeType == "application/octet-stream" || isStream(it) }
            DlnaMediaKind.IMAGE -> http.filter { it.isImage }
            DlnaMediaKind.OTHER -> http.filterNot { it.isImage }
        }.ifEmpty { http.filterNot { it.isImage && item.kind != DlnaMediaKind.IMAGE } }
        if (wanted.isEmpty()) return null
        return wanted.withIndex().sortedWith(
            compareBy<IndexedValue<DlnaResource>> { if (it.value.isConverted) 1 else 0 }
                .thenByDescending { (it.value.width ?: 0).toLong() * (it.value.height ?: 0) }
                .thenByDescending { it.value.size ?: 0 }
                .thenByDescending { it.value.bitrate ?: 0 }
                .thenBy { it.index }
        ).first().value
    }

    private fun isStream(r: DlnaResource) = r.mimeType?.let { "mpegurl" in it || "dash" in it } == true
}

/** Формат субтитров по MIME или расширению/типу (`srt`, `text/srt`, `application/x-subrip`). */
internal fun subtitleFormat(raw: String?): String? {
    val s = raw?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
    return when {
        "subrip" in s || s == "srt" || s.endsWith("/srt") || s.endsWith("/x-srt") -> "srt"
        "vtt" in s -> "vtt"
        s == "ass" || s.endsWith("/x-ass") || s.endsWith("/ass") -> "ass"
        s == "ssa" || s.endsWith("/x-ssa") || s.endsWith("/ssa") -> "ssa"
        "ttml" in s || "dfxp" in s -> "ttml"
        else -> null
    }
}
