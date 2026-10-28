package tv.p2160.core.avi

import android.net.Uri
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
        override fun createExtractors(): Array<Extractor> =
            delegate.createExtractors().map(::wrap).toTypedArray()

        override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
            delegate.createExtractors(uri, responseHeaders).map(::wrap).toTypedArray()
    }

    fun wrap(extractor: Extractor): Extractor =
        if (extractor is AviExtractor) AviFixExtractor(extractor) else extractor
}

@OptIn(UnstableApi::class)
private class AviFixExtractor(private val delegate: Extractor) : Extractor by delegate {
    private val unpackers = ArrayList<Mpeg4PackedUnpacker>()

    override fun init(output: ExtractorOutput) {
        unpackers.clear()
        delegate.init(object : ExtractorOutput by output {
            override fun track(id: Int, type: Int): TrackOutput {
                val track = output.track(id, type)
                if (type != C.TRACK_TYPE_VIDEO) return track
                val unpacker = Mpeg4PackedUnpacker()
                unpackers += unpacker
                return UnpackingTrackOutput(track, unpacker)
            }
        })
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int =
        delegate.read(AviFourccInput(input), seekPosition)

    override fun seek(position: Long, timeUs: Long) {
        unpackers.forEach { it.reset() }
        delegate.seek(position, timeUs)
    }
}

/** В заголовке AVI (до movi) DIV3/div3/DIV4/div4 читаются как MP43. В кадрах байты не трогаем. */
internal object AviFourcc {
    const val HEADER_LIMIT = 2 * 1024 * 1024
    private val rules = listOf(
        "DIV3".encodeToByteArray() to "MP43".encodeToByteArray(),
        "div3".encodeToByteArray() to "mp43".encodeToByteArray(),
        "DIV4".encodeToByteArray() to "MP43".encodeToByteArray(),
        "div4".encodeToByteArray() to "mp43".encodeToByteArray(),
    )

    fun rewrite(buffer: ByteArray, offset: Int, length: Int, filePosition: Long) {
        if (length < 4 || filePosition >= HEADER_LIMIT) return
        val window = minOf(length, (HEADER_LIMIT - filePosition).toInt())
        val start = offset
        val end = offset + window
        for ((from, to) in rules) {
            var i = start
            while (i + 4 <= end) {
                if (buffer[i] == from[0] && buffer[i + 1] == from[1] && buffer[i + 2] == from[2] && buffer[i + 3] == from[3]) {
                    buffer[i] = to[0]
                    buffer[i + 1] = to[1]
                    buffer[i + 2] = to[2]
                    buffer[i + 3] = to[3]
                    i += 4
                } else {
                    i++
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
private class AviFourccInput(private val delegate: ExtractorInput) : ExtractorInput by delegate {
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val at = delegate.position
        val n = delegate.read(buffer, offset, length)
        if (n > 0) AviFourcc.rewrite(buffer, offset, n, at)
        return n
    }

    override fun readFully(target: ByteArray, offset: Int, length: Int) {
        val at = delegate.position
        delegate.readFully(target, offset, length)
        AviFourcc.rewrite(target, offset, length, at)
    }

    override fun readFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
        val at = delegate.position
        val ok = delegate.readFully(target, offset, length, allowEndOfInput)
        if (ok) AviFourcc.rewrite(target, offset, length, at)
        return ok
    }

    override fun peek(buffer: ByteArray, offset: Int, length: Int): Int {
        val at = delegate.peekPosition
        val n = delegate.peek(buffer, offset, length)
        if (n > 0) AviFourcc.rewrite(buffer, offset, n, at)
        return n
    }

    override fun peekFully(target: ByteArray, offset: Int, length: Int) {
        val at = delegate.peekPosition
        delegate.peekFully(target, offset, length)
        AviFourcc.rewrite(target, offset, length, at)
    }

    override fun peekFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
        val at = delegate.peekPosition
        val ok = delegate.peekFully(target, offset, length, allowEndOfInput)
        if (ok) AviFourcc.rewrite(target, offset, length, at)
        return ok
    }
}

@OptIn(UnstableApi::class)
private class UnpackingTrackOutput(
    private val delegate: TrackOutput,
    private val unpacker: Mpeg4PackedUnpacker,
) : TrackOutput {
    private val pending = ByteArrayOutputStream()

    override fun format(format: Format) {
        unpacker.setMime(format.sampleMimeType)
        if (!unpacker.enabled || format.initializationData.isEmpty()) {
            delegate.format(format)
            return
        }
        val stripped = format.initializationData.map { Mpeg4PackedUnpacker.stripMarker(it) }
        delegate.format(format.buildUpon().setInitializationData(stripped).build())
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

    override fun durationUs(durationUs: Long) = delegate.durationUs(durationUs)
}
