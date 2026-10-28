package tv.p2160.core.avi

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.extractor.avi.AviExtractor
import org.junit.Assert.assertArrayEquals
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

class AviWmvTest {
    @Test
    fun wmv3KeepsBitmapExtraAsInitializationData() {
        val extra = byteArrayOf(0x4A, 0x10, 0x00, 0x00)
        val strf = strf(fourcc = "WMV3", width = 160, height = 120, extra = extra)
        val state = AviHeaderState()
        AviWmv.claim(strf, 0, strf.size, 0, state)
        assertEquals(1, state.wmv.size)
        val video = state.wmv.first()
        assertEquals("WMV3", video.fourcc)
        assertEquals(AviWmv.MIME_WMV3, video.mime)
        assertEquals(160, video.width)
        assertEquals(120, video.height)
        assertArrayEquals(extra, video.initializationData)
        assertEquals("MJPG", String(strf, 8 + 16, 4, Charsets.ISO_8859_1))
    }

    @Test
    fun wvc1AndWmv1AndWmv2MapToTheirMimes() {
        assertEquals(MimeTypes.VIDEO_VC1, claimFourcc("WVC1", byteArrayOf(0, 0, 1, 0x0F)).mime)
        assertEquals(AviWmv.MIME_WMV1, claimFourcc("WMV1", ByteArray(0)).mime)
        assertEquals(AviWmv.MIME_WMV2, claimFourcc("wmv2", byteArrayOf(1, 2)).mime)
        assertEquals(2, claimFourcc("wmv2", byteArrayOf(1, 2)).initializationData.size)
    }

    @Test
    fun theSameHeaderIsNotClaimedTwice() {
        val strf = strf("WMV3", 16, 16, byteArrayOf(9))
        val state = AviHeaderState()
        AviWmv.claim(strf, 0, strf.size, 100, state)
        val again = strf("WMV3", 16, 16, byteArrayOf(9))
        AviWmv.claim(again, 0, again.size, 100, state)
        assertEquals(1, state.wmv.size)
    }

    private fun claimFourcc(fourcc: String, extra: ByteArray): WmvVideo {
        val strf = strf(fourcc, 32, 32, extra)
        val state = AviHeaderState()
        AviWmv.claim(strf, 0, strf.size, 0, state)
        return state.wmv.single()
    }

    /** Чанк strf: id, размер, BITMAPINFOHEADER из 40 байт и хвост. */
    private fun strf(fourcc: String, width: Int, height: Int, extra: ByteArray): ByteArray {
        val body = ByteArray(40 + extra.size)
        le32(body, 0, 40)
        le32(body, 4, width)
        le32(body, 8, height)
        body[12] = 1
        body[14] = 24
        fourcc.encodeToByteArray().copyInto(body, 16)
        extra.copyInto(body, 40)
        val chunk = ByteArray(8 + body.size)
        "strf".encodeToByteArray().copyInto(chunk)
        le32(chunk, 4, body.size)
        body.copyInto(chunk, 8)
        return chunk
    }

    private fun le32(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value shr 8).toByte()
        target[offset + 2] = (value shr 16).toByte()
        target[offset + 3] = (value shr 24).toByte()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AviWmvFileTest {
    private val dir = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }
        .resolve("player-core/src/test/resources/avi")

    @Test
    fun wmv1AndWmv2AviExposeTheCodecAndSamples() {
        assertVideo("wmv1-mp3.avi", AviWmv.MIME_WMV1, extraBytes = 0)
        assertVideo("wmv2-mp3.avi", AviWmv.MIME_WMV2, extraBytes = 4)
    }

    private fun assertVideo(name: String, mime: String, extraBytes: Int) {
        val file = File(dir, name)
        FileWindowSource(file).use { source ->
            val output = RecordingOutput { true }
            try {
                ExtractionDriver(AviPlayback.wrap(AviExtractor()), source, output).run()
            } catch (_: EOFException) {
            }
            val video = output.tracks.values.first { it.type == C.TRACK_TYPE_VIDEO }
            assertEquals(mime, video.format?.sampleMimeType)
            val extra = video.format?.initializationData?.firstOrNull()?.size ?: 0
            assertEquals(extraBytes, extra)
            assertTrue("$name has no frames", video.samples.isNotEmpty())
        }
    }
}
