package tv.p2160.core.source.dlna

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.Charset

/** Ответ HTTP: код и тело в виде строки. */
data class HttpResult(val code: Int, val body: String)

/** Минимальный HTTP-клиент для DLNA — подменяется в тестах. */
interface DlnaHttp {
    fun get(url: String): HttpResult
    fun post(url: String, headers: Map<String, String>, body: String): HttpResult
}

class DlnaException(message: String, val code: Int? = null) : IOException(message)

/** Реализация на [HttpURLConnection] — без сторонних зависимостей. */
class UrlConnectionHttp(
    private val connectTimeoutMs: Int = 4000,
    private val readTimeoutMs: Int = 15000,
) : DlnaHttp {

    override fun get(url: String): HttpResult = request(url, "GET", emptyMap(), null)

    override fun post(url: String, headers: Map<String, String>, body: String): HttpResult =
        request(url, "POST", headers, body.toByteArray(Charsets.UTF_8))

    private fun request(url: String, method: String, headers: Map<String, String>, body: ByteArray?): HttpResult {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.requestMethod = method
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Connection", "close")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            // SOAP-ошибки приходят с кодом 500 и телом в errorStream.
            val stream: InputStream? = if (code >= 400) conn.errorStream else conn.inputStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            return HttpResult(code, decode(bytes, conn.contentType))
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val USER_AGENT = "Android/1.0 UPnP/1.0 2160Player/1.0 DLNADOC/1.50"

        /** Кодировка из Content-Type или из XML-пролога; по умолчанию UTF-8. */
        fun decode(bytes: ByteArray, contentType: String?): String {
            val fromHeader = contentType?.let { Regex("charset=\"?([\\w.-]+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
            val head = String(bytes, 0, minOf(bytes.size, 200), Charsets.ISO_8859_1)
            val fromProlog = Regex("encoding=[\"']([\\w.-]+)", RegexOption.IGNORE_CASE).find(head)?.groupValues?.get(1)
            val charset = (fromHeader ?: fromProlog)?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
            return String(bytes, charset)
        }
    }
}

/** Разрешает относительный URL относительно базы (URLBase или адрес описания). */
internal fun resolveUrl(base: String, ref: String?): String? {
    val r = ref?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (r.startsWith("http://", true) || r.startsWith("https://", true)) return r
    // URL(base, ref) корректно обрабатывает базу без пути (`http://h:8200` + `ctl/x`), в отличие от URI.resolve.
    @Suppress("DEPRECATION")
    return runCatching { URL(URL(base), r).toString() }.getOrNull()
        ?: runCatching { URI(base).resolve(r).toString() }.getOrNull()
}
