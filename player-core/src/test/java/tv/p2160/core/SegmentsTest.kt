package tv.p2160.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.p2160.core.api.Chapter
import tv.p2160.core.api.SegmentType
import tv.p2160.core.api.SkipSegment
import tv.p2160.core.engine.ManualMarks
import tv.p2160.core.engine.SegmentDetector
import tv.p2160.core.engine.TimeInput

class SegmentsTest {

    @Test
    fun timeInput() {
        assertEquals(90_000L, TimeInput.parse("90"))
        assertEquals((12 * 60 + 30) * 1000L, TimeInput.parse("1230"))
        assertEquals((1 * 3600 + 23 * 60 + 45) * 1000L, TimeInput.parse("12345"))
        assertEquals((1 * 3600 + 23 * 60 + 45) * 1000L, TimeInput.parse("1:23:45"))
        assertEquals((5 * 60 + 7) * 1000L, TimeInput.parse("5:07"))
        assertNull(TimeInput.parse(""))
        assertNull(TimeInput.parse("1:2:3:4"))
        assertNull(TimeInput.parse("abc"))
    }

    @Test
    fun seriesKeyFromFileName() {
        assertEquals("the expanse", SegmentDetector.seriesKey("The.Expanse.S02E05.1080p.WEB-DL.mkv"))
        assertEquals("the expanse", SegmentDetector.seriesKey("The Expanse s02e06.mkv"))
        assertEquals("friends", SegmentDetector.seriesKey("Friends 3x12.avi"))
        assertEquals("твин пикс", SegmentDetector.seriesKey("Твин Пикс. Серия 4.mkv"))
        assertEquals("frieren", SegmentDetector.seriesKey("[SubsPlease] Frieren - 05 (1080p).mkv"))
        assertNull(SegmentDetector.seriesKey("Inception.2010.2160p.mkv"))
    }

    @Test
    fun chapterNames() {
        assertEquals(SegmentType.INTRO, SegmentDetector.typeOfChapter("Opening"))
        assertEquals(SegmentType.INTRO, SegmentDetector.typeOfChapter("OP"))
        assertEquals(SegmentType.INTRO, SegmentDetector.typeOfChapter("Вступление"))
        assertEquals(SegmentType.CREDITS, SegmentDetector.typeOfChapter("End Credits"))
        assertEquals(SegmentType.CREDITS, SegmentDetector.typeOfChapter("ED"))
        assertEquals(SegmentType.CREDITS, SegmentDetector.typeOfChapter("Титры"))
        assertEquals(SegmentType.RECAP, SegmentDetector.typeOfChapter("Previously on"))
        assertEquals(SegmentType.PREVIEW, SegmentDetector.typeOfChapter("Preview"))
        assertNull(SegmentDetector.typeOfChapter("Chapter 3"))
        assertNull(SegmentDetector.typeOfChapter("Opera house"))
        val segments = SegmentDetector.fromChapters(
            listOf(Chapter("Intro", 0, 90_000), Chapter("Part A", 90_000, 600_000), Chapter("Credits", 600_000, 660_000))
        )
        assertEquals(listOf(SegmentType.INTRO, SegmentType.CREDITS), segments.map { it.type })
    }

    @Test
    fun segmentListFormat() {
        val parsed = SkipSegment.parseList("intro:0-90000;credits:1320000-; bogus:1-2; recap:500-100")
        assertEquals(listOf(SkipSegment(SegmentType.INTRO, 0, 90_000), SkipSegment(SegmentType.CREDITS, 1_320_000, null)), parsed)
        assertEquals(parsed, SkipSegment.parseList(SkipSegment.formatList(parsed)))
    }

    @Test
    fun manualMarksCreditsFromEnd() {
        val marks = ManualMarks(introStartMs = 30_000, introEndMs = 120_000, creditsFromEndMs = 60_000)
        val segments = marks.toSegments(durationMs = 1_500_000)
        assertEquals(SkipSegment(SegmentType.INTRO, 30_000, 120_000), segments[0])
        assertEquals(SkipSegment(SegmentType.CREDITS, 1_440_000, null), segments[1])
        assertTrue(segments[1].contains(1_450_000, 1_500_000))
        assertFalse(segments[1].contains(1_400_000, 1_500_000))
    }
}
