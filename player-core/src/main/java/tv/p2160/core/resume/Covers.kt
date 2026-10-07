package tv.p2160.core.resume

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import tv.p2160.core.source.RandomAccessSources
import java.io.File
import java.security.MessageDigest

/**
 * Обложки для «Продолжить просмотр» и истории: уменьшенные JPEG в `filesDir/covers`, по ключу записи
 * [ResumeStore] ([ResumeStore.keyFor]). Удаляются вместе с записью.
 *
 * Откуда берём (по порядку, см. [find]):
 * 1. встроенная в файл (обложка MP4/MP3/FLAC — `MediaMetadata.artworkData`);
 * 2. переданная источником — [tv.p2160.core.api.MediaEntry.artworkUri] (DLNA-сервер, встраивающее приложение);
 * 3. картинка рядом с файлом (file, smb, http): `<имя>.jpg`, `<имя>-poster.jpg`, `<имя>-thumb.jpg`, `poster.jpg`, `folder.jpg`,
 *    `cover.jpg`; для серий из папки сезона (`Season 1`, `Сезон 1`, `S01`) — ещё и постер папкой выше.
 */
object Covers {
    private const val MAX_SOURCE_BYTES = 6 * 1024 * 1024
    private const val TARGET_PX = 480

    private val _changes = MutableStateFlow(0L)
    /** Меняется при сохранении/удалении обложки — для перерисовки списков. */
    val changes: StateFlow<Long> = _changes.asStateFlow()

    private fun dir(context: Context) = File(context.applicationContext.filesDir, "covers")

    private fun file(context: Context, key: String): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir(context), hash.take(24) + ".jpg")
    }

    /** Сохранённая обложка записи или null. */
    fun get(context: Context, key: String): File? = file(context, key).takeIf { it.isFile && it.length() > 0 }

    fun delete(context: Context, key: String) {
        if (file(context, key).delete()) _changes.value++
    }

    fun clear(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
        _changes.value++
    }

    /** Сохранить картинку (любой формат, который декодирует Android), уменьшив до ~480 px. */
    fun save(context: Context, key: String, bytes: ByteArray): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= TARGET_PX) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return false
        val target = file(context, key)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        bitmap.recycle()
        val ok = tmp.renameTo(target)
        if (ok) _changes.value++
        return ok
    }

    /**
     * Найти и сохранить обложку для записи [key], если её ещё нет. Блокирует (сеть, SMB) — вызывать
     * с фонового потока. true — обложка есть (была или нашлась).
     */
    suspend fun find(
        context: Context,
        key: String,
        uri: Uri,
        artworkUri: Uri?,
        embedded: ByteArray?,
        headers: Map<String, String>,
    ): Boolean = withContext(Dispatchers.IO) {
        if (get(context, key) != null) return@withContext true
        if (embedded != null && runCatching { save(context, key, embedded) }.getOrDefault(false)) return@withContext true
        val candidates = listOfNotNull(artworkUri) + sidecarCandidates(uri)
        for (candidate in candidates) {
            val bytes = runCatching { read(context, candidate, headers) }.getOrNull() ?: continue
            if (runCatching { save(context, key, bytes) }.getOrDefault(false)) return@withContext true
        }
        false
    }

    /** Картинки рядом с файлом. Только file/smb/http(s): у content:// нет «соседей». */
    fun sidecarCandidates(uri: Uri): List<Uri> {
        val scheme = uri.scheme?.lowercase()
        if (scheme !in setOf("file", "smb", "http", "https")) return emptyList()
        val segments = uri.pathSegments
        if (segments.isEmpty()) return emptyList()
        val name = segments.last()
        val base = name.substringBeforeLast('.', name)
        val folder = segments.dropLast(1)
        val names = listOf("$base.jpg", "$base-poster.jpg", "$base-thumb.jpg", "$base.png", "poster.jpg", "folder.jpg", "cover.jpg", "poster.png", "folder.png")
        val result = names.map { child(uri, folder, it) }.toMutableList()
        // Серия в папке сезона — постер сериала обычно папкой выше.
        val season = folder.lastOrNull().orEmpty()
        if (folder.size > 1 && SEASON.containsMatchIn(season)) {
            listOf("poster.jpg", "folder.jpg", "cover.jpg", "poster.png").forEach { result += child(uri, folder.dropLast(1), it) }
        }
        return result
    }

    private val SEASON = Regex("""(?iu)^(season|сезон|series|серия)\s*\d+$|^s\d{1,2}$""")

    private fun child(uri: Uri, folder: List<String>, name: String): Uri {
        val builder = Uri.Builder().scheme(uri.scheme).encodedAuthority(uri.encodedAuthority)
        folder.forEach(builder::appendPath)
        builder.appendPath(name)
        return builder.build()
    }

    private fun read(context: Context, uri: Uri, headers: Map<String, String>): ByteArray? =
        RandomAccessSources.open(context, uri, headers).use { src ->
            val size = src.size
            if (size <= 0 || size > MAX_SOURCE_BYTES) return null
            val buffer = ByteArray(size.toInt())
            var done = 0
            while (done < buffer.size) {
                val n = src.read(done.toLong(), buffer, done, buffer.size - done)
                if (n <= 0) break
                done += n
            }
            if (done < buffer.size) null else buffer
        }
}
