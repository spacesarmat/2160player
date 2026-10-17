package tv.p2160.app.handoff

/**
 * Разбор заголовка `Range: bytes=…` для раздачи файла.
 * [partial] = false — заголовка не было, отдаём файл целиком (в том числе пустой: end < start, длина 0).
 * null — диапазон задан, но невыполним (ответ 416).
 */
internal data class ByteRange(val start: Long, val endInclusive: Long, val partial: Boolean) {
    val length: Long get() = if (endInclusive < start) 0 else endInclusive - start + 1
}

internal object ByteRanges {
    private val pattern = Regex("""bytes=(\d*)-(\d*)""")

    fun resolve(header: String?, size: Long): ByteRange? {
        if (size < 0) return null
        if (header.isNullOrBlank()) return ByteRange(0, size - 1, partial = false)
        val match = pattern.find(header.trim()) ?: return null
        val startText = match.groupValues[1]
        val endText = match.groupValues[2]
        if (startText.isEmpty() && endText.isEmpty()) return null
        if (size == 0L) return null
        val start: Long
        val end: Long
        if (startText.isEmpty()) {
            val suffix = endText.toLongOrNull() ?: return null
            if (suffix <= 0) return null
            start = (size - suffix).coerceAtLeast(0)
            end = size - 1
        } else {
            start = startText.toLongOrNull() ?: return null
            if (start >= size) return null
            end = if (endText.isEmpty()) size - 1 else endText.toLongOrNull()?.coerceAtMost(size - 1) ?: return null
            if (end < start) return null
        }
        return ByteRange(start, end, partial = true)
    }
}
