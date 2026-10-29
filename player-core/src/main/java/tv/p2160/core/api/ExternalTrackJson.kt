package tv.p2160.core.api

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON одной внешней дорожки:
 * `uri` (обязателен), `language`, `name`, `mime`, `id`, `delayMs`, `select`, `charset` (только субтитры).
 *
 * Плейлист передаётся массивом таких JSON-массивов: элемент `i` относится к пункту `i` плейлиста.
 * Одна строка (не массив строк) относится только к стартовому пункту.
 */
object ExternalTrackJson {

    fun parseAudio(raw: String?): List<ExternalAudio> = parse(raw) { obj, uri ->
        ExternalAudio(
            uri = uri,
            name = text(obj, "name"),
            language = text(obj, "language"),
            select = obj.optBoolean("select", false),
            id = text(obj, "id"),
            mimeType = text(obj, "mime"),
            delayMs = obj.optLong("delayMs", 0),
        )
    }

    fun parseSubtitles(raw: String?): List<ExternalSubtitle> = parse(raw) { obj, uri ->
        ExternalSubtitle(
            uri = uri,
            name = text(obj, "name"),
            language = text(obj, "language"),
            select = obj.optBoolean("select", false),
            id = text(obj, "id"),
            mimeType = text(obj, "mime"),
            delayMs = obj.optLong("delayMs", 0),
            charset = text(obj, "charset"),
        )
    }

    /** `am --esa` оставляет `\,` в значении, если запятую экранировали. Для JSON это обычная запятая. */
    fun unescapeAdbCommas(raw: String): String = raw.replace("\\,", ",")

    /** Целый JSON-массив, а не обломок после разрезания `adb --esa` по запятым. */
    fun isJsonArray(raw: String?): Boolean {
        val text = unescapeAdbCommas(raw?.trim().orEmpty())
        if (!text.startsWith("[")) return false
        return runCatching { JSONArray(text) }.isSuccess
    }

    /**
     * `adb shell am start --esa` режет значение по запятым, поэтому один JSON приходит кусками.
     * Куски склеиваются, пока снова не получится массив. Несколько целых массивов остаются по одному на пункт.
     * null: собрать массивы не вышло.
     */
    fun reassemble(parts: List<String>): List<String>? {
        if (parts.isEmpty()) return null
        val clean = parts.map(::unescapeAdbCommas)
        if (clean.all { isJsonArray(it) }) return clean
        val out = mutableListOf<String>()
        val buf = StringBuilder()
        for (part in clean) {
            if (buf.isNotEmpty()) buf.append(',')
            buf.append(part)
            if (isJsonArray(buf.toString())) {
                out += buf.toString()
                buf.setLength(0)
            }
        }
        if (buf.isNotEmpty() || out.isEmpty()) return null
        return out
    }

    fun audioJson(tracks: List<ExternalAudio>): String = json(tracks) { track, obj ->
        putCommon(obj, track.uri, track.name, track.language, track.id, track.mimeType, track.delayMs, track.select)
    }

    fun subtitleJson(tracks: List<ExternalSubtitle>): String = json(tracks) { track, obj ->
        putCommon(obj, track.uri, track.name, track.language, track.id, track.mimeType, track.delayMs, track.select)
        track.charset?.let { obj.put("charset", it) }
    }

    private fun putCommon(
        obj: JSONObject,
        uri: Uri,
        name: String?,
        language: String?,
        id: String?,
        mime: String?,
        delayMs: Long,
        select: Boolean,
    ) {
        obj.put("uri", uri.toString())
        name?.let { obj.put("name", it) }
        language?.let { obj.put("language", it) }
        id?.let { obj.put("id", it) }
        mime?.let { obj.put("mime", it) }
        if (delayMs != 0L) obj.put("delayMs", delayMs)
        if (select) obj.put("select", true)
    }

    private fun <T> json(tracks: List<T>, fill: (T, JSONObject) -> Unit): String {
        val array = JSONArray()
        tracks.forEach { track ->
            val obj = JSONObject()
            fill(track, obj)
            array.put(obj)
        }
        return array.toString()
    }

    private fun <T> parse(raw: String?, build: (JSONObject, Uri) -> T): List<T> {
        val text = unescapeAdbCommas(raw?.trim().orEmpty())
        if (text.isEmpty() || text == "[]") return emptyList()
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val uri = text(obj, "uri")?.let(Uri::parse) ?: continue
                if (uri.scheme.isNullOrBlank() && uri.path.isNullOrBlank()) continue
                add(build(obj, uri))
            }
        }
    }

    private fun text(obj: JSONObject, key: String): String? =
        obj.optString(key).trim().takeIf { it.isNotEmpty() && it != "null" }
}

/** Разбор `tv.p2160.extra.AUDIO_SELECT` / `TEXT_SELECT`. */
object TrackSelect {
    data class Parsed(
        val off: Boolean = false,
        val externalId: String? = null,
        val externalIndex: Int? = null,
        val embeddedIndex: Int? = null,
    )

    fun parse(raw: String?): Parsed? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (value.equals("off", ignoreCase = true)) return Parsed(off = true)
        val colon = value.indexOf(':')
        if (colon <= 0) return null
        val kind = value.substring(0, colon).lowercase()
        val body = value.substring(colon + 1).trim()
        if (body.isEmpty()) return null
        return when (kind) {
            "id" -> Parsed(externalId = body)
            "index" -> body.toIntOrNull()?.takeIf { it >= 0 }?.let { Parsed(externalIndex = it) }
            "embedded" -> body.toIntOrNull()?.takeIf { it >= 0 }?.let { Parsed(embeddedIndex = it) }
            else -> null
        }
    }
}
