package tv.p2160.core.m2ts

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.pgs.PgsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * Реальные Blu-ray файлы с NAS (Y:). Читаем только начало/окна по десяткам МБ и хвост для длительности.
 * Нет файлов — тесты пропускаются.
 */
class RealBlurayFilesTest {

    init {
        ParsableByteArray.setShouldEnforceLimitOnLegacyMethods(true)
    }

    /** PGS остаются сырыми (как с FLAG_EMIT_RAW_SUBTITLE_DATA), чтобы проверить их PgsParser'ом. */
    private fun rawSubtitleExtractor() =
        M2tsExtractorsFactory().apply {
            @Suppress("DEPRECATION")
            experimentalSetTextTrackTranscodingEnabled(false)
        }.createM2tsExtractor(null)

    private fun fallen(): File {
        assumeTrue("нет ${Fixtures.fallen}", Fixtures.fallen.isFile)
        return Fixtures.fallen
    }

    @Test
    fun fallenTracksDurationAndSamples() {
        val source = FileWindowSource(fallen())
        val driver = ExtractionDriver(rawSubtitleExtractor(), source)
        driver.run(maxBytes = 64L shl 20)
        val tracks = driver.output.tracks
        println("Fallen: прочитано физически ${source.physicalBytesRead shr 20} МБ, дорожки: " +
            tracks.values.joinToString { "%x %s/%s n=%d".format(it.id, it.format?.sampleMimeType, it.format?.language, it.samples.size) })

        assertEquals(192, (driver.extractor as M2tsExtractor).packetSize)
        assertEquals(MimeTypes.VIDEO_H264, tracks.getValue(0x1011).format!!.sampleMimeType)
        assertEquals(MimeTypes.AUDIO_AC3, tracks.getValue(0x1103).format!!.sampleMimeType)
        for (pid in intArrayOf(0x1100, 0x1101, 0x1102, 0x1104)) {
            // 0x86 = DTS-HD MA: ядро + ExSS → AUDIO_DTS_HD (Media3 штатно принял бы за SCTE-35).
            assertEquals(MimeTypes.AUDIO_DTS_HD, tracks.getValue(pid).format!!.sampleMimeType)
            assertEquals(6, tracks.getValue(pid).format!!.channelCount)
        }
        for (pid in intArrayOf(0x1200, 0x1201, 0x1202)) {
            assertEquals(MimeTypes.APPLICATION_PGS, tracks.getValue(pid).format!!.sampleMimeType)
        }
        assertEquals("ru", tracks.getValue(0x1100).format!!.language)
        assertEquals("en", tracks.getValue(0x1104).format!!.language)

        for (track in tracks.values.filter { it.type == C.TRACK_TYPE_AUDIO || it.id == 0x1011 }) {
            assertTrue("нет сэмплов в %x".format(track.id), track.samples.isNotEmpty())
        }
        for (track in tracks.values.filter { it.type == C.TRACK_TYPE_AUDIO }) {
            val times = track.samples.map { it.timeUs }
            assertEquals("время не монотонно в %x".format(track.id), times.sorted(), times)
        }
        val keyTimes = tracks.getValue(0x1011).samples.filter { it.flags and C.BUFFER_FLAG_KEY_FRAME != 0 }.map { it.timeUs }
        assertTrue(keyTimes.size >= 2)
        assertEquals(keyTimes.sorted(), keyTimes)

        // Длительность по PCR (ffmpeg: 02:04:11.39) — читали только начало и ~115 КБ хвоста.
        val seekMap = driver.output.seekMap!!
        assertTrue(seekMap.isSeekable)
        assertTrue("длительность ${seekMap.durationUs}", abs(seekMap.durationUs - 7_451_390_000L) < 1_000_000)
    }

    @Test
    fun fallenSeekToMiddleOf43GbFile() {
        val source = FileWindowSource(fallen())
        val driver = ExtractionDriver(rawSubtitleExtractor(), source)
        driver.run(stop = { driver.output.seekMap != null })
        val seekMap = driver.output.seekMap!!
        val video = driver.output.tracks.getValue(0x1011)

        for (targetUs in longArrayOf(seekMap.durationUs / 2, seekMap.durationUs - 60_000_000L, 30_000_000L)) {
            val point = seekMap.getSeekPoints(targetUs).first
            assertTrue("позиция должна попадать в TS-часть пакета", (point.position - 4) % 192 < 188)
            val before = video.samples.size
            val readBefore = source.physicalBytesRead
            val stepsBefore = driver.seekPositions.size
            driver.seekTo(point.position, targetUs)
            driver.run(stop = { video.samples.drop(before).any { it.flags and C.BUFFER_FLAG_KEY_FRAME != 0 } })
            val key = video.samples.drop(before).first { it.flags and C.BUFFER_FLAG_KEY_FRAME != 0 }
            val readMb = (source.physicalBytesRead - readBefore) / 1e6
            println("seek %.1f с → ключевой кадр %.3f с, бинарный поиск %d шагов, прочитано %.1f МБ".format(
                targetUs / 1e6, key.timeUs / 1e6, driver.seekPositions.size - stepsBefore, readMb))
            // TsBinarySearchSeeker останавливается до цели, первый ключевой кадр — в пределах GOP.
            assertTrue("ключевой кадр ${key.timeUs} для цели $targetUs", key.timeUs in (targetUs - 3_000_000)..(targetUs + 3_000_000))
            assertTrue("seek прочитал $readMb МБ", readMb < 64)
        }
    }

    @Test
    fun fallenPgsDisplaySetsParseIntoCues() {
        val file = fallen()
        // Окна в середине фильма, где точно есть диалоги.
        var cuesWithBitmap = 0
        var displaySets = 0
        for (offsetGb in longArrayOf(10, 18, 26)) {
            val base = (offsetGb shl 30) / 192 * 192
            val source = FileWindowSource(file, base, 48L shl 20)
            val driver = ExtractionDriver(rawSubtitleExtractor(), source)
            driver.run()
            val pgs = driver.output.tracks.values.filter { it.format?.sampleMimeType == MimeTypes.APPLICATION_PGS }
            assertEquals(3, pgs.size)
            for (track in pgs) {
                val times = track.samples.map { it.timeUs }
                assertEquals(times.sorted(), times)
                for (sample in track.samples) {
                    displaySets++
                    val parsed = mutableListOf<CuesWithTiming>()
                    PgsParser().parse(sample.data!!, SubtitleParser.OutputOptions.allCues()) { parsed += it }
                    assertEquals(1, parsed.size)
                    for (cue in parsed[0].cues) {
                        assertNotNull(cue.bitmap)
                        val bitmap = cue.bitmap!!
                        assertTrue(bitmap.width > 0 && bitmap.height > 0)
                        cuesWithBitmap++
                    }
                }
            }
            println("PGS окно $offsetGb ГБ: " + pgs.joinToString { "%x n=%d".format(it.id, it.samples.size) })
            if (cuesWithBitmap >= 3) break
        }
        assertTrue("display set'ов $displaySets, картинок $cuesWithBitmap", cuesWithBitmap > 0)
    }

    @Test
    fun aquamanIsoWindowAsM2ts() {
        assumeTrue("нет ${Fixtures.aquamanIso}", Fixtures.aquamanIso.isFile)
        // Окно M2TS основного фильма внутри ISO (sync-байт на 2 000 000 068, выравнивание 192 проверено).
        val source = FileWindowSource(Fixtures.aquamanIso, 2_000_000_064L, 64L shl 20)
        val driver = ExtractionDriver(rawSubtitleExtractor(), source)
        driver.run()
        val tracks = driver.output.tracks
        println("Aquaman: " + tracks.values.joinToString { "%x %s n=%d".format(it.id, it.format?.sampleMimeType, it.samples.size) })
        assertEquals(MimeTypes.VIDEO_H264, tracks.getValue(0x1011).format!!.sampleMimeType)
        for (pid in intArrayOf(0x1100, 0x1101, 0x1102)) {
            assertEquals(MimeTypes.AUDIO_AC3, tracks.getValue(pid).format!!.sampleMimeType)
        }
        assertEquals(MimeTypes.AUDIO_DTS_HD, tracks.getValue(0x1103).format!!.sampleMimeType)
        for (track in tracks.values.filter { it.type != C.TRACK_TYPE_TEXT }) {
            assertTrue("нет сэмплов в %x".format(track.id), track.samples.isNotEmpty())
        }
        // Окно = самостоятельный «файл»: длительность по PCR и seek в его середину.
        val seekMap = driver.output.seekMap!!
        assertTrue(seekMap.isSeekable)
        val target = seekMap.durationUs / 2
        val audio = tracks.getValue(0x1103)
        val before = audio.samples.size
        driver.seekTo(seekMap.getSeekPoints(target).first.position, target)
        driver.run(stop = { audio.samples.size > before })
        println("Aquaman seek: длительность окна ${seekMap.durationUs}, цель $target, позиция ${seekMap.getSeekPoints(target).first.position}, первый DTS ${audio.samples[before].timeUs}, шагов ${driver.seekPositions}")
        assertTrue(abs(audio.samples[before].timeUs - target) < 1_000_000)
    }
}
