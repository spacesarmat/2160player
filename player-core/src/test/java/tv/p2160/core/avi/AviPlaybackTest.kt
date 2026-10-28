package tv.p2160.core.avi

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.avi.AviExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.p2160.core.m2ts.ExtractionDriver
import tv.p2160.core.m2ts.FileWindowSource
import tv.p2160.core.m2ts.RecordingOutput
import java.io.EOFException
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AviPlaybackTest {
    private val dir = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }
        .resolve("player-core/src/test/resources/avi")

    @Test
    fun packedXvidBecomesOneVopPerSample() {
        val raw = videoSamples(File(dir, "xvid-libxvid-mp3.avi"), wrap = false)
        assertTrue("fixture is not packed", raw.any { Mpeg4PackedUnpacker.vopCount(it) > 1 })
        val unpacked = videoSamples(File(dir, "xvid-libxvid-mp3.avi"), wrap = true)
        assertTrue(unpacked.isNotEmpty())
        unpacked.forEach { sample ->
            assertTrue("sample still has ${Mpeg4PackedUnpacker.vopCount(sample)} VOPs", Mpeg4PackedUnpacker.vopCount(sample) <= 1)
        }
        fun kept(samples: List<ByteArray>) = samples.sumOf { sample ->
            val vops = Mpeg4PackedUnpacker.vopCount(sample)
            if (vops == 1 && sample.size <= Mpeg4PackedUnpacker.MAX_NVOP_SIZE) 0 else vops
        }
        assertEquals(kept(raw), kept(unpacked))
    }

    @Test
    fun unpackedXvidStaysOneVopAndPlaysWithoutIndex() {
        for (name in listOf("xvid-unpacked-mp3.avi", "xvid-unpacked-mp3-no-idx1.avi", "xvid-unpacked-ac3.avi", "xvid-gmc-mp3.avi")) {
            val samples = videoSamples(File(dir, name), wrap = true)
            assertTrue("$name produced no video", samples.isNotEmpty())
            samples.forEach { assertTrue(Mpeg4PackedUnpacker.vopCount(it) <= 1) }
        }
    }

    @Test
    fun div3FourccIsReadAsMsMpeg4() {
        val output = drive(File(dir, "msmpeg4-div3-mp3.avi"), wrap = true)
        val video = output.tracks.values.first { it.type == C.TRACK_TYPE_VIDEO }
        assertEquals(MimeTypes.VIDEO_MP43, video.format?.sampleMimeType)
        val tagged = drive(File(dir, "msmpeg4-mp43-mp3.avi"), wrap = true)
        val video2 = tagged.tracks.values.first { it.type == C.TRACK_TYPE_VIDEO }
        assertEquals(MimeTypes.VIDEO_MP43, video2.format?.sampleMimeType)
    }

    @Test
    fun mp3TimestampsFollowFrameDuration() {
        val output = drive(File(dir, "xvid-libxvid-mp3.avi"), wrap = true)
        val audio = output.tracks.values.first { it.type == C.TRACK_TYPE_AUDIO }
        assertEquals(MimeTypes.AUDIO_MPEG, audio.format?.sampleMimeType)
        assertTrue(audio.samples.size >= 2)
        val allowed = longArrayOf(
            1152L * 1_000_000L / 44100L,
            1152L * 1_000_000L / 48000L,
            576L * 1_000_000L / 44100L,
            576L * 1_000_000L / 48000L,
        )
        audio.samples.zipWithNext { a, b -> b.timeUs - a.timeUs }.forEach { delta ->
            assertTrue("timestamp step $delta", delta in allowed)
        }
    }

    @Test
    fun ac3AudioIsKept() {
        val output = drive(File(dir, "xvid-unpacked-ac3.avi"), wrap = true)
        val audio = output.tracks.values.first { it.type == C.TRACK_TYPE_AUDIO }
        assertEquals(MimeTypes.AUDIO_AC3, audio.format?.sampleMimeType)
        assertTrue(audio.samples.isNotEmpty())
    }

    private fun videoSamples(file: File, wrap: Boolean): List<ByteArray> {
        val output = drive(file, wrap)
        return output.tracks.values.filter { it.type == C.TRACK_TYPE_VIDEO }
            .flatMap { track -> track.samples.mapNotNull { it.data } }
    }

    private fun drive(file: File, wrap: Boolean): RecordingOutput {
        val extractor: Extractor = if (wrap) AviPlayback.wrap(AviExtractor()) else AviExtractor()
        FileWindowSource(file).use { source ->
            val output = RecordingOutput { true }
            try {
                ExtractionDriver(extractor, source, output).run()
            } catch (_: EOFException) {
                // Без idx1 AviExtractor читает чанк за концом movi. Кадры до этого уже отданы.
            }
            return output
        }
    }
}
