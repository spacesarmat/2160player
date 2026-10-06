package tv.p2160.core.engine

import android.content.Context
import org.json.JSONObject
import tv.p2160.core.api.Chapter
import tv.p2160.core.api.SegmentType
import tv.p2160.core.api.SkipSegment

/** Определение отрезков по названиям глав и ключ сериала для ручных отметок. */
object SegmentDetector {

    private val patterns = listOf(
        SegmentType.INTRO to Regex(
            "(?u)^(intro|opening|op\\s?\\d*|opening credits|main title|вступление|заставка|начальные титры|опенинг)\\b.*",
            RegexOption.IGNORE_CASE,
        ),
        SegmentType.RECAP to Regex("(?u)^(recap|previously|ранее в сериале|в предыдущих сериях|пересказ)\\b.*", RegexOption.IGNORE_CASE),
        SegmentType.CREDITS to Regex(
            "(?u)^(credits|end credits|ending|ed\\s?\\d*|outro|титры|финальные титры|концовка|эндинг)\\b.*",
            RegexOption.IGNORE_CASE,
        ),
        SegmentType.PREVIEW to Regex("(?u)^(preview|next episode|next time|анонс|превью|в следующей серии)\\b.*", RegexOption.IGNORE_CASE),
    )

    fun typeOfChapter(title: String?): SegmentType? {
        val t = title?.trim() ?: return null
        return patterns.firstOrNull { (_, re) -> re.matches(t) }?.first
    }

    fun fromChapters(chapters: List<Chapter>): List<SkipSegment> = chapters.mapNotNull { ch ->
        typeOfChapter(ch.title)?.let { SkipSegment(it, ch.startMs, ch.endMs) }
    }

    private val seasonEpisode = listOf(
        Regex("(?iu)^(.*?)[\\s._\\-\\[(]*s\\d{1,2}[\\s._-]*e\\d{1,3}"),           // Show.S01E02
        Regex("(?iu)^(.*?)[\\s._\\-\\[(]*\\d{1,2}x\\d{2,3}\\b"),                    // Show 1x02
        Regex("(?iu)^(.*?)[\\s._-]*(?:episode|ep|серия|эпизод)[\\s._-]*\\d{1,4}"), // Show Episode 2 / Серия 2
        Regex("^(.*?)[\\s_]+-[\\s_]+\\d{1,4}\\b"),                                // [Group] Show - 05
    )

    /**
     * Ключ сериала по имени файла: «Show.S01E02.1080p.mkv» → «show».
     * null — имя не похоже на серию, отметки тогда хранятся для одного файла.
     */
    fun seriesKey(fileName: String?): String? {
        val name = fileName?.substringBeforeLast('.') ?: return null
        for (re in seasonEpisode) {
            val prefix = re.find(name)?.groupValues?.get(1) ?: continue
            val key = prefix
                .replace(Regex("\\[[^]]*]"), " ")      // теги релиз-групп
                .replace(Regex("[._\\-]+"), " ")
                .trim()
                .lowercase()
            if (key.length >= 2) return key
        }
        return null
    }
}

/** Отметки, поставленные пользователем вручную. Титры храним «от конца» — длина серий разная. */
data class ManualMarks(
    val introStartMs: Long? = null,
    val introEndMs: Long? = null,
    val creditsFromEndMs: Long? = null,
) {
    val isEmpty: Boolean get() = introEndMs == null && creditsFromEndMs == null

    fun toSegments(durationMs: Long): List<SkipSegment> = buildList {
        if (introEndMs != null) add(SkipSegment(SegmentType.INTRO, introStartMs ?: 0L, introEndMs))
        if (creditsFromEndMs != null && durationMs > creditsFromEndMs) {
            add(SkipSegment(SegmentType.CREDITS, durationMs - creditsFromEndMs, null))
        }
    }
}

internal class SegmentStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("p2160_segments", Context.MODE_PRIVATE)

    fun get(key: String): ManualMarks = prefs.getString(key, null)?.let { raw ->
        runCatching {
            val j = JSONObject(raw)
            ManualMarks(
                introStartMs = j.optLong("is", -1).takeIf { it >= 0 },
                introEndMs = j.optLong("ie", -1).takeIf { it >= 0 },
                creditsFromEndMs = j.optLong("ce", -1).takeIf { it >= 0 },
            )
        }.getOrNull()
    } ?: ManualMarks()

    fun put(key: String, marks: ManualMarks) {
        if (marks.isEmpty && marks.introStartMs == null) {
            prefs.edit().remove(key).apply()
            return
        }
        val j = JSONObject()
        marks.introStartMs?.let { j.put("is", it) }
        marks.introEndMs?.let { j.put("ie", it) }
        marks.creditsFromEndMs?.let { j.put("ce", it) }
        prefs.edit().putString(key, j.toString()).apply()
    }

    companion object {
        @Volatile private var instance: SegmentStore? = null
        fun get(context: Context): SegmentStore =
            instance ?: synchronized(this) { instance ?: SegmentStore(context).also { instance = it } }
    }
}

/** Ввод времени: «1:23:45», «12:30», «90» (сек), «1230» → 12:30 (как на пульте: справа налево по две цифры). */
object TimeInput {
    fun parse(raw: String): Long? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val parts: List<Long> = if (':' in text || '.' in text) {
            text.split(':', '.').map { it.trim().toLongOrNull() ?: return null }
        } else {
            if (!text.all(Char::isDigit) || text.length > 6) return null
            text.reversed().chunked(2).map { it.reversed().toLong() }.reversed()
        }
        if (parts.isEmpty() || parts.size > 3) return null
        var seconds = 0L
        parts.forEach { seconds = seconds * 60 + it }
        return seconds * 1000
    }
}
