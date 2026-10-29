package tv.p2160.core.api

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Parcelable

/**
 * Разбор и сборка Intent'ов.
 *
 * Поддерживается собственный формат (`tv.p2160.extra.*`) и де-факто стандарт MX Player,
 * которым пользуются Kodi, Stremio, Jellyfin, Lampa и многие другие приложения.
 * Дополнительно читаются ключи VLC (`from_start`, `subtitles_location`).
 */
object IntentApi {
    const val ACTION_PLAY = "tv.p2160.action.PLAY"

    const val EXTRA_TITLE = "title"
    const val EXTRA_POSITION = "position"
    const val EXTRA_FROM_START = "from_start"
    const val EXTRA_HEADERS = "headers"
    const val EXTRA_RETURN_RESULT = "return_result"
    const val EXTRA_SUBS = "subs"
    const val EXTRA_SUBS_NAME = "subs.name"
    const val EXTRA_SUBS_ENABLE = "subs.enable"
    const val EXTRA_VLC_SUBTITLE = "subtitles_location"
    const val EXTRA_VIDEO_LIST = "video_list"
    const val EXTRA_VIDEO_LIST_NAME = "video_list.name"
    const val EXTRA_TITLES = "tv.p2160.extra.TITLES"
    /** Плейлист строками (String[] URI) - удобно из adb, веб-оболочек и скриптов. */
    const val EXTRA_PLAYLIST = "tv.p2160.extra.PLAYLIST"
    const val EXTRA_MIME_TYPES = "tv.p2160.extra.MIME_TYPES"
    /** Отрезки для текущего файла: `intro:0-90000;credits:1320000-` (мс). */
    const val EXTRA_SEGMENTS = "tv.p2160.extra.SEGMENTS"
    const val EXTRA_INTRO_START = "tv.p2160.extra.INTRO_START"
    const val EXTRA_INTRO_END = "tv.p2160.extra.INTRO_END"
    const val EXTRA_CREDITS_START = "tv.p2160.extra.CREDITS_START"
    /** Обложка текущего файла (String URI) - для «Продолжить просмотр». */
    const val EXTRA_ARTWORK = "tv.p2160.extra.ARTWORK"
    /** Обложки элементов плейлиста (String[] URI, по порядку; пустая строка - нет). */
    const val EXTRA_ARTWORKS = "tv.p2160.extra.ARTWORKS"
    /** Boolean: плейлист - телеканалы (см. [PlaybackRequest.liveTv]). */
    const val EXTRA_LIVE = "tv.p2160.extra.LIVE"

    /**
     * Внешнее аудио по пунктам плейлиста. `String[]`: элемент `i` - JSON-массив дорожек пункта `i`.
     * Одна строка `String` относится только к стартовому пункту.
     * Поля объекта: `uri`, `language`, `name`, `mime`, `id`, `delayMs`, `select`.
     */
    const val EXTRA_AUDIO_TRACKS = "tv.p2160.extra.AUDIO_TRACKS"
    /** Внешние субтитры в том же формате, плюс поле `charset`. */
    const val EXTRA_TEXT_TRACKS = "tv.p2160.extra.TEXT_TRACKS"
    /**
     * Что включить на стартовом пункте: `id:<id>`, `index:<n>` (внешний список), `embedded:<n>`.
     * Для субтитров ещё `off`.
     */
    const val EXTRA_AUDIO_SELECT = "tv.p2160.extra.AUDIO_SELECT"
    const val EXTRA_TEXT_SELECT = "tv.p2160.extra.TEXT_SELECT"

    /** Выбранный звук в результате. */
    const val EXTRA_RESULT_AUDIO_ID = "tv.p2160.extra.AUDIO_ID"
    const val EXTRA_RESULT_AUDIO_NAME = "tv.p2160.extra.AUDIO_NAME"
    const val EXTRA_RESULT_AUDIO_LANGUAGE = "tv.p2160.extra.AUDIO_LANGUAGE"
    const val EXTRA_RESULT_AUDIO_EXTERNAL = "tv.p2160.extra.AUDIO_EXTERNAL"
    const val EXTRA_RESULT_AUDIO_EMBEDDED_INDEX = "tv.p2160.extra.AUDIO_EMBEDDED_INDEX"
    const val EXTRA_RESULT_AUDIO_EXTERNAL_INDEX = "tv.p2160.extra.AUDIO_EXTERNAL_INDEX"
    /** Выбранные субтитры в результате. [EXTRA_RESULT_TEXT_OFF] = true - субтитры выключены. */
    const val EXTRA_RESULT_TEXT_ID = "tv.p2160.extra.TEXT_ID"
    const val EXTRA_RESULT_TEXT_NAME = "tv.p2160.extra.TEXT_NAME"
    const val EXTRA_RESULT_TEXT_LANGUAGE = "tv.p2160.extra.TEXT_LANGUAGE"
    const val EXTRA_RESULT_TEXT_EXTERNAL = "tv.p2160.extra.TEXT_EXTERNAL"
    const val EXTRA_RESULT_TEXT_EMBEDDED_INDEX = "tv.p2160.extra.TEXT_EMBEDDED_INDEX"
    const val EXTRA_RESULT_TEXT_EXTERNAL_INDEX = "tv.p2160.extra.TEXT_EXTERNAL_INDEX"
    const val EXTRA_RESULT_TEXT_OFF = "tv.p2160.extra.TEXT_OFF"

    /** Результат в формате MX Player. */
    const val RESULT_ACTION = "com.mxtech.intent.result.VIEW"
    const val RESULT_POSITION = "position"
    const val RESULT_DURATION = "duration"
    const val RESULT_END_BY = "end_by"
    const val END_BY_USER = "user"
    const val END_BY_COMPLETION = "playback_completion"
    /** Досмотрено до конца (или почти: порог ResumeStore.isFinished). Не заменяет `end_by`. */
    const val EXTRA_WATCHED = "tv.p2160.extra.WATCHED"
    /** Позиция и длительность в миллисекундах, Long. Старые ключи `position` и `duration` остаются Int. */
    const val EXTRA_RESULT_POSITION = "tv.p2160.extra.POSITION"
    const val EXTRA_RESULT_DURATION = "tv.p2160.extra.DURATION"

    // Дублируем в формате VLC, чтобы его клиенты тоже получили позицию.
    private const val VLC_RESULT_POSITION = "extra_position"
    private const val VLC_RESULT_DURATION = "extra_duration"

    fun parse(intent: Intent): PlaybackRequest? {
        val data = intent.data ?: return null

        val playlist = intent.parcelableArray<Uri>(EXTRA_VIDEO_LIST)?.toList()
            ?: intent.getStringArrayExtra(EXTRA_PLAYLIST)?.map(Uri::parse).orEmpty()
        val playlistNames = intent.getStringArrayExtra(EXTRA_VIDEO_LIST_NAME)
            ?: intent.getStringArrayExtra(EXTRA_TITLES)
        val mimeTypes = intent.getStringArrayExtra(EXTRA_MIME_TYPES)
        val artworks = intent.getStringArrayExtra(EXTRA_ARTWORKS)

        val uris = playlist.ifEmpty { listOf(data) }
        val startIndex = uris.indexOf(data).coerceAtLeast(0)

        val legacySubtitles = parseSubtitles(intent)
        val textPayload = readTrackPayload(intent, EXTRA_TEXT_TRACKS)
        val audioPayload = readTrackPayload(intent, EXTRA_AUDIO_TRACKS)
        val audioSelect = intent.getStringExtra(EXTRA_AUDIO_SELECT)?.takeIf { it.isNotBlank() }
        val textSelect = intent.getStringExtra(EXTRA_TEXT_SELECT)?.takeIf { it.isNotBlank() }
        val items = uris.mapIndexed { i, uri ->
            val fromJson = textAt(textPayload, i, startIndex, uris.size)
            val subtitles = if (i == startIndex) mergeSubtitles(fromJson, legacySubtitles) else fromJson
            MediaEntry(
                uri = uri,
                title = playlistNames?.getOrNull(i)
                    ?: if (i == startIndex) intent.getStringExtra(EXTRA_TITLE) else null,
                subtitles = subtitles,
                mimeType = mimeTypes?.getOrNull(i) ?: if (i == startIndex) intent.type else null,
                segments = if (i == startIndex) parseSegments(intent) else emptyList(),
                artworkUri = (artworks?.getOrNull(i)?.takeIf { it.isNotBlank() }
                    ?: if (i == startIndex) intent.getStringExtra(EXTRA_ARTWORK) else null)?.let(Uri::parse),
                audio = audioAt(audioPayload, i, startIndex, uris.size),
                audioSelect = if (i == startIndex) audioSelect else null,
                textSelect = if (i == startIndex) textSelect else null,
            )
        }

        val position = when {
            intent.getBooleanExtra(EXTRA_FROM_START, false) -> 0L
            intent.hasExtra(EXTRA_POSITION) -> intent.longOrInt(EXTRA_POSITION)
            else -> null
        }

        return PlaybackRequest(
            items = items,
            startIndex = startIndex,
            startPositionMs = position,
            headers = parseHeaders(intent),
            returnResult = intent.getBooleanExtra(EXTRA_RETURN_RESULT, false),
            liveTv = intent.getBooleanExtra(EXTRA_LIVE, false),
        )
    }

    fun toIntent(request: PlaybackRequest, intent: Intent): Intent = intent.apply {
        action = ACTION_PLAY
        val current = request.items[request.startIndex]
        setDataAndType(current.uri, current.mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        current.title?.let { putExtra(EXTRA_TITLE, it) }
        request.startPositionMs?.let {
            if (it == 0L) putExtra(EXTRA_FROM_START, true) else putExtra(EXTRA_POSITION, it.toInt())
        }
        if (request.items.size > 1) {
            putExtra(EXTRA_VIDEO_LIST, request.items.map { it.uri }.toTypedArray())
            putExtra(EXTRA_TITLES, request.items.map { it.title.orEmpty() }.toTypedArray())
            putExtra(EXTRA_MIME_TYPES, request.items.map { it.mimeType.orEmpty() }.toTypedArray())
        }
        if (current.subtitles.isNotEmpty()) {
            putExtra(EXTRA_SUBS, current.subtitles.map { it.uri }.toTypedArray())
            putExtra(EXTRA_SUBS_NAME, current.subtitles.map { it.name.orEmpty() }.toTypedArray())
            putExtra(EXTRA_SUBS_ENABLE, current.subtitles.filter { it.select }.map { it.uri }.toTypedArray())
        }
        if (request.items.any { it.audio.isNotEmpty() }) {
            putExtra(EXTRA_AUDIO_TRACKS, request.items.map { ExternalTrackJson.audioJson(it.audio) }.toTypedArray())
        }
        if (request.items.any { it.subtitles.isNotEmpty() }) {
            putExtra(EXTRA_TEXT_TRACKS, request.items.map { ExternalTrackJson.subtitleJson(it.subtitles) }.toTypedArray())
        }
        current.audioSelect?.let { putExtra(EXTRA_AUDIO_SELECT, it) }
        current.textSelect?.let { putExtra(EXTRA_TEXT_SELECT, it) }
        if (current.segments.isNotEmpty()) putExtra(EXTRA_SEGMENTS, SkipSegment.formatList(current.segments))
        current.artworkUri?.let { putExtra(EXTRA_ARTWORK, it.toString()) }
        if (request.items.size > 1 && request.items.any { it.artworkUri != null }) {
            putExtra(EXTRA_ARTWORKS, request.items.map { it.artworkUri?.toString().orEmpty() }.toTypedArray())
        }
        if (request.headers.isNotEmpty()) {
            putExtra(EXTRA_HEADERS, request.headers.flatMap { listOf(it.key, it.value) }.toTypedArray())
        }
        putExtra(EXTRA_RETURN_RESULT, request.returnResult)
        if (request.liveTv) putExtra(EXTRA_LIVE, true)
    }

    fun buildResult(result: PlaybackResult): Intent = Intent(RESULT_ACTION).apply {
        data = result.uri
        putExtra(RESULT_POSITION, result.positionMs.toInt())
        putExtra(RESULT_DURATION, result.durationMs.toInt())
        putExtra(RESULT_END_BY, if (result.completed) END_BY_COMPLETION else END_BY_USER)
        putExtra(EXTRA_WATCHED, result.completed)
        putExtra(EXTRA_RESULT_POSITION, result.positionMs)
        putExtra(EXTRA_RESULT_DURATION, result.durationMs)
        putExtra(VLC_RESULT_POSITION, result.positionMs)
        putExtra(VLC_RESULT_DURATION, result.durationMs)
        putSelected(this, result.audio, audio = true)
        putExtra(EXTRA_RESULT_TEXT_OFF, result.textOff)
        if (!result.textOff) putSelected(this, result.text, audio = false)
    }

    fun parseResult(resultCode: Int, intent: Intent?): PlaybackResult? {
        if (resultCode != Activity.RESULT_OK || intent == null) return null
        val position = if (intent.hasExtra(EXTRA_RESULT_POSITION)) intent.longOrInt(EXTRA_RESULT_POSITION) else intent.longOrInt(RESULT_POSITION)
        val duration = if (intent.hasExtra(EXTRA_RESULT_DURATION)) intent.longOrInt(EXTRA_RESULT_DURATION) else intent.longOrInt(RESULT_DURATION)
        return PlaybackResult(
            uri = intent.data,
            positionMs = position,
            durationMs = duration,
            completed = intent.getBooleanExtra(EXTRA_WATCHED, false) ||
                intent.getStringExtra(RESULT_END_BY) == END_BY_COMPLETION,
            audio = readSelected(intent, audio = true),
            text = readSelected(intent, audio = false),
            textOff = intent.getBooleanExtra(EXTRA_RESULT_TEXT_OFF, false),
        )
    }

    private data class TrackPayload(val perIndex: Boolean, val raw: List<String>)

    private fun readTrackPayload(intent: Intent, key: String): TrackPayload? {
        intent.getStringArrayExtra(key)?.let { return TrackPayload(perIndex = true, raw = it.toList()) }
        intent.getStringExtra(key)?.let { return TrackPayload(perIndex = false, raw = listOf(it)) }
        return null
    }

    private fun audioAt(payload: TrackPayload?, index: Int, start: Int, count: Int): List<ExternalAudio> {
        val raw = rawAt(payload, index, start, count) ?: return emptyList()
        return ExternalTrackJson.parseAudio(raw)
    }

    private fun textAt(payload: TrackPayload?, index: Int, start: Int, count: Int): List<ExternalSubtitle> {
        val raw = rawAt(payload, index, start, count) ?: return emptyList()
        return ExternalTrackJson.parseSubtitles(raw)
    }

    private fun rawAt(payload: TrackPayload?, index: Int, start: Int, count: Int): String? {
        if (payload == null || count <= 0) return null
        return if (payload.perIndex) payload.raw.getOrNull(index) else payload.raw.firstOrNull()?.takeIf { index == start }
    }

    /** JSON-список плюс старые `subs` / `subtitles_location`. Одинаковый URI не дублируется. */
    private fun mergeSubtitles(primary: List<ExternalSubtitle>, legacy: List<ExternalSubtitle>): List<ExternalSubtitle> {
        if (legacy.isEmpty()) return primary
        val out = primary.toMutableList()
        for (sub in legacy) if (out.none { it.uri == sub.uri }) out += sub
        return out
    }

    private fun putSelected(intent: Intent, track: SelectedTrack?, audio: Boolean) {
        if (track == null) return
        intent.putExtra(if (audio) EXTRA_RESULT_AUDIO_ID else EXTRA_RESULT_TEXT_ID, track.id)
        track.name?.let { intent.putExtra(if (audio) EXTRA_RESULT_AUDIO_NAME else EXTRA_RESULT_TEXT_NAME, it) }
        track.language?.let { intent.putExtra(if (audio) EXTRA_RESULT_AUDIO_LANGUAGE else EXTRA_RESULT_TEXT_LANGUAGE, it) }
        intent.putExtra(if (audio) EXTRA_RESULT_AUDIO_EXTERNAL else EXTRA_RESULT_TEXT_EXTERNAL, track.external)
        intent.putExtra(if (audio) EXTRA_RESULT_AUDIO_EMBEDDED_INDEX else EXTRA_RESULT_TEXT_EMBEDDED_INDEX, track.embeddedIndex)
        intent.putExtra(if (audio) EXTRA_RESULT_AUDIO_EXTERNAL_INDEX else EXTRA_RESULT_TEXT_EXTERNAL_INDEX, track.externalIndex)
    }

    private fun readSelected(intent: Intent, audio: Boolean): SelectedTrack? {
        val idKey = if (audio) EXTRA_RESULT_AUDIO_ID else EXTRA_RESULT_TEXT_ID
        if (!intent.hasExtra(idKey)) return null
        val id = intent.getStringExtra(idKey)?.takeIf { it.isNotBlank() } ?: return null
        return SelectedTrack(
            id = id,
            name = intent.getStringExtra(if (audio) EXTRA_RESULT_AUDIO_NAME else EXTRA_RESULT_TEXT_NAME)?.takeIf { it.isNotBlank() },
            language = intent.getStringExtra(if (audio) EXTRA_RESULT_AUDIO_LANGUAGE else EXTRA_RESULT_TEXT_LANGUAGE)?.takeIf { it.isNotBlank() },
            external = intent.getBooleanExtra(if (audio) EXTRA_RESULT_AUDIO_EXTERNAL else EXTRA_RESULT_TEXT_EXTERNAL, false),
            embeddedIndex = intent.getIntExtra(if (audio) EXTRA_RESULT_AUDIO_EMBEDDED_INDEX else EXTRA_RESULT_TEXT_EMBEDDED_INDEX, -1),
            externalIndex = intent.getIntExtra(if (audio) EXTRA_RESULT_AUDIO_EXTERNAL_INDEX else EXTRA_RESULT_TEXT_EXTERNAL_INDEX, -1),
        )
    }

    private fun parseSubtitles(intent: Intent): List<ExternalSubtitle> {
        val subs = intent.parcelableArray<Uri>(EXTRA_SUBS)?.toList().orEmpty()
        val names = intent.getStringArrayExtra(EXTRA_SUBS_NAME)
        val enabled = intent.parcelableArray<Uri>(EXTRA_SUBS_ENABLE)?.toSet().orEmpty()
        val result = subs.mapIndexed { i, uri ->
            ExternalSubtitle(uri, names?.getOrNull(i)?.ifBlank { null }, select = uri in enabled)
        }.toMutableList()
        intent.getStringExtra(EXTRA_VLC_SUBTITLE)?.let { location ->
            val uri = if (location.contains("://")) Uri.parse(location) else Uri.parse("file://$location")
            if (result.none { it.uri == uri }) result += ExternalSubtitle(uri, select = true)
        }
        return result
    }

    private fun parseSegments(intent: Intent): List<SkipSegment> {
        val result = SkipSegment.parseList(intent.getStringExtra(EXTRA_SEGMENTS)).toMutableList()
        if (intent.hasExtra(EXTRA_INTRO_END)) {
            val start = if (intent.hasExtra(EXTRA_INTRO_START)) intent.longOrInt(EXTRA_INTRO_START) else 0L
            val end = intent.longOrInt(EXTRA_INTRO_END)
            if (end > start) result += SkipSegment(SegmentType.INTRO, start, end)
        }
        if (intent.hasExtra(EXTRA_CREDITS_START)) {
            result += SkipSegment(SegmentType.CREDITS, intent.longOrInt(EXTRA_CREDITS_START), null)
        }
        return result
    }

    private fun parseHeaders(intent: Intent): Map<String, String> {
        val raw = intent.getStringArrayExtra(EXTRA_HEADERS) ?: return emptyMap()
        return raw.toList().chunked(2).filter { it.size == 2 }.associate { it[0] to it[1] }
    }

    private fun Intent.longOrInt(key: String): Long =
        when (val v = extras?.get(key)) {
            is Int -> v.toLong()
            is Long -> v
            is String -> v.toLongOrNull() ?: 0L
            else -> 0L
        }

    @Suppress("DEPRECATION")
    private inline fun <reified T : Parcelable> Intent.parcelableArray(key: String): Array<T>? {
        val raw: Array<out Parcelable>? =
            if (Build.VERSION.SDK_INT >= 33) getParcelableArrayExtra(key, T::class.java)
            else getParcelableArrayExtra(key)
        return raw?.filterIsInstance<T>()?.toTypedArray()
    }
}
