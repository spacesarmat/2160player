@file:OptIn(UnstableApi::class)

package tv.p2160.core.engine

import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException
import tv.p2160.core.api.ExternalAudio

/** Скорость из истории, включая 1x. Пустое или битое значение заменяется общей настройкой. */
internal fun playbackSpeedFor(stored: Float?, defaultSpeed: Float, live: Boolean): Float {
    if (live) return 1f
    val saved = stored?.takeIf { it in 0.25f..4f }
    return saved ?: defaultSpeed.coerceIn(0.25f, 4f)
}

/**
 * Какая внешняя дорожка сломала воспроизведение. null, если это не она.
 * Сначала id `exta:N` (в том числе после склейки `1:exta:0`), затем URI из
 * [UnrecognizedInputFormatException]. Декодер часто сообщает id дорожки контейнера
 * (`1` у Matroska), без `exta:`: тогда берётся выбранная внешняя, а если файл один, он и есть.
 * Ошибка видео сюда не входит.
 */
internal fun failingExternalIndex(
    error: Throwable,
    tracks: List<ExternalAudio>,
    selectedIndex: Int? = null,
): Int? {
    if (tracks.isEmpty()) return null
    val exo = generateSequence(error) { it.cause }.filterIsInstance<ExoPlaybackException>().firstOrNull()
        ?: (error as? ExoPlaybackException)
    val uris = LinkedHashSet<String>()
    generateSequence(error) { it.cause }.forEach { cause ->
        if (cause is UnrecognizedInputFormatException) uris += cause.uri.toString()
    }
    val hits = tracks.mapIndexedNotNull { index, audio ->
        val uri = audio.uri.toString()
        if (uris.any { it == uri || it.contains(uri) }) index else null
    }
    return externalAudioFailureIndex(
        tracks = tracks,
        rendererFormatId = exo?.rendererFormat?.id,
        selectedIndex = selectedIndex,
        audioFailure = isExternalAudioFailure(error),
        matchedUriIndex = hits.singleOrNull(),
    )
}

/**
 * Какой внешний файл снять. [rendererFormatId] `1` - id из контейнера, не наш `exta:`.
 * [audioFailure] ложь для ошибки видео: внешние файлы тогда не трогаем.
 */
internal fun externalAudioFailureIndex(
    tracks: List<ExternalAudio>,
    rendererFormatId: String?,
    selectedIndex: Int?,
    audioFailure: Boolean,
    matchedUriIndex: Int? = null,
): Int? {
    if (tracks.isEmpty()) return null
    externalAudioIndex(rendererFormatId)?.let { if (it in tracks.indices) return it }
    if (matchedUriIndex != null && matchedUriIndex in tracks.indices) return matchedUriIndex
    if (!audioFailure) return null
    if (selectedIndex != null && selectedIndex in tracks.indices) return selectedIndex
    if (tracks.size == 1) return 0
    return null
}

/** Ошибка звука или источника, из-за которой нельзя оставлять внешнюю дорожку выбранной. */
private fun isExternalAudioFailure(error: Throwable): Boolean {
    val exo = generateSequence(error) { it.cause }.filterIsInstance<ExoPlaybackException>().firstOrNull()
        ?: (error as? ExoPlaybackException)
    val mime = exo?.rendererFormat?.sampleMimeType
    if (exo?.type == ExoPlaybackException.TYPE_RENDERER && mime != null && MimeTypes.isVideo(mime)) return false
    if (isExternalSourceError(error)) return true
    return exo?.type == ExoPlaybackException.TYPE_RENDERER && (mime == null || !MimeTypes.isVideo(mime))
}

private fun isExternalSourceError(error: Throwable): Boolean {
    val exo = generateSequence(error) { it.cause }.filterIsInstance<ExoPlaybackException>().firstOrNull()
        ?: (error as? ExoPlaybackException)
    if (exo?.type == ExoPlaybackException.TYPE_SOURCE) return true
    val code = (error as? PlaybackException)?.errorCode ?: return false
    return code == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
        code == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
        code == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
        code == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
        code == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
}
