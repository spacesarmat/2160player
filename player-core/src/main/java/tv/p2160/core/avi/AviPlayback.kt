package tv.p2160.core.avi

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.avi.AviExtractor
import java.io.ByteArrayOutputStream

/**
 * AVI: распаковка packed B-кадров MPEG-4 и подмена fourcc DIV3/DIV4 на MP43.
 * Media3 знает MP43 (video/mp43) и не знает DIV3, хотя это тот же MS-MPEG4.
 */
@OptIn(UnstableApi::class)
internal object AviPlayback {
    fun factory(delegate: ExtractorsFactory): ExtractorsFactory = object : ExtractorsFactory by delegate {
        override fun createExtractors(): Array<Extractor> = ordered(delegate.createExtractors())

        override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
            ordered(delegate.createExtractors(uri, responseHeaders))
    }

    /**
     * Media3 ставит Mp3Extractor раньше AviExtractor. У cab-view AVI в movi лежат
     * подряд валидные кадры MP3 (Eurostar: с байта 10260), а content:// не даёт тип файла,
     * и файл открывается как звук. RIFF....AVI  забирает только наш экстрактор.
     */
    internal fun ordered(raw: Array<Extractor>): Array<Extractor> {
        val wrapped = raw.map(::wrap)
        val avi = wrapped.filterIsInstance<AviFixExtractor>()
        val rest = wrapped.filter { it !is AviFixExtractor }.map { RiffAviBlocked(it) }
        val head = if (avi.isEmpty()) listOf(AviFixExtractor(AviExtractor())) else avi
        return (head + rest).toTypedArray()
    }

    fun wrap(extractor: Extractor): Extractor =
        if (extractor is AviExtractor) AviFixExtractor(extractor) else extractor
}

/** Чужой экстрактор не должен забирать файл, который начинается с RIFF....AVI . */
@OptIn(UnstableApi::class)
internal class RiffAviBlocked(private val delegate: Extractor) : Extractor by delegate {
    override fun sniff(input: ExtractorInput): Boolean {
        if (input.looksLikeRiffAvi()) return false
        return delegate.sniff(input)
    }
}

@OptIn(UnstableApi::class)
internal fun ExtractorInput.looksLikeRiffAvi(): Boolean {
    val header = ByteArray(12)
    return try {
        if (!peekFully(header, 0, 12, true)) false
        else header[0] == 'R'.code.toByte() &&
            header[1] == 'I'.code.toByte() &&
            header[2] == 'F'.code.toByte() &&
            header[3] == 'F'.code.toByte() &&
            header[8] == 'A'.code.toByte() &&
            header[9] == 'V'.code.toByte() &&
            header[10] == 'I'.code.toByte() &&
            header[11] == ' '.code.toByte()
    } catch (_: Throwable) {
        false
    } finally {
        try {
            resetPeekPosition()
        } catch (_: Throwable) {
        }
    }
}

@OptIn(UnstableApi::class)
private class AviFixExtractor(private val delegate: Extractor) : Extractor by delegate {
    private val unpackers = ArrayList<Mpeg4PackedUnpacker>()
    private val mp3 = ArrayList<AviMp3AudioOutput>()
    private val mp3Index = AviMp3Index()
    private var rawSeekMap: SeekMap? = null

    override fun init(output: ExtractorOutput) {
        unpackers.clear()
        mp3.clear()
        rawSeekMap = null
        AviProbe.clear()
        delegate.init(object : ExtractorOutput by output {
            override fun track(id: Int, type: Int): TrackOutput {
                Log.i(TAG, "AVI track id=$id type=$type")
                val track = output.track(id, type)
                if (type == C.TRACK_TYPE_AUDIO) {
                    val audio = AviMp3AudioOutput(track, mp3Index) { rawSeekMap }
                    mp3 += audio
                    return audio
                }
                if (type != C.TRACK_TYPE_VIDEO) return track
                val unpacker = Mpeg4PackedUnpacker()
                unpackers += unpacker
                return UnpackingTrackOutput(track, unpacker)
            }

            override fun endTracks() {
                Log.i(TAG, "AVI endTracks")
                output.endTracks()
            }

            override fun seekMap(seekMap: SeekMap) {
                // Сырая карта нужна, чтобы запомнить смещение куска. Наружу отдаём карту по сэмплам.
                rawSeekMap = seekMap
                val wrapped = AviMp3SeekMap(seekMap, mp3Index)
                val duration = AviProbe.durationUs
                if (duration > 0) wrapped.overrideDurationUs = duration
                Log.i(TAG, "AVI seekMap durationUs=${wrapped.getDurationUs()} raw=${seekMap.durationUs}")
                output.seekMap(wrapped)
            }
        })
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int =
        delegate.read(AviFourccInput(input), seekPosition)

    override fun seek(position: Long, timeUs: Long) {
        unpackers.forEach { it.reset() }
        mp3.forEach { it.onSeek() }
        delegate.seek(position, timeUs)
    }
}

@OptIn(UnstableApi::class)
private class AviFourccInput(private val delegate: ExtractorInput) : ExtractorInput by delegate {
    override fun readFully(target: ByteArray, offset: Int, length: Int) {
        delegate.readFully(target, offset, length)
        note(target, offset, length, peeked = false)
    }

    override fun readFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
        val ok = delegate.readFully(target, offset, length, allowEndOfInput)
        if (ok) note(target, offset, length, peeked = false)
        return ok
    }

    override fun peekFully(target: ByteArray, offset: Int, length: Int) {
        delegate.peekFully(target, offset, length)
        note(target, offset, length, peeked = true)
    }

    override fun peekFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
        val ok = delegate.peekFully(target, offset, length, allowEndOfInput)
        if (ok) note(target, offset, length, peeked = true)
        return ok
    }

    private fun note(target: ByteArray, offset: Int, length: Int, peeked: Boolean) {
        if (length >= 12 &&
            fourccAt(target, offset) == "LIST" &&
            fourccAt(target, offset + 8) == "movi"
        ) {
            // peek не сдвигает позицию: Media3 ставит moviStart на LIST.
            AviProbe.moviStart = if (peeked) delegate.position else delegate.position - length
        }
        AviHeader.tryFixHdrl(target, offset, length)?.let { facts ->
            AviProbe.facts = facts
            val video = facts.videos.firstOrNull()
            Log.i(
                TAG,
                "AVI hdrl video handler=${video?.handler} compression=${video?.compression} " +
                    "${video?.width}x${video?.height} scale=${video?.scale} rate=${video?.rate} " +
                    "length=${video?.length} sampleSize=${video?.sampleSize} start=${video?.start} " +
                    "strfBeforeStrh=${video?.strfBeforeStrh} divx3=${facts.divx3} " +
                    "avihFrames=${facts.avihFrames} durationUs=${facts.durationUs}",
            )
            return
        }
        val frames = AviHeader.countIdx1Video(target, offset, length) ?: return
        val current = AviProbe.facts ?: return
        val updated = AviHeader.withIdx1(current, frames)
        AviProbe.facts = updated
        AviProbe.audioIndex = AviHeader.parseAudioIndex(
            target,
            offset,
            length,
            AviProbe.moviStart,
            current.audioSampleRate,
            current.audioBytesPerSec,
        )
        val audioChunks = AviProbe.audioIndex?.headerOffsets?.size ?: 0
        Log.i(
            TAG,
            "AVI idx1 videoFrames=$frames durationUs=${updated.durationUs} " +
                "audioChunks=$audioChunks rate=${current.audioSampleRate} bytesPerSec=${current.audioBytesPerSec}",
        )
    }
}

private fun fourccAt(data: ByteArray, at: Int): String {
    if (at < 0 || at + 4 > data.size) return ""
    return String(data, at, 4, Charsets.ISO_8859_1)
}

private const val TAG = "Player2160"

@OptIn(UnstableApi::class)
private class UnpackingTrackOutput(
    private val delegate: TrackOutput,
    private val unpacker: Mpeg4PackedUnpacker,
) : TrackOutput {
    private val pending = ByteArrayOutputStream()

    override fun format(format: Format) {
        val adjusted = if (
            AviProbe.divx3 &&
            (format.sampleMimeType == MimeTypes.VIDEO_MP43 || format.sampleMimeType == MimeTypes.VIDEO_MP42)
        ) {
            format.buildUpon().setSampleMimeType(AviHeader.MIME_DIVX3).build()
        } else {
            format
        }
        Log.i(TAG, "AVI format mime=${adjusted.sampleMimeType} ${adjusted.width}x${adjusted.height}")
        unpacker.setMime(adjusted.sampleMimeType)
        if (!unpacker.enabled || adjusted.initializationData.isEmpty()) {
            delegate.format(adjusted)
            return
        }
        val stripped = adjusted.initializationData.map { Mpeg4PackedUnpacker.stripMarker(it) }
        delegate.format(adjusted.buildUpon().setInitializationData(stripped).build())
    }

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
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
        if (sampleDataPart == TrackOutput.SAMPLE_DATA_PART_MAIN && length > 0) {
            pending.write(data.data, data.position, length)
        }
        data.skipBytes(length)
    }

    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
        val all = pending.toByteArray()
        pending.reset()
        val end = all.size - offset
        val start = (end - size).coerceAtLeast(0)
        val sample = if (start < end && end <= all.size) all.copyOfRange(start, end) else ByteArray(0)
        if (offset > 0 && end < all.size) pending.write(all, end, all.size - end)
        if (sample.isEmpty()) return
        val emitted = unpacker.consume(sample)
        val outFlags = if (unpacker.enabled) {
            if (Mpeg4PackedUnpacker.isIVop(emitted)) flags or C.BUFFER_FLAG_KEY_FRAME
            else flags and C.BUFFER_FLAG_KEY_FRAME.inv()
        } else {
            flags
        }
        delegate.sampleData(ParsableByteArray(emitted), emitted.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
        delegate.sampleMetadata(timeUs, outFlags, emitted.size, 0, cryptoData)
    }

    override fun durationUs(durationUs: Long) = delegate.durationUs(capDuration(durationUs))
}

private fun capDuration(durationUs: Long): Long {
    val cap = AviProbe.durationUs
    return if (cap > 0 && durationUs > cap) cap else durationUs
}
