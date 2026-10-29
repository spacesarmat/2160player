@file:OptIn(UnstableApi::class)

package tv.p2160.core.engine

import androidx.annotation.OptIn
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
 * Какая внешняя дорожка сломала источник. null, если это не она или из нескольких нельзя выбрать одну.
 * Совпадение по id формата `exta:N` или по URI в [UnrecognizedInputFormatException].
 */
internal fun failingExternalIndex(error: Throwable, tracks: List<ExternalAudio>): Int? {
    if (tracks.isEmpty()) return null
    val exo = generateSequence(error) { it.cause }.filterIsInstance<ExoPlaybackException>().firstOrNull()
        ?: (error as? ExoPlaybackException)
    externalAudioIndex(exo?.rendererFormat?.id)?.let { if (it in tracks.indices) return it }
    val uris = LinkedHashSet<String>()
    generateSequence(error) { it.cause }.forEach { cause ->
        if (cause is UnrecognizedInputFormatException) uris += cause.uri.toString()
    }
    val hits = tracks.mapIndexedNotNull { index, audio ->
        val uri = audio.uri.toString()
        if (uris.any { it == uri || it.contains(uri) }) index else null
    }
    if (hits.size == 1) return hits.single()
    if (tracks.size == 1 && isExternalSourceError(error)) return 0
    return null
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
