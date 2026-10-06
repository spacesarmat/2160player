package tv.p2160.core.m2ts

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.extractor.ts.TsPayloadReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Прогон по синтетическому M2TS от ffmpeg (LPCM, TrueHD, DTS, E-AC-3 с BD-типами потоков). */
class SyntheticM2tsTest {

    init {
        // В JVM-тестах Build.FINGERPRINT = null, и автоопределение «мы в тесте» в Media3 падает.
        ParsableByteArray.setShouldEnforceLimitOnLegacyMethods(true)
    }

    private fun file(): File {
        val f = Fixtures.synthetic
        assumeTrue("нет ffmpeg для генерации синтетического M2TS", f != null)
        return f!!
    }

    private fun extractAll(
        file: File,
        hints: PidTrackHints? = null,
        exposeCore: Boolean = false,
    ): ExtractionDriver {
        val factory = M2tsExtractorsFactory(exposeTrueHdAc3Core = exposeCore, hintsForUri = { hints })
        val driver = ExtractionDriver(factory.createM2tsExtractor(null), FileWindowSource(file))
        driver.run()
        return driver
    }

    @Test
    fun stockTsExtractorRejectsM2tsButOursAccepts() {
        val source = FileWindowSource(file())
        assertFalse(TsExtractor(DefaultSubtitleParserFactory()).sniff(SourceExtractorInput(source, 0)))
        val ours = M2tsExtractorsFactory().createM2tsExtractor(null)
        assertTrue(ours.sniff(SourceExtractorInput(source, 0)))
    }

    @Test
    fun createsBlurayAudioTracks() {
        val driver = extractAll(file())
        val tracks = driver.output.tracks
        assertEquals(192, (driver.extractor as M2tsExtractor).packetSize)

        val lpcm = tracks.getValue(0x1100).format!!
        assertEquals(MimeTypes.AUDIO_RAW, lpcm.sampleMimeType)
        assertEquals(3, lpcm.channelCount)
        assertEquals(48_000, lpcm.sampleRate)
        assertEquals(C.ENCODING_PCM_24BIT, lpcm.pcmEncoding)

        val truehd = tracks.getValue(0x1101).format!!
        assertEquals(MimeTypes.AUDIO_TRUEHD, truehd.sampleMimeType)
        assertEquals(6, truehd.channelCount)
        assertEquals(48_000, truehd.sampleRate)

        val dts = tracks.getValue(0x1102).format!!
        assertEquals(MimeTypes.AUDIO_DTS, dts.sampleMimeType)
        assertEquals(6, dts.channelCount)

        val eac3 = tracks.getValue(0x1103).format!!
        assertEquals(MimeTypes.AUDIO_E_AC3, eac3.sampleMimeType)

        for (track in tracks.values) {
            assertTrue("нет сэмплов в ${track.id.toString(16)}", track.samples.isNotEmpty())
            val times = track.samples.map { it.timeUs }
            assertEquals("время не монотонно в ${track.id.toString(16)}", times.sorted(), times)
            // ~12 с контента в каждой дорожке.
            assertTrue(times.last() - times.first() in 11_000_000L..12_100_000L)
        }
    }

    @Test
    fun lpcmIsLittleEndianWithoutPaddingChannel() {
        val track = extractAll(file()).output.tracks.getValue(0x1100)
        val bytesPerFrame = 3 * 3
        val totalFrames = track.samples.sumOf { it.size } / bytesPerFrame
        assertTrue("кадров $totalFrames", abs(totalFrames - 12 * 48_000) < 48_000 / 10)

        // Канал 1 (550 Гц) первых кадров: 24-битные little-endian слова.
        val data = track.samples.first().data!!
        for (frame in 1 until 40) {
            val o = frame * bytesPerFrame + 3 // канал 1
            var v = (data[o].toInt() and 0xFF) or ((data[o + 1].toInt() and 0xFF) shl 8) or (data[o + 2].toInt() shl 16)
            val expected = sin(2 * PI * 550 * frame / 48_000.0) * 0x7FFFFF
            assertTrue("кадр $frame: $v vs $expected", abs(v - expected) < 0x7FFFFF * 0.01)
        }
    }

    @Test
    fun trueHdSamplesAreRechunkedBy16AccessUnits() {
        val track = extractAll(file()).output.tracks.getValue(0x1101)
        val samples = track.samples
        assertTrue(samples.size > 10)
        assertTrue("первый сэмпл должен начинаться с major sync", hasMajorSync(splitTrueHdUnits(samples[0].data!!)[0]))
        samples.dropLast(1).forEach { assertEquals(16, splitTrueHdUnits(it.data!!).size) }
        // 16 AU × 40 отсчётов / 48 кГц = 13 333 мкс.
        val steps = samples.zipWithNext { a, b -> b.timeUs - a.timeUs }
        steps.forEach { assertTrue("шаг $it", abs(it - 13_333) <= 2) }
    }

    @Test
    fun durationAndSeekViaPcrBinarySearch() {
        val f = file()
        val driver = ExtractionDriver(M2tsExtractorsFactory().createM2tsExtractor(null), FileWindowSource(f))
        driver.run(stop = { driver.output.seekMap != null })
        val seekMap = driver.output.seekMap!!
        assertTrue(seekMap.isSeekable)
        assertTrue("длительность ${seekMap.durationUs}", abs(seekMap.durationUs - 12_000_000) < 300_000)

        val targetUs = 6_000_000L
        val point = seekMap.getSeekPoints(targetUs).first
        assertTrue("позиция должна попадать в TS-часть пакета M2TS", (point.position - 4) % 192 < 188)
        val lpcm = driver.output.tracks.getValue(0x1100)
        val before = lpcm.samples.size
        driver.seekTo(point.position, targetUs)
        driver.run(stop = { lpcm.samples.size > before })
        val first = lpcm.samples[before].timeUs
        assertTrue("после seek на 6 с первый LPCM-сэмпл в $first", abs(first - targetUs) < 500_000)
    }

    @Test
    fun languageAndLabelHooks() {
        val hints = PidTrackHints(
            languageForPid = { pid -> if (pid == 0x1101) "eng" else if (pid == 0x1100) "rus" else null },
            labelForPid = { pid -> if (pid == 0x1101) "Atmos" else null },
        )
        val tracks = extractAll(file(), hints).output.tracks
        // Media3 нормализует ISO 639-2 → 639-1.
        assertEquals("ru", tracks.getValue(0x1100).format!!.language)
        assertEquals("en", tracks.getValue(0x1101).format!!.language)
        assertEquals("Atmos", tracks.getValue(0x1101).format!!.label)
        assertEquals(null, tracks.getValue(0x1102).format!!.language)
    }

    @Test
    fun trueHdReaderSplitsInterleavedAc3Core() {
        // Берём настоящие AU TrueHD и кадры (E-)AC-3 из синтетики и чередуем их по PES, как на BD.
        val tracks = extractAll(file()).output.tracks
        val units = tracks.getValue(0x1101).samples.flatMap { splitTrueHdUnits(it.data!!) }
        val coreFrames = tracks.getValue(0x1103).samples.map { it.data!! }

        val output = RecordingOutput()
        val reader = TrueHdReader(language = "eng", exposeAc3Core = true)
        reader.createTracks(output, TsPayloadReader.TrackIdGenerator(1, 0x1101, 0x2000))
        var timeUs = 1_000_000L
        var unitIndex = 0
        // Начинаем с хвоста AU без major sync — читатель должен дождаться синхронизации.
        val firstSync = units.indexOfFirst(::hasMajorSync)
        unitIndex = firstSync + 1
        for (frame in coreFrames.take(100)) {
            reader.packetStarted(timeUs, 0)
            reader.consume(ParsableByteArray(frame))
            reader.packetFinished()
            // PES TrueHD: 3 AU, последний режем границей PES пополам.
            val pes = units.subList(unitIndex, unitIndex + 3).fold(ByteArray(0)) { a, b -> a + b }
            val cut = pes.size - 7
            reader.packetStarted(timeUs, 0)
            reader.consume(ParsableByteArray(pes.copyOfRange(0, cut)))
            reader.packetFinished()
            reader.packetStarted(C.TIME_UNSET, 0)
            reader.consume(ParsableByteArray(pes.copyOfRange(cut, pes.size)))
            reader.packetFinished()
            unitIndex += 3
            timeUs += 2_500
        }
        reader.endOfInputReached()

        val thd = output.tracks.getValue(0x1101)
        val core = output.tracks.getValue(0x1101 + 0x2000)
        assertEquals(MimeTypes.AUDIO_TRUEHD, thd.format!!.sampleMimeType)
        assertEquals(MimeTypes.AUDIO_E_AC3, core.format!!.sampleMimeType)
        assertEquals(TrueHdReader.DEFAULT_CORE_LABEL, core.format!!.label)
        assertNotEquals(thd.format!!.id, core.format!!.id)
        assertEquals(100, core.samples.size)
        val emittedUnits = thd.samples.flatMap { splitTrueHdUnits(it.data!!) }
        assertTrue(hasMajorSync(emittedUnits.first()))
        // Все AU после первого major sync дошли целыми и по порядку.
        val expected = units.subList(firstSync + 1, unitIndex).dropWhile { !hasMajorSync(it) }
        assertEquals(expected.size, emittedUnits.size)
        expected.zip(emittedUnits).forEach { (a, b) -> assertTrue(a.contentEquals(b)) }
    }

    @Test
    fun factoryPutsM2tsExtractorFirst() {
        val factory = M2tsExtractorsFactory(fallback = { arrayOf(Mp3Extractor()) })
        val extractors = factory.createExtractors()
        assertTrue(extractors[0] is M2tsExtractor)
        assertTrue(extractors[1] is Mp3Extractor)
    }
}
