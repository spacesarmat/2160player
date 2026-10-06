package tv.p2160.core.m2ts

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.min

/** Источник байтов с произвольным доступом: файл (окно в нём) или массив. */
interface ByteSource : AutoCloseable {
    val length: Long
    fun read(position: Long, target: ByteArray, offset: Int, length: Int): Int
    override fun close() {}
}

class ArrayByteSource(private val data: ByteArray) : ByteSource {
    override val length: Long get() = data.size.toLong()
    override fun read(position: Long, target: ByteArray, offset: Int, length: Int): Int {
        if (position >= data.size) return -1
        val n = min(length.toLong(), data.size - position).toInt()
        System.arraycopy(data, position.toInt(), target, offset, n)
        return n
    }
}

/** Окно [base, base+length) файла; блочный кэш, чтобы не дёргать NAS мелкими чтениями. */
class FileWindowSource(file: File, private val base: Long = 0, length: Long = -1) : ByteSource {
    private val raf = RandomAccessFile(file, "r")
    override val length: Long = if (length >= 0) length else raf.length() - base
    private val block = ByteArray(BLOCK)
    private var blockStart = -1L
    private var blockLength = 0
    var physicalBytesRead = 0L
        private set

    override fun read(position: Long, target: ByteArray, offset: Int, length: Int): Int {
        if (position >= this.length) return -1
        if (position < blockStart || position >= blockStart + blockLength) {
            blockStart = position - position % BLOCK
            raf.seek(base + blockStart)
            val want = min(BLOCK.toLong(), this.length - blockStart).toInt()
            var got = 0
            while (got < want) {
                val n = raf.read(block, got, want - got)
                if (n < 0) break
                got += n
            }
            blockLength = got
            physicalBytesRead += got
        }
        val inBlock = (position - blockStart).toInt()
        val n = min(length, blockLength - inBlock)
        System.arraycopy(block, inBlock, target, offset, n)
        return n
    }

    override fun close() = raf.close()

    private companion object {
        const val BLOCK = 1 shl 20
    }
}

/** ExtractorInput поверх [ByteSource] с семантикой DefaultExtractorInput. */
class SourceExtractorInput(
    private val source: ByteSource,
    private var position: Long,
    private val reportLength: Boolean = true,
) : ExtractorInput {
    private var peekPosition = position
    val startPosition = position

    private fun readAt(at: Long, target: ByteArray, offset: Int, length: Int): Int {
        var total = 0
        while (total < length) {
            val n = source.read(at + total, target, offset + total, length - total)
            if (n <= 0) break
            total += n
        }
        return total
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val n = readAt(position, buffer, offset, min(length, 64 * 1024))
        if (n == 0) return C.RESULT_END_OF_INPUT
        advance(n)
        return n
    }

    private fun advance(n: Int) {
        position += n
        if (peekPosition < position) peekPosition = position
    }

    override fun readFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
        val n = readAt(position, target, offset, length)
        if (n < length) {
            if (n == 0 && allowEndOfInput) return false
            throw EOFException()
        }
        advance(n)
        return true
    }

    override fun readFully(target: ByteArray, offset: Int, length: Int) {
        readFully(target, offset, length, false)
    }

    override fun skip(length: Int): Int {
        val n = min(length.toLong(), source.length - position).toInt()
        if (n <= 0) return C.RESULT_END_OF_INPUT
        advance(n)
        return n
    }

    override fun skipFully(length: Int, allowEndOfInput: Boolean): Boolean {
        val left = source.length - position
        if (left < length) {
            if (left == 0L && allowEndOfInput) return false
            throw EOFException()
        }
        advance(length)
        return true
    }

    override fun skipFully(length: Int) {
        skipFully(length, false)
    }

    override fun peek(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val n = readAt(peekPosition, target, offset, length)
        if (n == 0) return C.RESULT_END_OF_INPUT
        peekPosition += n
        return n
    }

    override fun peekFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
        val n = readAt(peekPosition, target, offset, length)
        if (n < length) {
            if (n == 0 && allowEndOfInput) return false
            throw EOFException()
        }
        peekPosition += n
        return true
    }

    override fun peekFully(target: ByteArray, offset: Int, length: Int) {
        peekFully(target, offset, length, false)
    }

    override fun advancePeekPosition(length: Int, allowEndOfInput: Boolean): Boolean {
        val left = source.length - peekPosition
        if (left < length) {
            if (left == 0L && allowEndOfInput) return false
            throw EOFException()
        }
        peekPosition += length
        return true
    }

    override fun advancePeekPosition(length: Int) {
        advancePeekPosition(length, false)
    }

    override fun resetPeekPosition() {
        peekPosition = position
    }

    override fun getPeekPosition(): Long = peekPosition
    override fun getPosition(): Long = position
    override fun getLength(): Long = if (reportLength) source.length else C.LENGTH_UNSET.toLong()
    override fun <E : Throwable> setRetryPosition(position: Long, e: E) = throw e
}

class RecordedSample(val timeUs: Long, val flags: Int, val size: Int, val data: ByteArray?)

class RecordingTrack(val id: Int, val type: Int, private val captureData: Boolean) : TrackOutput {
    val formats = mutableListOf<Format>()
    val samples = mutableListOf<RecordedSample>()
    private val pending = ByteArrayOutputStream()
    private var pendingBytes = 0L
    private val scratch = ByteArray(64 * 1024)

    val format: Format? get() = formats.lastOrNull()

    override fun format(format: Format) {
        formats += format
    }

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
        val n = input.read(scratch, 0, min(length, scratch.size))
        if (n == C.RESULT_END_OF_INPUT) {
            if (allowEndOfInput) return C.RESULT_END_OF_INPUT
            throw EOFException()
        }
        if (captureData) pending.write(scratch, 0, n)
        pendingBytes += n
        return n
    }

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        if (captureData) {
            pending.write(data.data, data.position, length)
        }
        data.skipBytes(length)
        pendingBytes += length
    }

    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
        var bytes: ByteArray? = null
        if (captureData) {
            val all = pending.toByteArray()
            val end = all.size - offset
            bytes = all.copyOfRange(end - size, end)
            pending.reset()
            pending.write(all, end, offset)
        }
        pendingBytes = offset.toLong()
        samples += RecordedSample(timeUs, flags, size, bytes)
    }
}

class RecordingOutput(private val captureData: (type: Int) -> Boolean = { it != C.TRACK_TYPE_VIDEO }) : ExtractorOutput {
    val tracks = linkedMapOf<Int, RecordingTrack>()
    var seekMap: SeekMap? = null
    var tracksEnded = false

    override fun track(id: Int, type: Int): TrackOutput =
        tracks.getOrPut(id) { RecordingTrack(id, type, captureData(type)) }

    override fun endTracks() {
        tracksEnded = true
    }

    override fun seekMap(seekMap: SeekMap) {
        this.seekMap = seekMap
    }
}

/**
 * Прогоняет экстрактор как ProgressiveMediaPeriod: RESULT_SEEK → новый ввод с указанной позиции.
 * Останавливается на конце ввода, после [maxBytes] прочитанных байт или когда [stop] вернёт true.
 */
class ExtractionDriver(
    val extractor: Extractor,
    private val source: ByteSource,
    val output: RecordingOutput = RecordingOutput(),
) {
    private val holder = PositionHolder()
    var input = SourceExtractorInput(source, 0)
        private set
    var bytesConsumed = 0L
        private set
    val seekPositions = mutableListOf<Long>()

    init {
        extractor.init(output)
    }

    fun run(maxBytes: Long = Long.MAX_VALUE, stop: () -> Boolean = { false }): Int {
        while (true) {
            val before = input.position
            val result = extractor.read(input, holder)
            bytesConsumed += input.position - before
            when (result) {
                Extractor.RESULT_SEEK -> {
                    seekPositions += holder.position
                    input = SourceExtractorInput(source, holder.position)
                }
                Extractor.RESULT_END_OF_INPUT -> return result
            }
            if (bytesConsumed >= maxBytes || stop()) return result
        }
    }

    /** Seek, как его делает плеер: extractor.seek + новый ввод с позиции из SeekMap. */
    fun seekTo(position: Long, timeUs: Long) {
        extractor.seek(position, timeUs)
        input = SourceExtractorInput(source, position)
    }
}

/** Делит сэмпл TrueHD на AU по полю длины. */
fun splitTrueHdUnits(data: ByteArray): List<ByteArray> {
    val units = mutableListOf<ByteArray>()
    var pos = 0
    while (pos + 4 <= data.size) {
        val len = (((data[pos].toInt() and 0x0F) shl 8) or (data[pos + 1].toInt() and 0xFF)) * 2
        if (len < 4 || pos + len > data.size) break
        units += data.copyOfRange(pos, pos + len)
        pos += len
    }
    require(pos == data.size) { "хвост ${data.size - pos} байт не является AU" }
    return units
}

fun hasMajorSync(unit: ByteArray): Boolean =
    unit.size >= 8 && unit[4] == 0xF8.toByte() && unit[5] == 0x72.toByte() &&
        unit[6] == 0x6F.toByte() && unit[7] == 0xBA.toByte()
