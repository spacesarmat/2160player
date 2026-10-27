package tv.p2160.core.intro

import android.net.Uri
import java.net.HttpURLConnection
import java.net.URL

/**
 * Один запрос с коротким таймаутом. Любой HTTP-ответ значит, что хост жив.
 * Обрыв соединения - false, без повторов: иначе MediaExtractor уходит в длинный цикл MediaHTTPConnection.
 */
internal fun httpEndpointAnswers(uri: Uri, headers: Map<String, String>): Boolean {
    val conn = (runCatching { URL(uri.toString()).openConnection() }.getOrNull() as? HttpURLConnection) ?: return false
    return try {
        conn.connectTimeout = 4_000
        conn.readTimeout = 4_000
        conn.instanceFollowRedirects = false
        conn.requestMethod = "GET"
        conn.setRequestProperty("Range", "bytes=0-0")
        headers.forEach { (key, value) -> conn.setRequestProperty(key, value) }
        conn.connect()
        conn.responseCode
        true
    } catch (_: Exception) {
        false
    } finally {
        runCatching { conn.disconnect() }
    }
}
