package tv.p2160.core.source

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.hierynomus.smbj.share.File
import tv.p2160.core.bluray.FileRandomAccessSource
import tv.p2160.core.bluray.RandomAccessSource
import tv.p2160.core.source.smb.SmbConnections
import tv.p2160.core.source.smb.SmbPath
import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer

/** Произвольный доступ к файлу по URI: file, content, smb, http(s) (через Range-запросы). */
object RandomAccessSources {

    fun open(context: Context, uri: Uri, headers: Map<String, String> = emptyMap()): RandomAccessSource =
        when (uri.scheme?.lowercase()) {
            "file", null -> FileRandomAccessSource(java.io.File(uri.path ?: throw IOException("no path")))
            "smb" -> SmbRandomAccessSource(SmbConnections.openRead(context, SmbPath.fromUri(uri) ?: throw IOException("bad smb uri")))
            "http", "https" -> HttpRandomAccessSource(uri.toString(), headers)
            else -> ContentRandomAccessSource(
                context.contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("cannot open $uri")
            )
        }
}

class SmbRandomAccessSource(private val file: File) : RandomAccessSource {
    override val size: Long = file.fileInformation.standardInformation.endOfFile

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= size) return -1
        return file.read(buffer, position, offset, minOf(length.toLong(), size - position).toInt())
    }

    override fun close() = file.close()
}

class ContentRandomAccessSource(private val fd: ParcelFileDescriptor) : RandomAccessSource {
    private val channel = FileInputStream(fd.fileDescriptor).channel
    override val size: Long = channel.size()

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int =
        channel.read(ByteBuffer.wrap(buffer, offset, length), position)

    override fun close() {
        channel.close()
        fd.close()
    }
}

/** HTTP с Range-запросами. Сервер обязан поддерживать `Accept-Ranges: bytes`. */
class HttpRandomAccessSource(private val url: String, private val headers: Map<String, String>) : RandomAccessSource {
    override val size: Long = run {
        val conn = connect("bytes=0-0")
        try {
            conn.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull()
                ?: throw IOException("server does not support range requests")
        } finally {
            conn.disconnect()
        }
    }

    // Последовательное чтение идёт по одному открытому соединению (`Range: bytes=pos-`);
    // переподключаемся только при переходе в другое место файла.
    private var connection: HttpURLConnection? = null
    private var stream: java.io.InputStream? = null
    private var streamPosition = -1L

    @Synchronized
    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= size) return -1
        if (stream == null || position != streamPosition) reopen(position)
        val wanted = minOf(length.toLong(), size - position).toInt()
        var total = 0
        while (total < wanted) {
            val n = stream!!.read(buffer, offset + total, wanted - total)
            if (n < 0) break
            total += n
        }
        streamPosition = position + total
        return total
    }

    private fun reopen(position: Long) {
        disconnect()
        val conn = connect("bytes=$position-")
        connection = conn
        stream = java.io.BufferedInputStream(conn.inputStream, 256 * 1024)
        streamPosition = position
    }

    private fun disconnect() {
        runCatching { stream?.close() }
        connection?.disconnect()
        stream = null
        connection = null
        streamPosition = -1
    }

    private fun connect(range: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            setRequestProperty("Range", range)
            connectTimeout = 15_000
            readTimeout = 20_000
        }

    @Synchronized
    override fun close() = disconnect()
}
