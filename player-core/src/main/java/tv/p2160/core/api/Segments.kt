package tv.p2160.core.api

/** Тип пропускаемого отрезка. */
enum class SegmentType { INTRO, RECAP, CREDITS, PREVIEW }

/**
 * Отрезок, который можно пропустить.
 * @param endMs `null` — до конца файла (типично для титров).
 */
data class SkipSegment(
    val type: SegmentType,
    val startMs: Long,
    val endMs: Long?,
) {
    fun contains(positionMs: Long, durationMs: Long): Boolean {
        val end = endMs ?: durationMs
        return positionMs >= startMs && positionMs < end - 500
    }

    companion object {
        /**
         * Разбор строки вида `intro:0-90000;credits:1320000-` (миллисекунды, конец можно опустить).
         * Используется в Intent-API: extra `tv.p2160.extra.SEGMENTS`.
         */
        fun parseList(raw: String?): List<SkipSegment> =
            raw.orEmpty().split(';', ',').mapNotNull { part ->
                val (name, range) = part.trim().split(':', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
                val type = SegmentType.entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) } ?: return@mapNotNull null
                val bounds = range.split('-', limit = 2)
                val start = bounds.getOrNull(0)?.trim()?.toLongOrNull() ?: return@mapNotNull null
                val end = bounds.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }?.toLongOrNull()
                if (end != null && end <= start) null else SkipSegment(type, start, end)
            }

        fun formatList(segments: List<SkipSegment>): String =
            segments.joinToString(";") { "${it.type.name.lowercase()}:${it.startMs}-${it.endMs ?: ""}" }
    }
}

/** Глава файла. */
data class Chapter(
    val title: String?,
    val startMs: Long,
    val endMs: Long,
)
