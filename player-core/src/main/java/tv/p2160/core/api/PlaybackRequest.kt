package tv.p2160.core.api

import android.net.Uri

/** Внешний файл субтитров, подключаемый к элементу воспроизведения. */
data class ExternalSubtitle(
    val uri: Uri,
    val name: String? = null,
    val language: String? = null,
    /** Включить эту дорожку сразу после старта. */
    val select: Boolean = false,
    /**
     * Стабильный идентификатор для выбора и результата (`id:lostfilm`).
     * Пустой - берётся [name], затем URI.
     */
    val id: String? = null,
    /** MIME, если его нельзя вывести из имени (`application/x-subrip` и т.п.). */
    val mimeType: String? = null,
    /** Сдвиг дорожки, мс. Больше 0 - субтитры позже картинки. Плюсуется к задержке пользователя. */
    val delayMs: Long = 0,
    /** Кодировка текста. Пусто - автоопределение, в том числе windows-1251. */
    val charset: String? = null,
)

/**
 * Внешний аудиофайл рядом с видео (отдельная озвучка).
 * Схемы: `http`, `https`, `file`, `content`, `smb`.
 */
data class ExternalAudio(
    val uri: Uri,
    val name: String? = null,
    val language: String? = null,
    /** Включить сразу, если для элемента нет [MediaEntry.audioSelect]. */
    val select: Boolean = false,
    /** Стабильный идентификатор студии/дорожки. Пустой - [name], затем URI. */
    val id: String? = null,
    /** MIME или подсказка контейнера (`audio/eac3`, `audio/x-matroska`). */
    val mimeType: String? = null,
    /** Сдвиг дорожки, мс. Больше 0 - звук позже картинки. Плюсуется к общей задержке звука. */
    val delayMs: Long = 0,
)

/** Дорожка, выбранная в момент остановки. [id] стабилен между сериями, если его задал вызывающий. */
data class SelectedTrack(
    val id: String,
    val name: String?,
    val language: String?,
    val external: Boolean,
    /** Индекс среди встроенных дорожек этого типа, с нуля. -1, если дорожка внешняя. */
    val embeddedIndex: Int = -1,
    /** Индекс во внешнем списке элемента. -1, если дорожка встроенная. */
    val externalIndex: Int = -1,
)

/** Идентификатор внешней дорожки: явный id, иначе имя, иначе URI. */
fun stableTrackId(id: String?, name: String?, uri: Uri): String =
    id?.trim()?.takeIf { it.isNotEmpty() }
        ?: name?.trim()?.takeIf { it.isNotEmpty() }
        ?: uri.toString()

fun ExternalAudio.stableId(): String = stableTrackId(id, name, uri)

fun ExternalSubtitle.stableId(): String = stableTrackId(id, name, uri)

/** Один элемент плейлиста. */
data class MediaEntry(
    val uri: Uri,
    val title: String? = null,
    val subtitles: List<ExternalSubtitle> = emptyList(),
    /** Явный MIME-тип, если по URI его не определить (например, HLS без .m3u8). */
    val mimeType: String? = null,
    /** Известные отрезки (вступление, титры…), например от медиасервера. */
    val segments: List<SkipSegment> = emptyList(),
    /** Обложка/постер (http(s), smb, file, content) - для «Продолжить просмотр»; иначе ищется сама. */
    val artworkUri: Uri? = null,
    /** Внешние аудиодорожки (отдельные файлы озвучки). */
    val audio: List<ExternalAudio> = emptyList(),
    /**
     * Какую аудиодорожку включить: `id:<id>`, `index:<n>` (внешний список), `embedded:<n>`.
     * Пусто - [ExternalAudio.select], затем история.
     */
    val audioSelect: String? = null,
    /**
     * Какую текстовую дорожку включить. Те же значения, плюс `off`.
     * Пусто - [ExternalSubtitle.select], затем история.
     */
    val textSelect: String? = null,
)

/**
 * Запрос на воспроизведение - основная точка входа API.
 *
 * @param startPositionMs позиция старта для [startIndex]; `null` - продолжить с сохранённого места.
 * @param headers HTTP-заголовки для сетевых источников (User-Agent, Referer, Authorization…).
 * @param returnResult вернуть вызывающему приложению позицию остановки (см. [PlaybackResult]).
 * @param liveTv элементы - телеканалы (IPTV): без продолжения с места и истории, «Эфир» вместо
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
    /** Выбранный звук. null - дорожки не было или плеер её ещё не показал. */
    val audio: SelectedTrack? = null,
    /** Выбранные субтитры. null, если их нет или они выключены ([textOff]). */
    val text: SelectedTrack? = null,
    /** Субтитры явно выключены. Старые результаты без этого поля читаются как false. */
    val textOff: Boolean = false,
)
