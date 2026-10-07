package tv.p2160.torrent

/**
 * Положение файла внутри торрента: смещение от начала «склеенного» потока всех файлов,
 * размер и длина куска. Позиции в методах — относительно начала файла.
 */
data class FileSpan(val offset: Long, val size: Long, val pieceLength: Int) {
    init {
        require(offset >= 0 && size >= 0 && pieceLength > 0)
    }

    val firstPiece: Int get() = (offset / pieceLength).toInt()
    val lastPiece: Int get() = ((offset + maxOf(size, 1) - 1) / pieceLength).toInt()
    val pieceCount: Int get() = lastPiece - firstPiece + 1

    /** Кусок, в который попадает байт [position] файла. */
    fun pieceAt(position: Long): Int = ((offset + position.coerceIn(0, maxOf(size - 1, 0))) / pieceLength).toInt()

    /** Начало куска в координатах файла (у первого куска может быть отрицательным). */
    fun pieceStart(piece: Int): Long = piece.toLong() * pieceLength - offset

    /** Конец куска (исключительно) в координатах файла, не дальше конца файла. */
    fun pieceEnd(piece: Int): Long = minOf(size, (piece + 1).toLong() * pieceLength - offset)

    /** Куски, покрывающие диапазон байт файла [position, position + length). */
    fun pieces(position: Long, length: Long): IntRange {
        if (length <= 0 || position >= size) return IntRange.EMPTY
        return pieceAt(position)..pieceAt(minOf(size, position + length) - 1)
    }
}

/**
 * Математика приоритетов для потокового просмотра: какие куски нужны срочно (с дедлайном),
 * какие — заранее (конец файла: индекс MKV Cues / MP4 moov), сколько данных уже есть впереди.
 * Чистые функции — проверяются JVM-тестами без libtorrent.
 */
object PiecePlanner {
    /** Сколько байт держать «срочными» впереди позиции чтения. */
    const val READAHEAD_BYTES = 32L * 1024 * 1024
    const val MIN_READAHEAD_PIECES = 4
    const val MAX_READAHEAD_PIECES = 96

    /** Начало файла — заголовки контейнера, нужны до старта. */
    const val HEAD_BYTES = 4L * 1024 * 1024

    /** Доля хвоста файла, которую качаем заранее, и её пределы. */
    const val TAIL_FRACTION = 0.025
    const val TAIL_MIN_BYTES = 2L * 1024 * 1024
    const val TAIL_MAX_BYTES = 64L * 1024 * 1024

    /** Дедлайн первого куска окна и шаг для следующих (мс от «сейчас»). */
    const val FIRST_DEADLINE_MS = 100
    const val DEADLINE_STEP_MS = 150
    /** Хвост — после ближайших кусков окна, чтобы не отнимать полосу у текущего чтения. */
    const val TAIL_DEADLINE_MS = 4_000

    fun readaheadPieces(span: FileSpan): Int =
        ((READAHEAD_BYTES + span.pieceLength - 1) / span.pieceLength).toInt()
            .coerceIn(MIN_READAHEAD_PIECES, MAX_READAHEAD_PIECES)

    fun headPieces(span: FileSpan): IntRange = span.pieces(0, minOf(span.size, HEAD_BYTES))

    fun tailBytes(size: Long): Long =
        (size * TAIL_FRACTION).toLong().coerceIn(TAIL_MIN_BYTES, TAIL_MAX_BYTES).coerceAtMost(size)

    fun tailPieces(span: FileSpan): IntRange {
        if (span.size == 0L) return IntRange.EMPTY
        val bytes = tailBytes(span.size)
        return span.pieces(span.size - bytes, bytes)
    }

    /** Окно чтения от [position]: столько кусков, сколько даёт [readaheadPieces], не дальше конца файла. */
    fun window(span: FileSpan, position: Long): IntRange {
        if (span.size == 0L) return IntRange.EMPTY
        val first = span.pieceAt(position)
        return first..minOf(span.lastPiece, first + readaheadPieces(span) - 1)
    }

    /**
     * Дедлайны кусков (кусок → мс). Для каждой позиции чтения — окно впереди с растущими
     * дедлайнами; при [wantHead] (плеер ещё не открыл файл) — начало файла; при [wantTail] —
     * хвост с отложенным дедлайном. Уже скачанные куски пропускаются. Если кусок попал в
     * несколько окон, берётся самый ранний дедлайн.
     */
    fun deadlines(
        span: FileSpan,
        positions: Collection<Long>,
        wantHead: Boolean,
        wantTail: Boolean,
        have: (Int) -> Boolean,
    ): Map<Int, Int> {
        val result = HashMap<Int, Int>()
        fun put(piece: Int, ms: Int) {
            if (have(piece)) return
            val old = result[piece]
            if (old == null || ms < old) result[piece] = ms
        }
        positions.forEach { pos ->
            window(span, pos).forEachIndexed { i, piece -> put(piece, FIRST_DEADLINE_MS + i * DEADLINE_STEP_MS) }
        }
        if (wantHead) headPieces(span).forEachIndexed { i, piece -> put(piece, FIRST_DEADLINE_MS + i * DEADLINE_STEP_MS) }
        if (wantTail) tailPieces(span).forEachIndexed { i, piece -> put(piece, TAIL_DEADLINE_MS + i * DEADLINE_STEP_MS) }
        return result
    }

    /**
     * Сколько байт подряд доступно начиная с [position] (для оценки «буфер впереди»).
     * [limitPieces] ограничивает проход по битовому полю.
     */
    fun contiguousBytes(span: FileSpan, position: Long, limitPieces: Int = 4096, have: (Int) -> Boolean): Long {
        if (position >= span.size) return 0
        var piece = span.pieceAt(position)
        var end = position
        var steps = 0
        while (piece <= span.lastPiece && steps < limitPieces && have(piece)) {
            end = span.pieceEnd(piece)
            piece++
            steps++
        }
        return (end - position).coerceAtLeast(0)
    }

    /** Секунды видео в [bytes] при средней скорости потока size/duration. */
    fun bytesToSeconds(bytes: Long, fileSize: Long, durationMs: Long): Long =
        if (fileSize <= 0 || durationMs <= 0) 0 else (bytes.toDouble() * durationMs / fileSize / 1000).toLong()
}
