package tv.p2160.core.intro

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.p2160.core.api.SegmentType
import java.io.File
import java.nio.file.Files
import kotlin.math.abs

class IntroAnalyzerTest {

    private val rate = 11_025
    private val seconds = 480.0
    private val config = IntroAnalyzer.Config(introWindowMs = 200_000, creditsWindowMs = 120_000)

    /** Серия 8 мин: заставка 40 с на [introAt], титры 50 с до самого конца. */
    private fun episode(seed: Int, introAt: Double, theme: Int = 7, ending: Int = 8): ShortArray {
        val x = SyntheticAudio.bed(seed, seconds, rate, 0.7 + (seed % 3) * 0.2)
        SyntheticAudio.insert(x, SyntheticAudio.theme(theme, 40.0, rate), introAt, rate, 0.7)
        SyntheticAudio.insert(x, SyntheticAudio.theme(ending, 50.0, rate), seconds - 50.0, rate, 0.6)
        SyntheticAudio.noise(x, 0.003, seed)
        return SyntheticAudio.toPcm(x)
    }

    private class Counter {
        var opens = 0
    }

    private fun ep(key: String, pcm: ShortArray, c: Counter) = IntroAnalyzer.Episode(key) {
        c.opens++
        ArrayPcmSource(pcm, 11_025)
    }

    private fun near(expectedSec: Double, ms: Long?, tol: Double = 1.5) =
        assertTrue("expected $expectedSec s, got ${ms?.div(1000.0)}", ms != null && abs(ms / 1000.0 - expectedSec) <= tol)

    @Test
    fun detectsCachesAndLearnsTemplates() = runBlocking {
        val dir = Files.createTempDirectory("intro").toFile()
        try {
            val store = IntroStore(dir)
            val analyzer = IntroAnalyzer(store, config)
            val c = Counter()
            val e1 = episode(1, 30.0)
            val e2 = episode(2, 75.0)
            val e3 = episode(3, 5.0)

            val r = analyzer.analyze(ep("e2", e2, c), listOf(ep("e1", e1, c), ep("e3", e3, c)), seriesKey = "show")
            assertEquals(3, c.opens)
            near(75.0, r.intro?.startMs)
            near(115.0, r.intro?.endMs)
            near(seconds - 50.0, r.credits?.startMs)
            assertNull("credits run to the end", r.credits?.endMs)
            assertTrue(r.introConfidence > 0.5f)

            // Повторно — из кэша, без декодирования.
            c.opens = 0
            assertEquals(r, analyzer.analyze(ep("e2", e2, c), emptyList(), "show"))
            assertEquals(0, c.opens)

            // Пустой итог с соседом без заставки пересчитывается, когда появляется другой сосед.
            val pilot = episode(5, 60.0, theme = 70, ending = 71)
            val lone = episode(6, 100.0, theme = 80, ending = 81)
            assertNull(analyzer.analyze(ep("lone", lone, c), listOf(ep("pilot", pilot, c))).intro)
            c.opens = 0
            assertNull(analyzer.analyze(ep("lone", lone, c), listOf(ep("pilot", pilot, c))).intro)
            assertEquals(0, c.opens)
            val twin = episode(7, 20.0, theme = 80, ending = 81)
            near(100.0, analyzer.analyze(ep("lone", lone, c), listOf(ep("pilot", pilot, c), ep("twin", twin, c))).intro?.startMs)
            c.opens = 0

            // Новая серия без соседей: находится по шаблону сериала, декодируется только она.
            val e4 = episode(4, 140.0)
            val r4 = analyzer.analyze(ep("e4", e4, c), emptyList(), seriesKey = "show")
            assertEquals(1, c.opens)
            near(140.0, r4.intro?.startMs)
            near(180.0, r4.intro?.endMs)
            near(seconds - 50.0, r4.credits?.startMs)

            // Отпечатки соседей тоже закэшированы: e1 с соседями e2, e3 не открывает их заново.
            c.opens = 0
            val r1 = IntroAnalyzer(store, config).analyze(ep("e1", e1, c), listOf(ep("e2", e2, c), ep("e3", e3, c)))
            assertEquals(0, c.opens)
            near(30.0, r1.intro?.startMs)

            // Другой сериал с тем же ключом шаблонов, но другой музыкой — шаблон не срабатывает.
            val other = episode(9, 60.0, theme = 50, ending = 51)
            val r9 = analyzer.analyze(ep("x9", other, c), emptyList(), seriesKey = "show")
            assertNull(r9.intro)
            assertNull(r9.credits)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun undecodableSourceGivesEmptyResult() = runBlocking {
        val dir = Files.createTempDirectory("intro").toFile()
        try {
            val analyzer = IntroAnalyzer(IntroStore(dir), config)
            val r = analyzer.analyze(IntroAnalyzer.Episode("dts-only") { null }, listOf(IntroAnalyzer.Episode("b") { null }))
            assertEquals(DetectionResult.EMPTY, r)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun storeRoundTrip() {
        val dir = Files.createTempDirectory("intro").toFile()
        try {
            val store = IntroStore(dir, maxFingerprints = 2)
            val fp = Fingerprint(1000, 123.8, intArrayOf(1, 2, -3), intArrayOf(-1, 0, 7), 186.0)
            store.putFingerprint("a", StoredFingerprint(fp, 99_000))
            val back = store.fingerprint("a")!!
            assertEquals(99_000, back.fileDurationMs)
            assertTrue(back.fingerprint.frames.contentEquals(fp.frames))
            assertTrue(back.fingerprint.masks.contentEquals(fp.masks))
            assertEquals(1000, back.fingerprint.startMs)
            assertNull(store.fingerprint("b"))

            val res = DetectionResult(
                intro = tv.p2160.core.api.SkipSegment(SegmentType.INTRO, 1000, 91000),
                credits = tv.p2160.core.api.SkipSegment(SegmentType.CREDITS, 1_300_000, null),
                introConfidence = 0.9f,
                creditsConfidence = 0.7f,
            )
            store.putResult("a", res)
            assertEquals(res, store.result("a"))

            store.putTemplates("show", SeriesTemplates(listOf(fp), emptyList()))
            assertEquals(1, store.templates("show").intros.size)
            assertEquals(0, store.templates("other").intros.size)

            // Обрезка старых отпечатков.
            store.putFingerprint("b", StoredFingerprint(fp, 1))
            File(dir, "fp").listFiles()!!.forEach { it.setLastModified(1000) }
            store.putFingerprint("c", StoredFingerprint(fp, 1))
            assertEquals(2, File(dir, "fp").listFiles()!!.size)
            assertNotNull(store.fingerprint("c"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun siblingsAroundIndex() {
        val items = listOf("e1", "e2", "e3", "e4", "e5")
        assertEquals(listOf("e2", "e4"), IntroDetector.pickSiblings(items, 2))
        assertEquals(listOf("e2", "e3"), IntroDetector.pickSiblings(items, 0))
        assertEquals(listOf("e4", "e3"), IntroDetector.pickSiblings(items, 4))
        assertEquals(emptyList<String>(), IntroDetector.pickSiblings(listOf("x"), 0))
    }
}
