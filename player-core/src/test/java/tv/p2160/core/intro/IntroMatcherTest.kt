package tv.p2160.core.intro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class IntroMatcherTest {

    private val rate = 11_025
    private val fp = Fingerprinter()
    private val headSec = 360.0

    /** «Начало серии»: фон [bedSeed] и тема [themeSeed] на [themeAt] секунде. */
    private fun episode(
        bedSeed: Int,
        themeSeed: Int,
        themeAt: Double,
        gain: Double,
        synthRate: Int = rate,
        seconds: Double = headSec,
        themeSec: Double = 40.0,
    ): ShortArray {
        val bed = SyntheticAudio.bed(bedSeed, seconds, synthRate, 0.6 + (bedSeed % 3) * 0.3)
        SyntheticAudio.insert(bed, SyntheticAudio.theme(themeSeed, themeSec, synthRate), themeAt, synthRate, gain)
        SyntheticAudio.noise(bed, 0.004, bedSeed + 100)
        return if (synthRate == rate) SyntheticAudio.toPcm(bed) else SyntheticAudio.resample(bed, synthRate, rate)
    }

    private fun assertNear(expectedSec: Double, actualMs: Long, tolSec: Double = 1.5) {
        assertTrue("expected ${expectedSec}s, got ${actualMs / 1000.0}s", abs(actualMs / 1000.0 - expectedSec) <= tolSec)
    }

    @Test
    fun introFoundAtDifferentOffsets() {
        val a = fp.fingerprint(episode(1, 7, 62.0, 0.8))
        val b = fp.fingerprint(episode(2, 7, 95.0, 0.5, synthRate = 22_050))
        val t0 = System.nanoTime()
        val found = IntroMatcher.findIntro(a, listOf(b))
        println("match 2×${headSec.toInt()}s: ${(System.nanoTime() - t0) / 1_000_000} ms, $found")
        assertNotNull(found)
        found!!
        assertNear(62.0, found.startMs)
        assertNear(102.0, found.endMs)
        assertNear(95.0, found.match.bStartMs)
        assertNear(135.0, found.match.bEndMs)
    }

    @Test
    fun threeEpisodesAgree() {
        val a = fp.fingerprint(episode(11, 5, 30.0, 0.7, themeSec = 55.0))
        val b = fp.fingerprint(episode(12, 5, 140.0, 0.9, themeSec = 55.0))
        val c = fp.fingerprint(episode(13, 5, 3.0, 0.4, synthRate = 16_000, themeSec = 55.0))
        val found = IntroMatcher.findIntro(a, listOf(b, c))
        assertNotNull(found)
        assertNear(30.0, found!!.startMs)
        assertNear(85.0, found.endMs)
        assertTrue(found.confidence > 0.5f)
    }

    @Test
    fun noFalsePositiveForDifferentThemes() {
        val a = fp.fingerprint(episode(21, 100, 62.0, 0.8))
        val b = fp.fingerprint(episode(22, 200, 95.0, 0.8))
        assertNull(IntroMatcher.findIntro(a, listOf(b)))
        // Тема только в одном из двух соседей: пара с другим соседом не подтверждает, одна пара — только при высокой уверенности.
        val c = fp.fingerprint(episode(23, 300, 10.0, 0.8))
        assertNull(IntroMatcher.findIntro(a, listOf(b, c)))
    }

    @Test
    fun shortCommonSoundIsIgnored() {
        // Общий джингл 8 с — меньше минимальных 15 с.
        val a = fp.fingerprint(episode(31, 9, 50.0, 0.8, themeSec = 8.0))
        val b = fp.fingerprint(episode(32, 9, 120.0, 0.8, themeSec = 8.0))
        assertNull(IntroMatcher.findIntro(a, listOf(b)))
    }

    @Test
    fun creditsAtTheEnd() {
        val tail = 240.0
        // Титры 70 с: во втором файле до самого конца, в первом после них ещё 5 с фона.
        val a = episode(41, 77, tail - 75.0, 0.7, seconds = tail, themeSec = 70.0)
        val b = episode(42, 77, tail - 70.0, 0.6, seconds = tail, themeSec = 70.0)
        val fa = fp.fingerprint(a, startMs = 1_200_000)
        val fb = fp.fingerprint(b, startMs = 1_500_000)
        val found = IntroMatcher.findCredits(fa, listOf(fb))
        assertNotNull(found)
        assertNear(1200.0 + tail - 75.0, found!!.startMs)
        assertNear(1200.0 + tail - 5.0, found.endMs, 2.0)
    }

    @Test
    fun creditsPreferEndOverLongerMiddleSegment() {
        val tail = 300.0
        val a = SyntheticAudio.bed(51, tail, rate, 0.8)
        val b = SyntheticAudio.bed(52, tail, rate, 0.8)
        val mid = SyntheticAudio.theme(500, 60.0, rate)
        val end = SyntheticAudio.theme(600, 30.0, rate)
        SyntheticAudio.insert(a, mid, 40.0, rate, 0.7)
        SyntheticAudio.insert(b, mid, 100.0, rate, 0.7)
        SyntheticAudio.insert(a, end, tail - 30.0, rate, 0.7)
        SyntheticAudio.insert(b, end, tail - 30.0, rate, 0.7)
        val found = IntroMatcher.findCredits(fp.fingerprint(SyntheticAudio.toPcm(a)), listOf(fp.fingerprint(SyntheticAudio.toPcm(b))))
        assertNotNull(found)
        assertNear(tail - 30.0, found!!.startMs)
    }

    @Test
    fun templateFoundInNewEpisode() {
        val a = fp.fingerprint(episode(61, 8, 62.0, 0.8))
        val b = fp.fingerprint(episode(62, 8, 95.0, 0.5))
        val found = IntroMatcher.findIntro(a, listOf(b))!!
        val template = a.slice(found.match.aFrom, found.match.aTo)
        val c = fp.fingerprint(episode(63, 8, 210.0, 0.6, synthRate = 22_050))
        val t = IntroMatcher.matchTemplate(template, c)
        assertNotNull(t)
        assertNear(210.0, t!!.startMs)
        assertNear(250.0, t.endMs)
        assertNull(IntroMatcher.matchTemplate(template, fp.fingerprint(episode(64, 99, 50.0, 0.8))))
    }

    @Test
    fun fingerprintIgnoresGain() {
        val x = SyntheticAudio.theme(3, 20.0, rate)
        val loud = fp.fingerprint(SyntheticAudio.toPcm(x.map { it * 0.9f }.toFloatArray()))
        val quiet = fp.fingerprint(SyntheticAudio.toPcm(x.map { it * 0.1f }.toFloatArray()))
        // Надёжные биты и сама маска от громкости не зависят.
        var diff = 0
        var maskDiff = 0
        for (i in loud.frames.indices) {
            val m = loud.masks[i] and quiet.masks[i]
            diff += Integer.bitCount((loud.frames[i] xor quiet.frames[i]) and m)
            maskDiff += Integer.bitCount(loud.masks[i] xor quiet.masks[i])
        }
        assertTrue("mean bits ${diff.toDouble() / loud.size}", diff.toDouble() / loud.size < 1.0)
        assertTrue("mask bits ${maskDiff.toDouble() / loud.size}", maskDiff.toDouble() / loud.size < 1.0)
        // Тишина — пустые кадры.
        val silent = fp.fingerprint(ShortArray(rate * 5))
        assertTrue(silent.masks.all { it == 0 })
    }

    @Test
    fun resamplerKeepsTone() {
        val from = 48_000
        val x = FloatArray(from * 2) { (0.5 * sin(2 * PI * 1000.0 * it / from)).toFloat() }
        val y = SyntheticAudio.resample(x, from, rate)
        assertEquals(rate * 2.0, y.size.toDouble(), 20.0)
        // Сравнение с идеальной синусоидой на выходной частоте (фаза совпадает — задержка компенсирована).
        var err = 0.0
        var ref = 0.0
        for (i in 200 until y.size - 200) {
            val e = 0.5 * sin(2 * PI * 1000.0 * i / rate)
            err += (y[i] / 32767.0 - e).let { it * it }
            ref += e * e
        }
        assertTrue("snr ${ref / err}", ref / err > 100)
    }

    @Test
    fun performanceTenMinutes() {
        val ten = SyntheticAudio.toPcm(SyntheticAudio.bed(71, 600.0, rate, 1.0))
        val t0 = System.nanoTime()
        val f = fp.fingerprint(ten)
        val t1 = System.nanoTime()
        val g = fp.fingerprint(SyntheticAudio.toPcm(SyntheticAudio.bed(72, 600.0, rate, 1.0)))
        val t2 = System.nanoTime()
        IntroMatcher.findIntro(f, listOf(g))
        val t3 = System.nanoTime()
        // Передискретизация 48 кГц → 11 025 Гц (как после декодера), 600 с порциями по 4096.
        val r = Resampler(48_000, rate)
        val chunk = FloatArray(4096) { (it % 97) / 97f - 0.5f }
        var out = 0L
        val t4 = System.nanoTime()
        repeat(48_000 * 600 / 4096) { r.process(chunk, chunk.size) { _, c -> out += c } }
        val t5 = System.nanoTime()
        println(
            "fingerprint 600s: ${(t1 - t0) / 1_000_000} ms (${f.size} frames); match 600s×600s: ${(t3 - t2) / 1_000_000} ms; " +
                "resample 600s 48k: ${(t5 - t4) / 1_000_000} ms ($out samples)",
        )
    }
}
