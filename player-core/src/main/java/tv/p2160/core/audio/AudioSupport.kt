package tv.p2160.core.audio

import android.content.Context
import android.net.Uri
import androidx.media3.common.MimeTypes
import tv.p2160.core.api.ExternalAudio
import tv.p2160.core.source.smb.SmbConnections
import tv.p2160.core.source.smb.SmbPath
import tv.p2160.core.subtitle.ContentSidecars
import tv.p2160.core.subtitle.SubtitleSupport
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Внешние аудиофайлы рядом с видео: то же имя (`Movie.ru.mka`) и папки вроде `Audio` / `RUS Sound`.
 */
object AudioSupport {

    val EXTENSIONS = listOf(
        "mka", "ac3", "eac3", "ec3", "dts", "aac", "m4a", "mp3", "flac", "opus", "ogg", "wav",
    )

    private val FOLDER_NAMES = setOf(
        "audio", "audios", "sound", "sounds", "dub", "dubs", "voice", "voices",
        "track", "tracks", "озвучка", "озвучки", "звук",
    )

    /** Языки, которые пробуем в HTTP-именах, если каталог не отдаёт список. */
    val HTTP_LANGUAGES = listOf("ru", "rus", "en", "eng")

    fun isAudioFile(name: String): Boolean = extension(name) in EXTENSIONS

    fun extension(name: String?): String = name?.substringAfterLast('.', "")?.lowercase().orEmpty()

    /** MIME для элементарных потоков. MKA и AAC отдаются на определение по содержимому. */
    fun mimeForName(name: String?): String? = when (extension(name)) {
        "mp3" -> MimeTypes.AUDIO_MPEG
        "ac3" -> MimeTypes.AUDIO_AC3
        "eac3", "ec3" -> MimeTypes.AUDIO_E_AC3
        "dts" -> MimeTypes.AUDIO_DTS
        "m4a" -> MimeTypes.AUDIO_MP4
        "flac" -> MimeTypes.AUDIO_FLAC
        "ogg" -> MimeTypes.AUDIO_OGG
        "opus" -> MimeTypes.AUDIO_OPUS
        "wav" -> MimeTypes.AUDIO_WAV
        else -> null
    }

    fun isAudioFolder(name: String): Boolean {
        val normalized = name.trim().lowercase()
        if (normalized in FOLDER_NAMES) return true
        return normalized.endsWith(" sound") || normalized.endsWith(" sounds") ||
            normalized.endsWith(" audio") || normalized.endsWith(" dub")
    }

    /** `RUS Sound` -> `rus`. Обычная папка `Audio` язык не задаёт. */
    fun folderLanguage(name: String): String? {
        if (!isAudioFolder(name)) return null
        val first = name.trim().split(Regex("\\s+")).firstOrNull()?.lowercase() ?: return null
        if (first.length !in 2..3 || !first.all { it.isLetter() }) return null
        if (first in FOLDER_NAMES) return null
        return first
    }

    /**
     * Файлы рядом с [base] (`Movie` для `Movie.mkv`): в этой папке и на один уровень в папке озвучки.
     * Второй элемент пары - язык из папки, если его нет в имени файла.
     */
    fun collectDirectory(dir: File, base: String): List<Pair<File, String?>> {
        if (base.isEmpty() || !dir.isDirectory) return emptyList()
        val out = mutableListOf<Pair<File, String?>>()
        for (child in dir.listFiles().orEmpty()) {
            if (child.isFile && accept(base, child.name)) {
                out += child to null
            } else if (child.isDirectory && isAudioFolder(child.name)) {
                val folderLang = folderLanguage(child.name)
                for (nested in child.listFiles().orEmpty()) {
                    if (nested.isFile && accept(base, nested.name)) out += nested to folderLang
                }
            }
        }
        return out.sortedBy { it.first.name.lowercase() }
    }

    /** Имена, которые стоит проверить по HTTP, если каталог не листится. Не больше [limit]. */
    fun httpRelativeNames(base: String, languages: List<String> = HTTP_LANGUAGES, limit: Int = 24): List<String> {
        if (base.isEmpty()) return emptyList()
        val names = LinkedHashSet<String>()
        fun add(name: String) {
            if (names.size < limit) names += name
        }
        for (ext in listOf("mka", "ac3", "eac3", "dts", "m4a", "mp3", "aac", "flac", "opus", "ogg", "wav")) {
            add("$base.$ext")
        }
        val short = listOf("mka", "ac3", "m4a", "mp3")
        for (lang in languages) {
            for (ext in short) add("$base.$lang.$ext")
        }
        for (ext in listOf("mka", "ac3", "m4a")) add("Audio/$base.$ext")
        for (ext in listOf("mka", "ac3")) {
            add("RUS Sound/$base.$ext")
            add("ENG Sound/$base.$ext")
        }
        return names.toList()
    }

    fun siblingUri(video: Uri, relative: String): Uri {
        val folder = video.pathSegments.dropLast(1).toMutableList()
        relative.split('/').filter { it.isNotEmpty() }.forEach { folder += it }
        val builder = Uri.Builder().scheme(video.scheme).encodedAuthority(video.encodedAuthority)
        folder.forEach(builder::appendPath)
        return builder.build()
    }

    fun findSidecars(context: Context, videoUri: Uri, headers: Map<String, String>): List<ExternalAudio> {
        return when (videoUri.scheme?.lowercase()) {
            "file" -> fileSidecars(videoUri)
            "smb" -> smbSidecars(context, videoUri)
            "content" -> contentSidecars(context, videoUri)
            "http", "https" -> if (videoUri.query.isNullOrEmpty()) httpSidecars(videoUri, headers) else emptyList()
            else -> emptyList()
        }
    }

    /** `content://` без читаемого каталога: ту же папку один раз спрашивает [tv.p2160.core.subtitle.SubtitleFolders]. */
    fun contentNeedsFolder(context: Context, videoUri: Uri): Boolean {
        if (!videoUri.scheme.equals("content", true)) return false
        val disk = ContentSidecars.diskFile(context, videoUri)
        if (disk?.parentFile?.listFiles() != null) return false
        return ContentSidecars.search(context, videoUri, EXTENSIONS.toSet()).askForFolder
    }

    /** Файл открывается. Для явных ссылок таймаут больше, чем для угаданных соседей. */
    fun reachable(context: Context, uri: Uri, headers: Map<String, String>, timeoutMs: Int): Boolean {
        return runCatching {
            when (uri.scheme?.lowercase()) {
                "file" -> {
                    val file = File(uri.path ?: return false)
                    file.isFile && file.length() > 0L
                }
                "smb" -> {
                    val path = SmbPath.fromUri(uri) ?: return false
                    SmbConnections.openRead(context, path).use { it.fileInformation.standardInformation.endOfFile >= 0L }
                }
                "http", "https" -> {
                    val code = httpStatus(uri.toString(), headers, timeoutMs)
                    code in 200..299 || code == 416
                }
                "content" -> context.contentResolver.openInputStream(uri)?.use { it.read() >= 0 } == true
                else -> false
            }
        }.getOrDefault(false)
    }

    private fun fileSidecars(videoUri: Uri): List<ExternalAudio> {
        val video = File(videoUri.path ?: return emptyList())
        val dir = video.parentFile ?: return emptyList()
        return collectDirectory(dir, video.nameWithoutExtension).map { (file, folderLang) ->
            fromFileName(Uri.fromFile(file), file.name, folderLang)
        }
    }

    private fun smbSidecars(context: Context, videoUri: Uri): List<ExternalAudio> = runCatching {
        val video = SmbPath.fromUri(videoUri) ?: return emptyList()
        val dir = video.parent ?: return emptyList()
        val base = video.name.substringBeforeLast('.')
        val out = mutableListOf<ExternalAudio>()
        for (entry in SmbConnections.list(context, dir)) {
            if (!entry.isDirectory && accept(base, entry.name)) {
                out += fromFileName(entry.path.toUri(), entry.name, null)
            } else if (entry.isDirectory && isAudioFolder(entry.name)) {
                val folderLang = folderLanguage(entry.name)
                for (nested in runCatching { SmbConnections.list(context, entry.path) }.getOrDefault(emptyList())) {
                    if (!nested.isDirectory && accept(base, nested.name)) {
                        out += fromFileName(nested.path.toUri(), nested.name, folderLang)
                    }
                }
            }
        }
        out.sortedBy { it.name?.lowercase() }
    }.getOrDefault(emptyList())

    private fun contentSidecars(context: Context, videoUri: Uri): List<ExternalAudio> {
        val disk = ContentSidecars.diskFile(context, videoUri)
        val parent = disk?.parentFile
        if (disk != null && parent != null && parent.listFiles() != null) {
            return collectDirectory(parent, disk.nameWithoutExtension).map { (file, folderLang) ->
                fromFileName(Uri.fromFile(file), file.name, folderLang)
            }
        }
        return ContentSidecars.search(context, videoUri, EXTENSIONS.toSet()).hits.map { hit ->
            val folder = hit.uri.pathSegments.dropLast(1).lastOrNull()
            fromFileName(hit.uri, hit.name, folder?.let(::folderLanguage))
        }
    }

    fun fromTree(context: Context, videoUri: Uri, treeUri: Uri): List<ExternalAudio> =
        ContentSidecars.listTree(context, videoUri, treeUri, EXTENSIONS.toSet()).map { hit ->
            fromFileName(hit.uri, hit.name, null)
        }

    private fun httpSidecars(videoUri: Uri, headers: Map<String, String>): List<ExternalAudio> {
        val name = videoUri.lastPathSegment ?: return emptyList()
        val base = name.substringBeforeLast('.', name)
        if (base.isEmpty() || base == name) return emptyList()
        val listed = httpListing(videoUri, headers)
        if (listed != null) {
            return listed.mapNotNull { href ->
                val fileName = href.substringAfterLast('/')
                if (!accept(base, fileName)) return@mapNotNull null
                val uri = if (href.contains("://")) Uri.parse(href) else siblingUri(videoUri, href.removePrefix("./"))
                fromFileName(uri, fileName, null)
            }.distinctBy { it.uri }
        }
        val found = mutableListOf<ExternalAudio>()
        var down = 0
        for (relative in httpRelativeNames(base)) {
            val uri = siblingUri(videoUri, relative)
            when (httpProbe(uri.toString(), headers, 1200)) {
                HttpProbe.HIT -> {
                    down = 0
                    val fileName = relative.substringAfterLast('/')
                    val folder = relative.substringBeforeLast('/', "")
                    val folderLang = folder.takeIf { it.isNotEmpty() }?.let(::folderLanguage)
                    found += fromFileName(uri, fileName, folderLang)
                }
                HttpProbe.MISS -> down = 0
                HttpProbe.DOWN -> {
                    down++
                    if (down >= 2) break
                }
            }
        }
        return found
    }

    private fun accept(base: String, fileName: String): Boolean =
        isAudioFile(fileName) && SubtitleSupport.matchesStem(base, fileName)

    private fun fromFileName(uri: Uri, fileName: String, folderLang: String?): ExternalAudio {
        val language = SubtitleSupport.languageFromName(fileName) ?: folderLang
        return ExternalAudio(
            uri = uri,
            name = fileName.substringBeforeLast('.'),
            language = language,
            mimeType = mimeForName(fileName),
        )
    }

    private enum class HttpProbe { HIT, MISS, DOWN }

    private fun httpProbe(url: String, headers: Map<String, String>, timeoutMs: Int): HttpProbe = try {
        val code = open(url, headers, timeoutMs).useCode()
        when {
            code in 200..299 || code == 416 -> HttpProbe.HIT
            code in 400..499 -> HttpProbe.MISS
            else -> HttpProbe.DOWN
        }
    } catch (_: Exception) {
        HttpProbe.DOWN
    }

    private fun httpStatus(url: String, headers: Map<String, String>, timeoutMs: Int): Int = try {
        open(url, headers, timeoutMs).useCode()
    } catch (_: Exception) {
        -1
    }

    private fun open(url: String, headers: Map<String, String>, timeoutMs: Int): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.instanceFollowRedirects = true
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.setRequestProperty("Range", "bytes=0-0")
        headers.forEach { (key, value) -> conn.setRequestProperty(key, value) }
        return conn
    }

    private fun HttpURLConnection.useCode(): Int = try {
        responseCode
    } finally {
        disconnect()
    }

    private val HREF = Regex("""href\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    /** null - это не список каталога. Пустой список - каталог есть, подходящих имён нет. */
    private fun httpListing(video: Uri, headers: Map<String, String>): List<String>? {
        if (video.authority.isNullOrEmpty() && video.scheme.equals("file", true)) return null
        val folder = video.pathSegments.dropLast(1)
        val builder = Uri.Builder().scheme(video.scheme).encodedAuthority(video.encodedAuthority)
        folder.forEach(builder::appendPath)
        val url = builder.build().toString().trimEnd('/') + "/"
        return try {
            val conn = open(url, headers, 2000)
            val code = conn.responseCode
            val type = conn.contentType.orEmpty()
            if (code !in 200..299 || (!type.contains("html", true) && !type.contains("xml", true))) {
                conn.disconnect()
                return null
            }
            val body = conn.inputStream.bufferedReader().use { it.readText().take(200_000) }
            conn.disconnect()
            HREF.findAll(body).map { it.groupValues[1] }.filter { !it.startsWith("?") && ".." != it }.toList()
        } catch (_: Exception) {
            null
        }
    }
}
