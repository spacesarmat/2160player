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
    /** Плейлист строками (String[] URI) — удобно из adb, веб-оболочек и скриптов. */
    const val EXTRA_PLAYLIST = "tv.p2160.extra.PLAYLIST"
    const val EXTRA_MIME_TYPES = "tv.p2160.extra.MIME_TYPES"
    /** Отрезки для текущего файла: `intro:0-90000;credits:1320000-` (мс). */
    const val EXTRA_SEGMENTS = "tv.p2160.extra.SEGMENTS"
    const val EXTRA_INTRO_START = "tv.p2160.extra.INTRO_START"
    const val EXTRA_INTRO_END = "tv.p2160.extra.INTRO_END"
    const val EXTRA_CREDITS_START = "tv.p2160.extra.CREDITS_START"
    /** Boolean: плейлист — телеканалы (см. [PlaybackRequest.liveTv]). */
    const val EXTRA_LIVE = "tv.p2160.extra.LIVE"

    /** Результат в формате MX Player. */
    const val RESULT_ACTION = "com.mxtech.intent.result.VIEW"
    const val RESULT_POSITION = "position"
    const val RESULT_DURATION = "duration"
    const val RESULT_END_BY = "end_by"
    const val END_BY_USER = "user"
    const val END_BY_COMPLETION = "playback_completion"

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

        val uris = playlist.ifEmpty { listOf(data) }
        val startIndex = uris.indexOf(data).coerceAtLeast(0)

        val subtitles = parseSubtitles(intent)
        val items = uris.mapIndexed { i, uri ->
            MediaEntry(
                uri = uri,
                title = playlistNames?.getOrNull(i)
                    ?: if (i == startIndex) intent.getStringExtra(EXTRA_TITLE) else null,
                subtitles = if (i == startIndex) subtitles else emptyList(),
                mimeType = mimeTypes?.getOrNull(i) ?: if (i == startIndex) intent.type else null,
                segments = if (i == startIndex) parseSegments(intent) else emptyList(),
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
        if (current.segments.isNotEmpty()) putExtra(EXTRA_SEGMENTS, SkipSegment.formatList(current.segments))
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
        putExtra(VLC_RESULT_POSITION, result.positionMs)
        putExtra(VLC_RESULT_DURATION, result.durationMs)
    }

    fun parseResult(resultCode: Int, intent: Intent?): PlaybackResult? {
        if (resultCode != Activity.RESULT_OK || intent == null) return null
        return PlaybackResult(
            uri = intent.data,
            positionMs = intent.longOrInt(RESULT_POSITION),
            durationMs = intent.longOrInt(RESULT_DURATION),
            completed = intent.getStringExtra(RESULT_END_BY) == END_BY_COMPLETION,
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
