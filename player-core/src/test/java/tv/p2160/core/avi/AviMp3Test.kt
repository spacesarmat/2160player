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
        val audio = AviMp3AudioOutput(out)
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
