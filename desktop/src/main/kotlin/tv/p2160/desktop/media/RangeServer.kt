package tv.p2160.desktop.media

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Произвольный доступ, из которого локальный HTTP отдаёт Range-запросы libmpv. */
interface ByteSource : AutoCloseable {
    val length: Long
    /** -1, если смещение за концом. Может блокироваться, пока данные не появятся. */
    fun read(offset: Long, dst: ByteArray, dstOff: Int, len: Int): Int

    /**
     * Поток на один HTTP-запрос. Реализация по умолчанию отдаёт этот же объект,
     * и сервер его не закрывает. Торрент возвращает отдельный поток.
     */
    fun openReader(): ByteSource = this
}

/**
 * Сервер только на 127.0.0.1. Так SMB и торрент попадают в libmpv как обычный HTTP
 * с перемоткой, без отдельного протокола.
 */
class RangeServer : AutoCloseable {
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val sources = ConcurrentHashMap<String, ByteSource>()
    val port: Int = server.address.port

    init {
        server.executor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "range-http").apply { isDaemon = true }
        }
        server.createContext("/media") { exchange ->
            try {
                serve(exchange)
            } catch (e: IOException) {
                exchange.close()
            } catch (e: Exception) {
                runCatching {
                    val msg = (e.message ?: "error").toByteArray()
                    exchange.sendResponseHeaders(500, msg.size.toLong())
                    exchange.responseBody.use { it.write(msg) }
                }
            }
        }
        server.start()
    }

    fun publish(source: ByteSource): String {
        val id = UUID.randomUUID().toString()
        sources[id] = source
        return "http://127.0.0.1:$port/media/$id"
    }

    fun unpublish(url: String) {
        val id = url.substringAfterLast('/')
        sources.remove(id)?.let { runCatching { it.close() } }
    }

    private fun serve(exchange: HttpExchange) {
        val id = exchange.requestURI.path.removePrefix("/media/").substringBefore('/')
        val source = sources[id]
        if (source == null) {
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
            return
        }
        val reader = source.openReader()
        val owned = reader !== source
        try {
            val length = reader.length
            val range = exchange.requestHeaders.getFirst("Range")
            val span = parseRange(range, length)
            if (span == null) {
                exchange.sendResponseHeaders(416, -1)
                exchange.close()
                return
            }
            val (start, end) = span
            val count = end - start
            val partial = range != null
            exchange.responseHeaders.add("Accept-Ranges", "bytes")
            exchange.responseHeaders.add("Content-Type", "application/octet-stream")
            if (partial) {
                exchange.responseHeaders.add("Content-Range", "bytes $start-${end - 1}/$length")
                exchange.sendResponseHeaders(206, count)
            } else {
                exchange.sendResponseHeaders(200, count)
            }
            if (exchange.requestMethod.equals("HEAD", true)) {
                exchange.close()
                return
            }
            exchange.responseBody.use { out ->
                val buf = ByteArray(256 * 1024)
                var pos = start
                while (pos < end) {
                    val want = minOf(buf.size.toLong(), end - pos).toInt()
                    val n = reader.read(pos, buf, 0, want)
                    if (n < 0) break
                    if (n == 0) throw IOException("empty read at $pos")
                    out.write(buf, 0, n)
                    pos += n
                }
            }
        } finally {
            if (owned) runCatching { reader.close() }
        }
    }

    override fun close() {
        sources.values.forEach { runCatching { it.close() } }
        sources.clear()
        server.stop(0)
    }
}

/** Диапазон [start, end). null, если заголовок Range бессмысленный. */
fun parseRange(header: String?, length: Long): Pair<Long, Long>? {
    if (length < 0) return null
    if (header.isNullOrBlank()) return 0L to length
    val spec = header.trim().removePrefix("bytes=").substringBefore(',')
    if (spec.startsWith("-")) {
        val tail = spec.drop(1).toLongOrNull() ?: return null
        if (tail <= 0) return null
        val start = (length - tail).coerceAtLeast(0)
        return start to length
    }
    val start = spec.substringBefore('-').toLongOrNull() ?: return null
    if (start < 0 || start >= length) return null
    val endText = spec.substringAfter('-', "")
    val endInclusive = if (endText.isEmpty()) length - 1 else endText.toLongOrNull() ?: return null
    val end = (endInclusive + 1).coerceAtMost(length)
    if (end <= start) return null
    return start to end
}
