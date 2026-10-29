package tv.p2160.core.subtitle

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.media3.common.MimeTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.p2160.core.api.ExternalSubtitle
import tv.p2160.core.source.smb.SmbConnections
import tv.p2160.core.source.smb.SmbPath
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

    /**
     * MIME субтитров: сначала расширение имени, затем тип от провайдера, затем начало файла.
     * `application/octet-stream` и `text/plain` (так DocumentsUI часто помечает .srt) не считаются
     * ответом: у них нет формата, и Media3 не разбирает такой файл как субтитры.
     */
    fun resolveMime(name: String?, reportedMime: String?, header: ByteArray?): String? {
        mimeForName(name)?.let { return it }
        mimeFromReported(reportedMime)?.let { return it }
        if (header != null && header.isNotEmpty()) sniffMime(header)?.let { return it }
        return null
    }

    /** Тип, который можно отдать Media3. `null` - тип ничего не говорит о формате. */
    fun mimeFromReported(reportedMime: String?): String? {
        val mime = reportedMime?.lowercase()?.substringBefore(';')?.trim().orEmpty()
        if (mime.isEmpty() || mime in GENERIC_MIMES) return null
        return when (mime) {
            "application/x-subrip", "text/x-subrip", "application/x-srt" -> MimeTypes.APPLICATION_SUBRIP
            "text/vtt" -> MimeTypes.TEXT_VTT
            "text/x-ssa", "text/x-ass", "application/x-ass", "application/x-ssa" -> MimeTypes.TEXT_SSA
            "application/ttml+xml", "application/xml", "text/xml", "application/dfxp+xml" -> MimeTypes.APPLICATION_TTML
            else -> null
        }
    }

    /** Формат по первым байтам, если имени и типа нет. */
    fun sniffMime(bytes: ByteArray): String? {
        val text = bytes.decodeToString(endIndex = minOf(bytes.size, 512)).removePrefix("\uFEFF").trimStart()
        if (text.startsWith("WEBVTT", ignoreCase = true)) return MimeTypes.TEXT_VTT
        if (text.startsWith("[Script Info]", ignoreCase = true) || text.startsWith("Dialogue:", ignoreCase = true)) {
            return MimeTypes.TEXT_SSA
        }
        val lowerHead = text.take(400).lowercase()
        if (text.startsWith("<tt", ignoreCase = true) || (text.startsWith("<?xml") && "ttml" in lowerHead)) {
            return MimeTypes.APPLICATION_TTML
        }
        if (SRT_START.containsMatchIn(text)) return MimeTypes.APPLICATION_SUBRIP
        return null
    }

    fun extensionForMime(mime: String): String = when (mime) {
        MimeTypes.TEXT_SSA -> "ass"
        MimeTypes.TEXT_VTT -> "vtt"
        MimeTypes.APPLICATION_TTML -> "ttml"
        else -> "srt"
    }

    /**
     * Сохраняет право чтения `content://` после закрытия активити.
     * У `ACTION_OPEN_DOCUMENT` разрешение можно сохранить; у чужого Intent его может не быть - тогда пропуск.
     */
    fun persistReadPermission(context: Context, uri: Uri) {
        if (!uri.scheme.equals("content", ignoreCase = true)) return
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Список субтитров элемента: явные (Intent или уже добавленные), затем запомненные для этого файла,
     * затем файлы рядом, если явного списка в запросе не было.
     */
    fun mergeExternal(
        current: List<ExternalSubtitle>,
        remembered: List<ExternalSubtitle>,
        sidecars: List<ExternalSubtitle>,
        includeSidecars: Boolean,
    ): List<ExternalSubtitle> {
        val out = current.toMutableList()
        for (sub in remembered) if (out.none { it.uri == sub.uri }) out += sub
        if (includeSidecars) for (sub in sidecars) if (out.none { it.uri == sub.uri }) out += sub
        return out
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
     * Копия субтитров в UTF-8 без BOM в кэше. Media3 читает её как `file://`, а не исходный
     * `content://`: у DocumentsUI тип часто `application/octet-stream`, и разрешение на URI
     * провайдера не обязано доживать до потока загрузки плеера.
     * Уже UTF-8 тоже копируется. Если прочитать нельзя, берётся прежняя копия, иначе исходный URI.
     */
    suspend fun normalize(context: Context, uri: Uri, headers: Map<String, String>, name: String? = null): Uri =
        withContext(Dispatchers.IO) {
            val cached = existingCache(context, uri)
            runCatching {
                val bytes = readBytes(context, uri, headers) ?: return@runCatching cached?.let(Uri::fromFile) ?: uri
                val resolvedName = name ?: displayName(context, uri) ?: "sub.srt"
                val reported = if (uri.scheme.equals("content", true)) {
                    runCatching { context.contentResolver.getType(uri) }.getOrNull()
                } else null
                val mime = resolveMime(resolvedName, reported, bytes) ?: MimeTypes.APPLICATION_SUBRIP
                val ext = mimeForName(resolvedName)?.let { extensionForMime(it) }
                    ?: resolvedName.substringAfterLast('.', "").lowercase().takeIf { it in EXTENSIONS }
                    ?: extensionForMime(mime)
                val dir = File(context.cacheDir, "subtitles").apply { mkdirs() }
                val file = File(dir, "${sha1(uri.toString())}.$ext")
                file.writeText(toUnicode(bytes), Charsets.UTF_8)
                Uri.fromFile(file)
            }.getOrElse { error ->
                Log.w(LOG_TAG, "subtitle normalize failed: $uri", error)
                cached?.let(Uri::fromFile) ?: uri
            }
        }

    /** Текст субтитров в Unicode: UTF-8 и UTF-16 как есть (без BOM), иначе windows-1251. */
    internal fun toUnicode(bytes: ByteArray): String {
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, Charsets.UTF_16LE).removePrefix("\uFEFF")
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, Charsets.UTF_16BE).removePrefix("\uFEFF")
        }
        decode(bytes)?.let { return it }
        return String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
    }

    /**
     * Имя субтитров относится к видео с основой [base]: `Movie.srt`, `Movie.ru.srt`.
     * `Movie2.srt` и `Show.S01E10.srt` к `Show.S01E1` не подходят - после основы нужна точка.
     */
    fun matchesStem(base: String, fileName: String): Boolean {
        if (base.isEmpty() || fileName.length <= base.length) return false
        if (!fileName.startsWith(base)) return false
        return fileName[base.length] == '.'
    }

    /**
     * Ищет субтитры рядом с локальным видео: `Movie.mkv` → `Movie.srt`, `Movie.ru.srt`…
     * `content://` из выбора документа не даёт список каталога, поэтому рядом ничего не находится.
     * Такой файл подключают вручную, и он запоминается для этого URI.
     */
    fun findSidecars(context: Context, videoUri: Uri): List<Uri> {
        if (videoUri.scheme == "smb") return findSmbSidecars(context, videoUri)
        if (videoUri.scheme != "file") return emptyList()
        val video = File(videoUri.path ?: return emptyList())
        val base = video.nameWithoutExtension
        val dir = video.parentFile ?: return emptyList()
        return dir.listFiles().orEmpty()
            .filter { it.isFile && it.extension.lowercase() in EXTENSIONS && matchesStem(base, it.name) }
            .sortedBy { it.name }
            .map { Uri.fromFile(it) }
    }

    private fun findSmbSidecars(context: Context, videoUri: Uri): List<Uri> = runCatching {
        val video = SmbPath.fromUri(videoUri) ?: return emptyList()
        val dir = video.parent ?: return emptyList()
        val base = video.name.substringBeforeLast('.')
        SmbConnections.list(context, dir)
            .filter { !it.isDirectory && matchesStem(base, it.name) && it.name.substringAfterLast('.').lowercase() in EXTENSIONS }
            .map { it.path.toUri() }
    }.getOrDefault(emptyList())

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
            "smb" -> SmbPath.fromUri(uri)?.let { path ->
                SmbConnections.openRead(context, path).use { f ->
                    val size = minOf(f.fileInformation.standardInformation.endOfFile, limit.toLong()).toInt()
                    val bytes = ByteArray(size)
                    var read = 0
                    while (read < size) {
                        val n = f.read(bytes, read.toLong(), read, size - read)
                        if (n <= 0) break
                        read += n
                    }
                    bytes.copyOf(read)
                }
            }
            else -> context.contentResolver.openInputStream(uri)?.use { it.readNBytesCompat(limit) }
        }
    }

    /** null - перекодирование не требуется (уже UTF-8/UTF-16 с BOM). */
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

    private fun existingCache(context: Context, uri: Uri): File? {
        val dir = File(context.cacheDir, "subtitles")
        if (!dir.isDirectory) return null
        val prefix = sha1(uri.toString())
        return dir.listFiles()?.filter { it.isFile && it.name.startsWith(prefix) && it.length() > 0 }
            ?.maxByOrNull { it.lastModified() }
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private val SRT_START = Regex("""(?m)^\d+\s*\r?\n\d{2}:\d{2}:\d{2}[,.]\d{3}\s*-->""")
    private val GENERIC_MIMES = setOf(
        "application/octet-stream",
        "binary/octet-stream",
        "application/binary",
        "text/plain",
        "*/*",
    )

    private const val LOG_TAG = "Player2160"
}
