package tv.p2160.core.subtitle

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.media3.common.MimeTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

object SubtitleSupport {

    val EXTENSIONS = listOf("srt", "ass", "ssa", "vtt", "ttml", "dfxp", "xml")

    fun mimeForName(name: String?): String? = when (name?.substringAfterLast('.', "")?.lowercase()) {
        "srt" -> MimeTypes.APPLICATION_SUBRIP
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        "vtt" -> MimeTypes.TEXT_VTT
        "ttml", "dfxp", "xml" -> MimeTypes.APPLICATION_TTML
        else -> null
    }

    fun displayName(context: Context, uri: Uri): String? {
        if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) return c.getString(0) }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    /** Язык из имени файла: `Movie.ru.srt`, `Movie.eng.forced.ass`. */
    fun languageFromName(name: String?): String? {
        val parts = name?.substringBeforeLast('.')?.split('.', '_')?.drop(1) ?: return null
        return parts.reversed().firstOrNull { it.length in 2..3 && it.all(Char::isLetter) }?.lowercase()
    }

    /**
     * Приводит текстовые субтитры к UTF-8. Русские .srt очень часто лежат в cp1251,
     * а Media3 по умолчанию читает их как UTF-8 — получаются «кракозябры».
     * Возвращает URI на нормализованную копию в кэше или исходный URI, если перекодировать не нужно.
     */
    suspend fun normalize(context: Context, uri: Uri, headers: Map<String, String>): Uri =
        withContext(Dispatchers.IO) {
            runCatching {
                val bytes = readBytes(context, uri, headers) ?: return@runCatching uri
                val text = decode(bytes) ?: return@runCatching uri
                val name = displayName(context, uri) ?: "sub.srt"
                val ext = name.substringAfterLast('.', "srt").lowercase()
                val dir = File(context.cacheDir, "subtitles").apply { mkdirs() }
                val file = File(dir, "${sha1(uri.toString())}.$ext")
                file.writeText(text, Charsets.UTF_8)
                Uri.fromFile(file)
            }.getOrDefault(uri)
        }

    /** Ищет субтитры рядом с локальным видео: `Movie.mkv` → `Movie.srt`, `Movie.ru.srt`… */
    fun findSidecars(videoUri: Uri): List<Uri> {
        if (videoUri.scheme != "file") return emptyList()
        val video = File(videoUri.path ?: return emptyList())
        val base = video.nameWithoutExtension
        val dir = video.parentFile ?: return emptyList()
        return dir.listFiles().orEmpty()
            .filter { it.isFile && it.extension.lowercase() in EXTENSIONS && it.name.startsWith(base) }
            .sortedBy { it.name }
            .map { Uri.fromFile(it) }
    }

    private fun readBytes(context: Context, uri: Uri, headers: Map<String, String>): ByteArray? {
        val limit = 8 * 1024 * 1024
        return when (uri.scheme?.lowercase()) {
            "http", "https" -> {
                val conn = URL(uri.toString()).openConnection() as HttpURLConnection
                headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                conn.inputStream.use { it.readNBytesCompat(limit) }
            }
            else -> context.contentResolver.openInputStream(uri)?.use { it.readNBytesCompat(limit) }
        }
    }

    /** null — перекодирование не требуется (уже UTF-8/UTF-16 с BOM). */
    internal fun decode(bytes: ByteArray): String? {
        if (bytes.size >= 2 && (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ||
                bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())
        ) return null
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) return null
        if (isValidUtf8(bytes)) return null
        return String(bytes, Charset.forName("windows-1251"))
    }

    private fun isValidUtf8(bytes: ByteArray): Boolean = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
        true
    } catch (_: CharacterCodingException) {
        false
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (out.size() < limit) {
            val n = read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
