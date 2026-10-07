package tv.p2160.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PiecePlannerTest {
    private val mb = 1024L * 1024

    // Второй файл торрента: начинается в середине куска 2 (куски по 1 МБ).
    private val span = FileSpan(offset = 2 * mb + 512 * 1024, size = 100 * mb, pieceLength = mb.toInt())

    @Test
    fun spanGeometry() {
        assertEquals(2, span.firstPiece)
        assertEquals(102, span.lastPiece) // (2.5 МБ + 100 МБ - 1) / 1 МБ
        assertEquals(101, span.pieceCount)
        assertEquals(2, span.pieceAt(0))
        assertEquals(2, span.pieceAt(512 * 1024 - 1))
        assertEquals(3, span.pieceAt(512 * 1024))
        assertEquals(-512 * 1024L, span.pieceStart(2))
        assertEquals(512 * 1024L, span.pieceEnd(2))
        assertEquals(100 * mb, span.pieceEnd(102))
        assertEquals(2..4, span.pieces(0, 2 * mb))
        assertTrue(span.pieces(100 * mb, 10).isEmpty())
        assertTrue(span.pieces(0, 0).isEmpty())
    }

    @Test
    fun readaheadIsClamped() {
        assertEquals(32, PiecePlanner.readaheadPieces(FileSpan(0, 10_000 * mb, mb.toInt())))
        assertEquals(PiecePlanner.MIN_READAHEAD_PIECES, PiecePlanner.readaheadPieces(FileSpan(0, 10_000 * mb, 16 * mb.toInt())))
        assertEquals(PiecePlanner.MAX_READAHEAD_PIECES, PiecePlanner.readaheadPieces(FileSpan(0, 10_000 * mb, 16 * 1024)))
    }

    @Test
    fun tailIsSmallFractionWithinLimits() {
        assertEquals(PiecePlanner.TAIL_MIN_BYTES, PiecePlanner.tailBytes(10 * mb))
        assertEquals((1000 * mb * 0.025).toLong(), PiecePlanner.tailBytes(1000 * mb))
        assertEquals(PiecePlanner.TAIL_MAX_BYTES, PiecePlanner.tailBytes(50_000 * mb))
        assertEquals(mb, PiecePlanner.tailBytes(mb)) // не больше самого файла
        // 2.5 МБ хвоста файла в 100 МБ → последние 3 куска
        assertEquals(100..102, PiecePlanner.tailPieces(span))
    }

    @Test
    fun windowAfterSeekStartsAtSeekPiece() {
        val w = PiecePlanner.window(span, 50 * mb)
        assertEquals(span.pieceAt(50 * mb), w.first)
        assertEquals(32, w.count())
        // У конца файла окно обрезается
        assertEquals(span.lastPiece, PiecePlanner.window(span, 99 * mb).last)
    }

    @Test
    fun deadlinesIncreaseAlongWindowAndSkipDownloaded() {
        val have = setOf(53)
        val d = PiecePlanner.deadlines(span, listOf(50 * mb), wantHead = false, wantTail = false) { it in have }
        val first = span.pieceAt(50 * mb)
        assertEquals(PiecePlanner.FIRST_DEADLINE_MS, d[first])
        assertFalse(53 in d)
        assertTrue(d.getValue(first + 2) < d.getValue(first + 5))
        assertEquals(31, d.size)
    }

    @Test
    fun headAndTailBeforePlayerOpens() {
        val d = PiecePlanner.deadlines(span, emptyList(), wantHead = true, wantTail = true) { false }
        PiecePlanner.headPieces(span).forEach { assertTrue("head $it", d.getValue(it) < PiecePlanner.TAIL_DEADLINE_MS) }
        PiecePlanner.tailPieces(span).forEach { assertTrue("tail $it", d.getValue(it) >= PiecePlanner.TAIL_DEADLINE_MS) }
        assertFalse(50 in d)
    }

    @Test
    fun overlappingWindowsKeepEarliestDeadline() {
        val d = PiecePlanner.deadlines(span, listOf(10 * mb, 12 * mb), wantHead = false, wantTail = false) { false }
        assertEquals(PiecePlanner.FIRST_DEADLINE_MS, d[span.pieceAt(12 * mb)])
    }

    @Test
    fun contiguousBytesStopsAtGap() {
        val have = (2..10).toSet()
        // от позиции 0: куски 2..10 → до конца куска 10 = 8.5 МБ
        assertEquals(span.pieceEnd(10), PiecePlanner.contiguousBytes(span, 0) { it in have })
        assertEquals(0L, PiecePlanner.contiguousBytes(span, 20 * mb) { it in have })
        assertEquals(0L, PiecePlanner.contiguousBytes(span, 100 * mb) { true })
        assertEquals(100 * mb, PiecePlanner.contiguousBytes(span, 0) { true })
    }

    @Test
    fun bytesToSeconds() {
        assertEquals(60L, PiecePlanner.bytesToSeconds(10 * mb, 100 * mb, 600_000))
        assertEquals(0L, PiecePlanner.bytesToSeconds(10 * mb, 100 * mb, 0))
    }

    @Test
    fun singleFileTorrentFromStart() {
        val s = FileSpan(0, 3 * mb + 1, mb.toInt())
        assertEquals(0, s.firstPiece)
        assertEquals(3, s.lastPiece)
        assertEquals(1L, s.pieceEnd(3) - s.pieceStart(3))
    }
}
