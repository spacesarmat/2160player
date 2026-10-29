package tv.p2160.core.avi

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.avi.AviExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun div3StrfBeforeStrhStillOpensVideoAndUsesStrhDuration() {
        val file = File.createTempFile("div3-order", ".avi")
        file.writeBytes(div3StrfFirstAvi())
        try {
            val output = drive(file, wrap = true)
            val video = output.tracks.values.first { it.type == C.TRACK_TYPE_VIDEO }
            assertEquals(AviHeader.MIME_DIVX3, video.format?.sampleMimeType)
            val expected = 32_981L * 1001L * 1_000_000L / 30_000L
            assertEquals(expected, checkNotNull(output.seekMap).durationUs)
        } finally {
            file.delete()
        }
    }

    @Test
    fun div3FourccIsReadAsMsMpeg4() {
        val output = drive(File(dir, "msmpeg4-div3-mp3.avi"), wrap = true)
        val video = output.tracks.values.first { it.type == C.TRACK_TYPE_VIDEO }
        assertEquals(AviHeader.MIME_DIVX3, video.format?.sampleMimeType)
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
            // Целая формула samples*1e6/rate даёт соседние шаги 26122 и 26123.
            val near = allowed.any { kotlin.math.abs(delta - it) <= 1L }
            assertTrue("timestamp step $delta", near)
        }
    }

    @Test
    fun seekIntoUnevenMp3KeepsSampleClockAndMeetsVideo() {
        val file = File.createTempFile("uneven-mp3", ".avi")
        file.writeBytes(unevenMp3Avi())
        try {
            val extractor = AviPlayback.wrap(AviExtractor())
            FileWindowSource(file).use { source ->
                val output = RecordingOutput { true }
                val driver = ExtractionDriver(extractor, source, output)
                try {
                    driver.run()
                } catch (_: EOFException) {
                }
                val audio = output.tracks.values.first { it.type == C.TRACK_TYPE_AUDIO }
                val video = output.tracks.values.first { it.type == C.TRACK_TYPE_VIDEO }
                assertTrue(audio.samples.size >= 4)
                val linearAudio = audio.samples.map { it.timeUs }
                val linearVideo = video.samples.map { it.timeUs }
                val target = linearAudio[linearAudio.size / 2]
                val point = checkNotNull(output.seekMap).getSeekPoints(target).first
                val audioAt = audio.samples.size
                val videoAt = video.samples.size
                driver.seekTo(point.position, point.timeUs)
                try {
                    driver.run()
                } catch (_: EOFException) {
                }
                val resumedAudio = audio.samples.drop(audioAt)
                val resumedVideo = video.samples.drop(videoAt)
                assertTrue("audio after seek at ${point.position} t=${point.timeUs}", resumedAudio.isNotEmpty())
                assertEquals("pos=${point.position} got=${resumedAudio.first().timeUs}", point.timeUs, resumedAudio.first().timeUs)
                val from = linearAudio.indexOf(resumedAudio.first().timeUs)
                assertTrue(from >= 0)
                resumedAudio.forEachIndexed { i, sample ->
                    assertEquals(linearAudio[from + i], sample.timeUs)
                }
                assertTrue(resumedVideo.isNotEmpty())
                assertTrue(linearVideo.contains(resumedVideo.first().timeUs))
                assertTrue(kotlin.math.abs(resumedVideo.first().timeUs - resumedAudio.first().timeUs) < 50_000)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun msMpeg4CodecInfoKeepsSampleMimeAndDeviceKeyMime() {
        val info = tv.p2160.core.engine.msMpeg4CodecInfo(
            "OMX.realtek.video.dec.divx3",
            androidx.media3.common.MimeTypes.VIDEO_MP43,
            "video/divx3",
            null,
        )
        assertEquals("OMX.realtek.video.dec.divx3", info.name)
        assertEquals(androidx.media3.common.MimeTypes.VIDEO_MP43, info.mimeType)
        assertEquals("video/divx3", info.codecMimeType)
        assertTrue(info.hardwareAccelerated)
        assertTrue(!info.softwareOnly)
    }

    @Test
    fun gmcVolIsRecognizedAndPlainXvidIsNot() {
        assertTrue(Mpeg4Vol.usesGmc(File(dir, "xvid-gmc-mp3.avi").readBytes()))
        assertFalse(Mpeg4Vol.usesGmc(File(dir, "xvid-unpacked-mp3.avi").readBytes()))
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

/** DIV3, strf раньше strh, avih считает все чанки, video strh - 30000/1001. */
private fun div3StrfFirstAvi(): ByteArray {
    val strh = ByteArray(56)
    "vids".toByteArray(Charsets.US_ASCII).copyInto(strh, 0)
    "DIV3".toByteArray(Charsets.US_ASCII).copyInto(strh, 4)
    aviLe32(1001).copyInto(strh, 20)
    aviLe32(30_000).copyInto(strh, 24)
    aviLe32(32_981).copyInto(strh, 32)
    aviLe32(1_036_800).copyInto(strh, 44)
    val strf = ByteArray(40)
    aviLe32(40).copyInto(strf, 0)
    aviLe32(672).copyInto(strf, 4)
    aviLe32(480).copyInto(strf, 8)
    strf[12] = 1
    strf[14] = 24
    "DIV3".toByteArray(Charsets.US_ASCII).copyInto(strf, 16)
    val avih = ByteArray(56)
    aviLe32(33_367).copyInto(avih, 0)
    aviLe32(0x10).copyInto(avih, 12)
    aviLe32(78_833).copyInto(avih, 16)
    val hdrl = aviList(
        "hdrl",
        aviBox("avih", avih) + aviList("strl", aviBox("strf", strf) + aviBox("strh", strh)),
    )
    val payload = ByteArray(8)
    val moviStart = 12 + hdrl.size
    val cursor = moviStart + 12
    val idx = java.io.ByteArrayOutputStream()
    idx.write("00dc".toByteArray(Charsets.US_ASCII))
    idx.write(aviLe32(0x10))
    idx.write(aviLe32(cursor))
    idx.write(aviLe32(payload.size))
    val movi = aviList("movi", aviBox("00dc", payload))
    val body = "AVI ".toByteArray(Charsets.US_ASCII) + hdrl + movi + aviBox("idx1", idx.toByteArray())
    return "RIFF".toByteArray(Charsets.US_ASCII) + aviLe32(body.size) + body
}

/** AVI: видео 25 к/с и MP3 кусками по 1, 3, 2 и 1 кадру. dwLength звука не равен числу кусков. */
private fun unevenMp3Avi(): ByteArray {
    val frame = ByteArray(417)
    frame[0] = 0xFF.toByte()
    frame[1] = 0xFB.toByte()
    frame[2] = 0x90.toByte()
    val hdrl = aviList(
        "hdrl",
        aviBox("avih", aviMainHeader()) +
            aviList("strl", aviBox("strh", aviStreamHeader("vids", 1, 25, 4, 0)) + aviBox("strf", aviVideoFormat())) +
            aviList("strl", aviBox("strh", aviStreamHeader("auds", 1, 44100, 100_000, 0)) + aviBox("strf", aviMp3Format())),
    )
    val pieces = listOf(
        "00dc" to ByteArray(4),
        "01wb" to ByteArray(417).also { frame.copyInto(it) },
        "00dc" to ByteArray(4),
        "01wb" to ByteArray(417 * 3).also { repeat(3) { i -> frame.copyInto(it, i * 417) } },
        "00dc" to ByteArray(4),
        "01wb" to ByteArray(417 * 2).also { repeat(2) { i -> frame.copyInto(it, i * 417) } },
        "00dc" to ByteArray(4),
        "01wb" to ByteArray(417).also { frame.copyInto(it) },
    )
    val moviStart = 12 + hdrl.size
    var cursor = moviStart + 12
    val idx = java.io.ByteArrayOutputStream()
    val moviBody = java.io.ByteArrayOutputStream()
    for ((id, payload) in pieces) {
        val chunk = aviBox(id, payload)
        idx.write(id.toByteArray(Charsets.US_ASCII))
        idx.write(aviLe32(0x10))
        idx.write(aviLe32(cursor))
        idx.write(aviLe32(payload.size))
        moviBody.write(chunk)
        cursor += chunk.size
    }
    val movi = aviList("movi", moviBody.toByteArray())
    val body = "AVI ".toByteArray(Charsets.US_ASCII) + hdrl + movi + aviBox("idx1", idx.toByteArray())
    return "RIFF".toByteArray(Charsets.US_ASCII) + aviLe32(body.size) + body
}

private fun aviMainHeader(): ByteArray {
    val body = ByteArray(56)
    aviLe32(40_000).copyInto(body, 0)
    aviLe32(0x10).copyInto(body, 12)
    aviLe32(4).copyInto(body, 16)
    aviLe32(2).copyInto(body, 24)
    return body
}

private fun aviStreamHeader(type: String, scale: Int, rate: Int, length: Int, sampleSize: Int): ByteArray {
    val body = ByteArray(56)
    type.toByteArray(Charsets.US_ASCII).copyInto(body, 0)
    aviLe32(scale).copyInto(body, 20)
    aviLe32(rate).copyInto(body, 24)
    aviLe32(length).copyInto(body, 32)
    aviLe32(sampleSize).copyInto(body, 44)
    return body
}

private fun aviVideoFormat(): ByteArray {
    val body = ByteArray(40)
    aviLe32(40).copyInto(body, 0)
    aviLe32(16).copyInto(body, 4)
    aviLe32(16).copyInto(body, 8)
    body[12] = 1
    body[14] = 24
    "MJPG".toByteArray(Charsets.US_ASCII).copyInto(body, 16)
    return body
}

private fun aviMp3Format(): ByteArray {
    val body = ByteArray(16)
    body[0] = 0x55
    body[2] = 1
    aviLe32(44100).copyInto(body, 4)
    return body
}

private fun aviList(type: String, body: ByteArray): ByteArray =
    aviBox("LIST", type.toByteArray(Charsets.US_ASCII) + body)

private fun aviBox(id: String, body: ByteArray): ByteArray {
    val pad = if (body.size % 2 == 1) byteArrayOf(0) else ByteArray(0)
    return id.toByteArray(Charsets.US_ASCII) + aviLe32(body.size) + body + pad
}

private fun aviLe32(value: Int): ByteArray = byteArrayOf(
    (value and 0xff).toByte(),
    ((value ushr 8) and 0xff).toByte(),
    ((value ushr 16) and 0xff).toByte(),
    ((value ushr 24) and 0xff).toByte(),
)
