package tv.p2160.core.avi

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.TrackOutput
import java.io.ByteArrayOutputStream

/**
 * Кадр MPEG audio. В AVI время куска берётся из dwScale/dwRate и часто не совпадает
 * с длительностью кадра MP3 (VBR, чужой nBlockAlign). Декодер тогда пишет
 * Unexpected audio track timestamp discontinuity, и звуковые часы почти стоят.
 */
internal data class MpegAudioFrame(val size: Int, val sampleRate: Int, val samplesPerFrame: Int)

internal object MpegAudio {
    private val bitrateMpeg1Layer3 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
    private val bitrateMpeg2Layer3 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0)
    private val rateMpeg1 = intArrayOf(44100, 48000, 32000)
    private val rateMpeg2 = intArrayOf(22050, 24000, 16000)
    private val rateMpeg25 = intArrayOf(11025, 12000, 8000)

    /** Заголовок кадра с [offset], или null если это не целый кадр Layer III. */
    fun frameAt(data: ByteArray, offset: Int): MpegAudioFrame? {
        if (offset < 0 || offset + 4 > data.size) return null
        val b0 = data[offset].toInt() and 0xFF
        val b1 = data[offset + 1].toInt() and 0xFF
        val b2 = data[offset + 2].toInt() and 0xFF
        if (b0 != 0xFF || b1 and 0xE0 != 0xE0) return null
        val version = (b1 shr 3) and 0x3
        val layer = (b1 shr 1) and 0x3
        val bitrateIndex = (b2 shr 4) and 0xF
        val rateIndex = (b2 shr 2) and 0x3
        val padding = (b2 shr 1) and 0x1
        // version 01 зарезервирован, layer 01 это Layer III, bitrate 0000 и 1111 недопустимы.
        if (version == 1 || layer != 1 || bitrateIndex == 0 || bitrateIndex == 15 || rateIndex == 3) return null
        val bitrateKbps = if (version == 3) bitrateMpeg1Layer3[bitrateIndex] else bitrateMpeg2Layer3[bitrateIndex]
        val sampleRate = when (version) {
            3 -> rateMpeg1[rateIndex]
            2 -> rateMpeg2[rateIndex]
            else -> rateMpeg25[rateIndex]
        }
        if (bitrateKbps == 0 || sampleRate == 0) return null
        val samples = if (version == 3) 1152 else 576
        val frameSize = if (version == 3) {
            144000 * bitrateKbps / sampleRate + padding
        } else {
            72000 * bitrateKbps / sampleRate + padding
        }
        if (frameSize < 4) return null
        return MpegAudioFrame(frameSize, sampleRate, samples)
    }
}

/**
 * Режет звук AVI на кадры MP3 и ставит время по числу сэмплов, а не по метке куска.
 * Не-MP3 проходит как было.
 */
@OptIn(UnstableApi::class)
internal class AviMp3AudioOutput(private val delegate: TrackOutput) : TrackOutput {
    private val pending = ByteArrayOutputStream()
    private var mp3 = false
    private var tail = ByteArray(0)
    private var clockUs = C.TIME_UNSET

    fun reset() {
        pending.reset()
        tail = ByteArray(0)
        clockUs = C.TIME_UNSET
    }

    override fun format(format: Format) {
        mp3 = format.sampleMimeType == MimeTypes.AUDIO_MPEG
        if (!mp3) reset()
        delegate.format(format)
    }

    override fun sampleData(input: androidx.media3.common.DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
        if (!mp3) return delegate.sampleData(input, length, allowEndOfInput, sampleDataPart)
        if (length <= 0) return 0
        val tmp = ByteArray(length)
        var got = 0
        while (got < length) {
            val n = input.read(tmp, got, length - got)
            if (n == C.RESULT_END_OF_INPUT) {
                if (allowEndOfInput && got == 0) return C.RESULT_END_OF_INPUT
                break
            }
            if (n <= 0) break
            got += n
        }
        if (sampleDataPart == TrackOutput.SAMPLE_DATA_PART_MAIN && got > 0) pending.write(tmp, 0, got)
        return got
    }

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        if (!mp3) {
            delegate.sampleData(data, length, sampleDataPart)
            return
        }
        if (sampleDataPart == TrackOutput.SAMPLE_DATA_PART_MAIN && length > 0) {
            pending.write(data.data, data.position, length)
        }
        data.skipBytes(length)
    }

    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
        if (!mp3) {
            delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
            return
        }
        val all = pending.toByteArray()
        pending.reset()
        val end = all.size - offset
        val start = (end - size).coerceAtLeast(0)
        val chunk = if (start < end && end <= all.size) all.copyOfRange(start, end) else ByteArray(0)
        if (offset > 0 && end < all.size) pending.write(all, end, all.size - end)
        val data = if (tail.isEmpty()) chunk else tail + chunk
        if (clockUs == C.TIME_UNSET) clockUs = timeUs
        var offsetIn = 0
        var skipped = 0
        while (offsetIn + 4 <= data.size && skipped <= MAX_SKIP) {
            val frame = MpegAudio.frameAt(data, offsetIn)
            if (frame == null) {
                offsetIn++
                skipped++
                continue
            }
            if (offsetIn + frame.size > data.size) break
            skipped = 0
            val bytes = data.copyOfRange(offsetIn, offsetIn + frame.size)
            delegate.sampleData(ParsableByteArray(bytes), bytes.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
            delegate.sampleMetadata(clockUs, C.BUFFER_FLAG_KEY_FRAME, bytes.size, 0, null)
            clockUs += frame.samplesPerFrame * 1_000_000L / frame.sampleRate
            offsetIn += frame.size
        }
        tail = if (offsetIn < data.size) data.copyOfRange(offsetIn, data.size) else ByteArray(0)
        if (tail.size > MAX_TAIL) tail = tail.copyOfRange(tail.size - 4, tail.size)
    }

    override fun durationUs(durationUs: Long) = delegate.durationUs(durationUs)

    private companion object {
        const val MAX_SKIP = 8192
        const val MAX_TAIL = 16 * 1024
    }
}
