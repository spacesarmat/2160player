package tv.p2160.core.engine

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import io.github.anilbeesetti.nextlib.mediainfo.MediaInfo
import io.github.anilbeesetti.nextlib.mediainfo.MediaInfoBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tv.p2160.core.api.Chapter

/**
 * Читает файл через FFmpeg (libavformat): главы и кадры для превью при перемотке.
 * ExoPlayer главы не отдаёт, поэтому открываем файл второй раз, только для метаданных.
 */
internal class MediaAnalyzer(private val context: Context) {
    private val mutex = Mutex()
    private var info: MediaInfo? = null
    private var openedUri: Uri? = null

    suspend fun chapters(uri: Uri): List<Chapter> = mutex.withLock {
        withContext(Dispatchers.IO) {
            open(uri)?.chapters.orEmpty()
                .sortedBy { it.start }
                .map { Chapter(it.title?.trim()?.takeIf(String::isNotEmpty), it.start, it.end) }
                .filter { it.endMs > it.startMs }
        }
    }

    /** Кадр около [positionMs] или null, если формат/источник этого не позволяет. */
    suspend fun frameAt(uri: Uri, positionMs: Long): Bitmap? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val media = open(uri)?.takeIf { it.supportsFrameLoading } ?: return@withContext null
            runCatching { media.getFrameAt(positionMs) }.getOrNull()
        }
    }

    fun release() {
        runCatching { info?.release() }
        info = null
        openedUri = null
    }

    private fun open(uri: Uri): MediaInfo? {
        if (openedUri == uri) return info
        release()
        openedUri = uri
        info = runCatching {
            when (uri.scheme?.lowercase()) {
                "content", "android.resource" -> MediaInfoBuilder().from(context, uri).build()
                "file" -> MediaInfoBuilder().from(uri.path ?: return null).build()
                else -> MediaInfoBuilder().from(uri.toString()).build()
            }
        }.getOrNull()
        return info
    }
}
