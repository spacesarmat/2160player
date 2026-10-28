package tv.p2160.core.avi

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import java.io.ByteArrayOutputStream

/**
 * Кадр MPEG audio. В AVI время куска берётся из dwScale/dwRate и не зависит от того,
 * сколько кадров MP3 в куске. Декодер тогда пишет Unexpected audio track timestamp discontinuity.
 */
internal data class MpegAudioFrame(val size: Int, val sampleRate: Int, val samplesPerFrame: Int)

internal object MpegAudio {
    private val bitrateMpeg1Layer3 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
    private val bitrateMpeg2Layer3 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0)
    private val rateMpeg1 = intArrayOf(44100, 48000, 32000)
    private val rateMpeg2 = intArrayOf(22050, 24000, 16000)
    private val rateMpeg25 = intArrayOf(11025, 12000, 8000)
    private const val MAX_FRAME = 1728

    /**
     * Заголовок Layer III с [offset]. null, если это не кадр.
     * Размер может вылезать за [data]: тогда это хвост куска, его надо склеить со следующим.
     */
    fun frameAt(data: ByteArray, offset: Int): MpegAudioFrame? {
        if (headerKind(data, offset) != Header.FRAME) return null
        val b1 = data[offset + 1].toInt() and 0xFF
        val b2 = data[offset + 2].toInt() and 0xFF
        val version = (b1 shr 3) and 0x3
        val bitrateIndex = (b2 shr 4) and 0xF
        val rateIndex = (b2 shr 2) and 0x3
        val padding = (b2 shr 1) and 0x1
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
        if (frameSize < 4 || frameSize > MAX_FRAME) return null
        return MpegAudioFrame(frameSize, sampleRate, samples)
    }

    /** Свободный битрейт (индекс 0000): длину кадра из заголовка не узнать. */
    fun isFreeFormat(data: ByteArray, offset: Int): Boolean = headerKind(data, offset) == Header.FREE

    private fun headerKind(data: ByteArray, offset: Int): Header {
        if (offset < 0 || offset + 4 > data.size) return Header.NONE
        val b0 = data[offset].toInt() and 0xFF
        val b1 = data[offset + 1].toInt() and 0xFF
        val b2 = data[offset + 2].toInt() and 0xFF
        if (b0 != 0xFF || b1 and 0xE0 != 0xE0) return Header.NONE
        val version = (b1 shr 3) and 0x3
        val layer = (b1 shr 1) and 0x3
        val bitrateIndex = (b2 shr 4) and 0xF
        val rateIndex = (b2 shr 2) and 0x3
        if (version == 1 || layer != 1 || rateIndex == 3 || bitrateIndex == 15) return Header.NONE
        if (bitrateIndex == 0) return Header.FREE
        return Header.FRAME
    }

    private enum class Header { NONE, FRAME, FREE }
}

/** Сколько PCM-сэмплов было до каждого куска. Ключ - метка куска, которую ставит ChunkReader. */
internal class AviMp3Index {
    data class Entry(
        val containerTimeUs: Long,
        val offset: Long,
        val payload: Long,
        val samplesBefore: Long,
        val samplesInChunk: Long,
    )

    private val entries = ArrayList<Entry>()
    var sampleRate: Int = 0
        private set

    fun noteRate(rate: Int) {
        if (sampleRate == 0 && rate > 0) sampleRate = rate
    }

    fun add(containerTimeUs: Long, offset: Long, payload: Long, samplesInChunk: Long) {
        if (samplesInChunk <= 0) return
        if (entries.any { it.containerTimeUs == containerTimeUs }) return
        val before = if (entries.isEmpty()) 0L else entries.last().samplesBefore + entries.last().samplesInChunk
        entries += Entry(containerTimeUs, offset, payload, before, samplesInChunk)
    }

    fun samplesBefore(containerTimeUs: Long): Long? =
        entries.firstOrNull { it.containerTimeUs == containerTimeUs }?.samplesBefore

    /** По смещению заголовка или полезной нагрузки, если метка ChunkReader после seek сдвинута. */
    fun samplesBeforePosition(position: Long): Long? =
        entries.firstOrNull {
            it.offset == position || it.payload == position ||
                (it.payload >= 8 && it.payload - 8 == position) ||
                (it.offset >= 0 && it.offset + 8 == position)
        }?.samplesBefore

    /** Кусок, чьё время по сэмплам не позже [timeUs]. null, пока индекс пуст. */
    fun seekEntry(timeUs: Long): Entry? {
        if (sampleRate <= 0 || entries.isEmpty()) return null
        var best: Entry? = null
        for (entry in entries) {
            val at = entry.samplesBefore * 1_000_000L / sampleRate
            if (at <= timeUs) best = entry else break
        }
        return best ?: entries.first()
    }

    fun sampleTimeUs(entry: Entry): Long =
        if (sampleRate <= 0) 0L else entry.samplesBefore * 1_000_000L / sampleRate
}

/**
 * SeekMap по числу сэмплов, а не по номеру куска.
 * Пока индекс не собран, остаётся карта Media3.
 */
@OptIn(UnstableApi::class)
internal class AviMp3SeekMap(
    private val delegate: SeekMap,
    private val index: AviMp3Index,
) : SeekMap {
    override fun isSeekable(): Boolean = delegate.isSeekable
    /** Подмена длины: Media3 не даёт короткому video strh уменьшить avih. */
    var overrideDurationUs: Long = C.TIME_UNSET
    override fun getDurationUs(): Long = overrideDurationUs.takeIf { it > 0 } ?: delegate.getDurationUs()

    override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
        val entry = index.seekEntry(timeUs) ?: return delegate.getSeekPoints(timeUs)
        if (entry.offset < 0) return delegate.getSeekPoints(timeUs)
        val audio = SeekPoint(index.sampleTimeUs(entry), entry.offset)
        val video = delegate.getSeekPoints(timeUs).first
        // Раньше из двух точек, чтобы не перескочить видеокадр, который лежит перед этим куском звука.
        return if (video.position in 1 until audio.position) {
            SeekMap.SeekPoints(SeekPoint(audio.timeUs, video.position))
        } else {
            SeekMap.SeekPoints(audio)
        }
    }
}

/**
 * Режет звук AVI на кадры MP3 и ставит время по числу сэмплов.
 * Не-MP3 проходит как было. Свободный битрейт, мусор и битый заголовок возвращают куски
 * к прежним меткам контейнера, а не выдают их за кадры.
 */
@OptIn(UnstableApi::class)
internal class AviMp3AudioOutput(
    private val delegate: TrackOutput,
    private val index: AviMp3Index,
    private val rawSeekMap: () -> SeekMap?,
) : TrackOutput {
    private val pending = ByteArrayOutputStream()
    private var mp3 = false
    private var fallback = false
    private var tail = ByteArray(0)
    /** -1: шкала ещё не поставлена. Дальше число PCM-сэмплов от начала потока. */
    private var samplesOut = -1L
    private var badChunks = 0
    private var payloadOffset = -1L

    fun onSeek() {
        pending.reset()
        tail = ByteArray(0)
        samplesOut = -1L
        payloadOffset = -1L
    }

    override fun format(format: Format) {
        mp3 = format.sampleMimeType == MimeTypes.AUDIO_MPEG
        if (!mp3) onSeek()
        delegate.format(format)
    }

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
        if (!mp3 || fallback) return delegate.sampleData(input, length, allowEndOfInput, sampleDataPart)
        if (pending.size() == 0 && input is ExtractorInput) payloadOffset = input.position
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
        if (!mp3 || fallback) {
            delegate.sampleData(data, length, sampleDataPart)
            return
        }
        if (sampleDataPart == TrackOutput.SAMPLE_DATA_PART_MAIN && length > 0) {
            pending.write(data.data, data.position, length)
        }
        data.skipBytes(length)
    }

    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
        if (!mp3 || fallback) {
            delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
            return
        }
        val all = pending.toByteArray()
        pending.reset()
        val end = all.size - offset
        val start = (end - size).coerceAtLeast(0)
        val chunk = if (start < end && end <= all.size) all.copyOfRange(start, end) else ByteArray(0)
        if (offset > 0 && end < all.size) pending.write(all, end, all.size - end)
        if (chunk.isEmpty() && tail.isEmpty()) return
        if (startsWithFreeFormat(chunk)) {
            giveUp(chunk, timeUs, flags, cryptoData)
            return
        }
        val data = if (tail.isEmpty()) chunk else tail + chunk
        val before = (if (payloadOffset >= 0) index.samplesBeforePosition(payloadOffset) else null)
            ?: index.samplesBefore(timeUs)
        if (before != null) samplesOut = before
        else if (samplesOut < 0) samplesOut = timeUsToSamples(timeUs)
        var offsetIn = 0
        var skipped = 0
        var frames = 0
        var samples = 0L
        while (offsetIn + 4 <= data.size && skipped <= MAX_SKIP) {
            if (MpegAudio.isFreeFormat(data, offsetIn)) {
                giveUp(chunk, timeUs, flags, cryptoData)
                return
            }
            val frame = MpegAudio.frameAt(data, offsetIn)
            if (frame == null) {
                offsetIn++
                skipped++
                continue
            }
            if (offsetIn + frame.size > data.size) break
            val next = offsetIn + frame.size
            val followed = next + 1 < data.size &&
                data[next].toInt() and 0xFF == 0xFF &&
                data[next + 1].toInt() and 0xE0 == 0xE0
            // Ложный синхрослово в середине куска. Кадр с границы куска или перед нулями оставляем:
            // иначе 48 кГц CBR с выравниванием не отдаёт ни одного кадра, и видео встаёт на первом.
            if (!followed && skipped > 0 && !padding(data, next)) {
                offsetIn++
                skipped++
                continue
            }
            skipped = 0
            index.noteRate(frame.sampleRate)
            val bytes = data.copyOfRange(offsetIn, offsetIn + frame.size)
            val stamp = samplesOut * 1_000_000L / frame.sampleRate
            delegate.sampleData(ParsableByteArray(bytes), bytes.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
            delegate.sampleMetadata(stamp, C.BUFFER_FLAG_KEY_FRAME, bytes.size, 0, null)
            samplesOut += frame.samplesPerFrame
            samples += frame.samplesPerFrame
            frames++
            offsetIn += frame.size
            if (!followed) {
                if (padding(data, offsetIn)) offsetIn = data.size
                break
            }
        }
        val rest = if (offsetIn < data.size) data.copyOfRange(offsetIn, data.size) else ByteArray(0)
        if (frames == 0 && rest.size > MAX_TAIL) {
            badChunks++
            tail = ByteArray(0)
            if (badChunks >= BAD_CHUNK_LIMIT) giveUp(chunk, timeUs, flags, cryptoData)
            return
        }
        if (frames > 0) badChunks = 0
        tail = rest
        val payload = payloadOffset
        payloadOffset = -1L
        // Смещение из idx1 на таких файлах схлопывается в первый кусок: метки контейнера слишком близки.
        // Берём место, откуда кусок реально прочитан (заголовок на 8 байт раньше нагрузки).
        val offset = if (payload >= 8) payload - 8 else rawSeekMap()?.getSeekPoints(timeUs)?.first?.position ?: -1L
        if (frames > 0) index.add(timeUs, offset, payload, samples)
    }

    override fun durationUs(durationUs: Long) {
        val cap = AviProbe.durationUs
        delegate.durationUs(if (cap > 0 && durationUs > cap) cap else durationUs)
    }

    private fun padding(data: ByteArray, from: Int): Boolean {
        var i = from
        var nonzero = 0
        while (i < data.size) {
            if (data[i] != 0.toByte()) nonzero++
            if (nonzero > 1) return false
            i++
        }
        return true
    }

    /** Непрочитанный кусок: стартуем от метки контейнера, дальше снова считаем сэмплы. */
    private fun timeUsToSamples(timeUs: Long): Long {
        val rate = index.sampleRate
        return if (rate > 0) timeUs * rate / 1_000_000L else 0L
    }

    private fun startsWithFreeFormat(chunk: ByteArray): Boolean {
        if (tail.isNotEmpty()) return false
        return MpegAudio.isFreeFormat(chunk, 0)
    }

    private fun giveUp(chunk: ByteArray, timeUs: Long, flags: Int, cryptoData: TrackOutput.CryptoData?) {
        fallback = true
        tail = ByteArray(0)
        pending.reset()
        if (chunk.isEmpty()) return
        delegate.sampleData(ParsableByteArray(chunk), chunk.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
        delegate.sampleMetadata(timeUs, flags, chunk.size, 0, cryptoData)
    }

    private companion object {
        const val MAX_SKIP = 4096
        const val MAX_TAIL = 1728
        const val BAD_CHUNK_LIMIT = 2
    }
}
