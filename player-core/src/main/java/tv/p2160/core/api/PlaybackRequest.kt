package tv.p2160.core.api

import android.net.Uri

/** Внешний файл субтитров, подключаемый к элементу воспроизведения. */
data class ExternalSubtitle(
    val uri: Uri,
    val name: String? = null,
    val language: String? = null,
    /** Включить эту дорожку сразу после старта. */
    val select: Boolean = false,
)

/** Один элемент плейлиста. */
data class MediaEntry(
    val uri: Uri,
    val title: String? = null,
    val subtitles: List<ExternalSubtitle> = emptyList(),
    /** Явный MIME-тип, если по URI его не определить (например, HLS без .m3u8). */
    val mimeType: String? = null,
    /** Известные отрезки (вступление, титры…), например от медиасервера. */
    val segments: List<SkipSegment> = emptyList(),
)

/**
 * Запрос на воспроизведение — основная точка входа API.
 *
 * @param startPositionMs позиция старта для [startIndex]; `null` — продолжить с сохранённого места.
 * @param headers HTTP-заголовки для сетевых источников (User-Agent, Referer, Authorization…).
 * @param returnResult вернуть вызывающему приложению позицию остановки (см. [PlaybackResult]).
 * @param liveTv элементы — телеканалы (IPTV): без продолжения с места и истории, «Эфир» вместо
 *   полосы перемотки, переключение каналов стрелками вверх/вниз, текущая передача из [LiveGuide].
 */
data class PlaybackRequest(
    val items: List<MediaEntry>,
    val startIndex: Int = 0,
    val startPositionMs: Long? = null,
    val headers: Map<String, String> = emptyMap(),
    val returnResult: Boolean = false,
    val liveTv: Boolean = false,
) {
    init {
        require(items.isNotEmpty()) { "PlaybackRequest requires at least one item" }
    }

    companion object {
        fun single(uri: Uri, title: String? = null): PlaybackRequest =
            PlaybackRequest(listOf(MediaEntry(uri, title)))
    }
}

/** Результат, возвращаемый плеером вызывающему приложению. */
data class PlaybackResult(
    val uri: Uri?,
    val positionMs: Long,
    val durationMs: Long,
    val completed: Boolean,
)
