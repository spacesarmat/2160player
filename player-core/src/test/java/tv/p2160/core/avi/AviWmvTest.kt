package tv.p2160.core.avi

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.extractor.avi.AviExtractor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

/** WMV/VC-1 в AVI: fourcc уходит в MJPG-заглушку, MIME и extradata собираются в strf. */
class AviWmvTest {
    @Test
    fun wmv3KeepsBitmapExtraAsInitializationData() {
        val extra = byteArrayOf(0x4A, 0x10, 0x00, 0x00)
        val hdrl = hdrl(compression = "WMV3", width = 160, height = 120, extra = extra)
        val facts = checkNotNull(AviHeader.tryFixHdrl(hdrl, 0, hdrl.size))
        val video = facts.videos.single()
        val wmv = checkNotNull(video.wmv)
        assertEquals("WMV3", wmv.fourcc)
        assertEquals(AviWmv.MIME_WMV3, wmv.mime)
        assertEquals(160, wmv.width)
        assertEquals(120, wmv.height)
        assertArrayEquals(extra, wmv.initializationData)
        // В буфере fourcc подменён на заглушку, иначе Media3 выбросит дорожку.
        assertEquals("MJPG", video.compression)
        assertTrue(String(hdrl, Charsets.ISO_8859_1).contains("MJPG"))
    }

    @Test
    fun wvc1AndWmv1AndWmv2MapToTheirMimes() {
        assertEquals(MimeTypes.VIDEO_VC1, AviWmv.mimeFor("WVC1"))
        assertEquals(AviWmv.MIME_WMV3, AviWmv.mimeFor("WMV3"))
        assertEquals(AviWmv.MIME_WMV1, AviWmv.mimeFor("WMV1"))
        assertEquals(AviWmv.MIME_WMV2, AviWmv.mimeFor("wmv2"))
        assertNull(AviWmv.mimeFor("XVID"))
        val two = AviWmv.fromStrf("wmv2", 32, 32, 42, strfBody("wmv2", 32, 32, byteArrayOf(1, 2)), 0)
        assertEquals(2, two.initializationData.size)
    }

    @Test
    fun reparsingThePatchedHeaderDoesNotDuplicateTracks() {
        val hdrl = hdrl(compression = "WMV3", width = 16, height = 16, extra = byteArrayOf(9))
        val first = checkNotNull(AviHeader.tryFixHdrl(hdrl, 0, hdrl.size))
        assertEquals(1, first.videos.size)
        // Повторный разбор уже подменённого буфера: MJPG, без второй WMV-дорожки.
        val second = checkNotNull(AviHeader.tryFixHdrl(hdrl, 0, hdrl.size))
        assertEquals(1, second.videos.size)
        assertEquals("MJPG", second.videos.single().compression)
        assertNull(second.videos.single().wmv)
    }

    /** Тело hdrl: avih и LIST/strl (strh + strf с хвостом BITMAPINFOHEADER). */
    private fun hdrl(compression: String, width: Int, height: Int, extra: ByteArray): ByteArray {
        val strh = ByteArray(56)
        "vids".toByteArray(Charsets.US_ASCII).copyInto(strh, 0)
        compression.toByteArray(Charsets.US_ASCII).copyInto(strh, 4)
        le(1001).copyInto(strh, 20)
        le(30_000).copyInto(strh, 24)
        le(32_981).copyInto(strh, 32)
        val strf = strfBody(compression, width, height, extra)
        val avih = ByteArray(56)
        le(33_367).copyInto(avih, 0)
        le(78_833).copyInto(avih, 16)
        return box("avih", avih) + box("LIST", "strl".toByteArray(Charsets.US_ASCII) + box("strh", strh) + box("strf", strf))
    }

    private fun strfBody(compression: String, width: Int, height: Int, extra: ByteArray): ByteArray {
        val body = ByteArray(40 + extra.size)
        le(40).copyInto(body, 0)
        le(width).copyInto(body, 4)
        le(height).copyInto(body, 8)
        body[12] = 1
        body[14] = 24
        compression.toByteArray(Charsets.US_ASCII).copyInto(body, 16)
        extra.copyInto(body, 40)
        return body
    }

    private fun box(id: String, body: ByteArray): ByteArray =
        id.toByteArray(Charsets.US_ASCII) + le(body.size) + body + if (body.size % 2 == 1) byteArrayOf(0) else ByteArray(0)

    private fun le(value: Int): ByteArray = byteArrayOf(
        (value and 0xff).toByte(),
        ((value ushr 8) and 0xff).toByte(),
        ((value ushr 16) and 0xff).toByte(),
        ((value ushr 24) and 0xff).toByte(),
    )
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
