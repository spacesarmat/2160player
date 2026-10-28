@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package tv.p2160.core.avi

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.TrackOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AviMp3Test {
    @Test
    fun frameHeaderGives1152SamplesAt44100() {
        val frame = mpeg1Layer3Frame()
        val parsed = MpegAudio.frameAt(frame, 0)
        assertEquals(417, parsed?.size)
        assertEquals(44100, parsed?.sampleRate)
        assertEquals(1152, parsed?.samplesPerFrame)
    }

    @Test
    fun twoFramesInOneChunkGetFrameDurationsNotTheContainerStamp() {
        val frame = mpeg1Layer3Frame()
        val chunk = frame + frame
        val out = CollectingTrack()
        val audio = AviMp3AudioOutput(out, AviMp3Index()) { null }
        audio.format(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_MPEG).setSampleRate(44100).build())
        audio.sampleData(ParsableByteArray(chunk), chunk.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
        // Контейнер ставит на весь кусок одну метку. Кадры должны разойтись на 1152/44100 с.
        audio.sampleMetadata(0, C.BUFFER_FLAG_KEY_FRAME, chunk.size, 0, null)
        assertEquals(2, out.times.size)
        assertEquals(0L, out.times[0])
        val step = 1152L * 1_000_000L / 44100L
        assertEquals(step, out.times[1])
        assertTrue(out.sizes.all { it == 417 })
    }

    @Test
    fun frameSplitAcrossChunksIsOneFrame() {
        val frame = mpeg1Layer3Frame()
        val out = CollectingTrack()
        val audio = AviMp3AudioOutput(out, AviMp3Index()) { null }
        audio.format(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_MPEG).setSampleRate(44100).build())
        val head = frame.copyOf(200)
        val tail = frame.copyOfRange(200, frame.size)
        audio.sampleData(ParsableByteArray(head), head.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
        audio.sampleMetadata(0, C.BUFFER_FLAG_KEY_FRAME, head.size, 0, null)
        assertTrue(out.times.isEmpty())
        audio.sampleData(ParsableByteArray(tail), tail.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
        audio.sampleMetadata(50_000, C.BUFFER_FLAG_KEY_FRAME, tail.size, 0, null)
        assertEquals(listOf(0L), out.times)
        assertEquals(listOf(417), out.sizes)
    }

    @Test
    fun freeFormatFallsBackToContainerTime() {
        val junk = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x02, 0x00, 1, 2, 3, 4)
        val out = CollectingTrack()
        val audio = AviMp3AudioOutput(out, AviMp3Index()) { null }
        audio.format(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_MPEG).build())
        audio.sampleData(ParsableByteArray(junk), junk.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
        audio.sampleMetadata(12_000, C.BUFFER_FLAG_KEY_FRAME, junk.size, 0, null)
        assertEquals(listOf(12_000L), out.times)
        assertEquals(listOf(junk.size), out.sizes)
    }

    @Test
    fun seekRestoresSampleClockNotContainerStamp() {
        val frame = mpeg1Layer3Frame()
        val step = 1152L * 1_000_000L / 44100L
        val index = AviMp3Index()
        val out = CollectingTrack()
        val audio = AviMp3AudioOutput(out, index) { null }
        audio.format(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_MPEG).setSampleRate(44100).build())
        // Куски по 1 и 3 кадра. Метки контейнера нарочно ровные и неверные.
        emit(audio, frame, 1, 0)
        emit(audio, frame, 3, 10_000)
        audio.onSeek()
        out.times.clear()
        out.sizes.clear()
        emit(audio, frame, 3, 10_000)
        assertEquals(3, out.times.size)
        assertEquals(1152L * 1_000_000L / 44100L, out.times[0])
        assertEquals(2304L * 1_000_000L / 44100L, out.times[1])
        assertEquals(3456L * 1_000_000L / 44100L, out.times[2])
    }

    private fun emit(audio: AviMp3AudioOutput, frame: ByteArray, count: Int, containerUs: Long) {
        val chunk = ByteArray(frame.size * count)
        repeat(count) { frame.copyInto(chunk, it * frame.size) }
        audio.sampleData(ParsableByteArray(chunk), chunk.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
        audio.sampleMetadata(containerUs, C.BUFFER_FLAG_KEY_FRAME, chunk.size, 0, null)
    }

    private fun mpeg1Layer3Frame(): ByteArray {
        val frame = ByteArray(417)
        frame[0] = 0xFF.toByte()
        frame[1] = 0xFB.toByte()
        frame[2] = 0x90.toByte()
        frame[3] = 0x00
        return frame
    }
}

private class CollectingTrack : TrackOutput {
    val times = ArrayList<Long>()
    val sizes = ArrayList<Int>()

    override fun format(format: Format) = Unit

    override fun sampleData(input: androidx.media3.common.DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int = 0

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        data.skipBytes(length)
    }

    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
        times += timeUs
        sizes += size
    }
}
