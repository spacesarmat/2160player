package tv.p2160.core.m2ts

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.extractor.ts.TsPayloadReader

/**
 * Экстрактор MPEG-TS с поддержкой Blu-ray M2TS (192-байтные пакеты).
 *
 * Размер пакета определяется по первым байтам (sync 0x47 с шагом 192 или 188):
 * - 192 (M2TS/MTS/AVCHD): штатный [TsExtractor] получает «очищенный» поток через
 *   [M2tsPacketInput]; позиции в seek-карте и в RESULT_SEEK переводятся 188↔192,
 *   поэтому длительность (по PCR в начале/конце файла) и бинарный поиск по PCR работают
 *   точно и для файлов в десятки ГБ — читаются только окна по ~110 КБ;
 * - 188 (обычный TS): [TsExtractor] работает напрямую.
 *
 * Окно поиска PCR (длительность, бинарный поиск) для M2TS расширено до [M2TS_TIMESTAMP_SEARCH_BYTES]:
 * на Blu-ray PCR идёт отдельным PID с интервалом до 100 мс, при 48 Мбит/с это ~600 КБ, а штатных
 * 600 × 188 = 110 КБ не хватает — без PCR в окне файл становится «не перематываемым».
 *
 * @param payloadReaderFactory фабрика читателей ES; аргумент — true, если поток M2TS
 *   (тогда типы 0x80/0x86 трактуются по-блюрейному: LPCM и DTS-HD MA).
 */
@OptIn(UnstableApi::class)
class M2tsExtractor(
    private val payloadReaderFactory: (isM2ts: Boolean) -> TsPayloadReader.Factory,
    private val subtitleParserFactory: SubtitleParser.Factory,
    private val emitRawSubtitleData: Boolean = false,
    private val timestampSearchBytes: Int? = null,
) : Extractor {

    private var output: ExtractorOutput? = null
    private var inner: TsExtractor? = null
    private var packetInput: M2tsPacketInput? = null
    private val logicalPosition = PositionHolder()
    private var pendingSeekPosition = C.INDEX_UNSET.toLong()
    private var pendingSeekTimeUs = 0L

    /** Результат определения формата (для тестов и диагностики). */
    var packetSize: Int = 0
        private set

    override fun sniff(input: ExtractorInput): Boolean = detect(input) != null

    override fun init(output: ExtractorOutput) {
        this.output = output
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        val extractor = inner ?: createInner(input) ?: return Extractor.RESULT_END_OF_INPUT
        val packets = packetInput ?: return extractor.read(input, seekPosition)
        packets.bind(input)
        val result = extractor.read(packets, logicalPosition)
        if (result == Extractor.RESULT_SEEK) {
            seekPosition.position = packets.toPhysical(logicalPosition.position)
            packets.invalidate()
        }
        return result
    }

    override fun seek(position: Long, timeUs: Long) {
        val extractor = inner
        if (extractor == null) {
            pendingSeekPosition = position
            pendingSeekTimeUs = timeUs
            return
        }
        val packets = packetInput
        if (packets != null) {
            packets.invalidate()
            extractor.seek(packets.toLogical(position), timeUs)
        } else {
            extractor.seek(position, timeUs)
        }
    }

    override fun release() {
        inner?.release()
    }

    override fun getUnderlyingImplementation(): Extractor = this

    private fun createInner(input: ExtractorInput): TsExtractor? {
        val detected = detect(input)
        // Не распознали — отдаём как обычный TS: TsExtractor сам найдёт sync-байты.
        val (size, phase) = detected ?: Detection(TsExtractor.TS_PACKET_SIZE, 0)
        packetSize = size
        val isM2ts = size == M2tsPacketInput.M2TS_PACKET_SIZE
        val extractor = TsExtractor(
            TsExtractor.MODE_SINGLE_PMT,
            if (emitRawSubtitleData) TsExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA else 0,
            subtitleParserFactory,
            TimestampAdjuster(0),
            payloadReaderFactory(isM2ts),
            timestampSearchBytes
                ?: if (isM2ts) M2TS_TIMESTAMP_SEARCH_BYTES else TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES,
        )
        val out = checkNotNull(output) { "init() не вызван" }
        if (isM2ts) {
            val packets = M2tsPacketInput(phase)
            packetInput = packets
            extractor.init(M2tsOutput(out, packets))
        } else {
            extractor.init(out)
        }
        inner = extractor
        if (pendingSeekPosition != C.INDEX_UNSET.toLong()) {
            val p = pendingSeekPosition
            pendingSeekPosition = C.INDEX_UNSET.toLong()
            seek(p, pendingSeekTimeUs)
        }
        return extractor
    }

    private data class Detection(val packetSize: Int, val phase: Int)

    /** Ищет 5 подряд sync-байтов с шагом 192 (M2TS), затем 188 (TS). phase — абсолютная, mod 192. */
    private fun detect(input: ExtractorInput): Detection? {
        val probe = ByteArray(M2tsPacketInput.M2TS_PACKET_SIZE * (SNIFF_PACKETS + 1))
        input.resetPeekPosition()
        var length = 0
        while (length < probe.size) {
            val n = input.peek(probe, length, probe.size - length)
            if (n == C.RESULT_END_OF_INPUT) break
            length += n
        }
        input.resetPeekPosition()
        val base = input.position
        for (stride in intArrayOf(M2tsPacketInput.M2TS_PACKET_SIZE, TsExtractor.TS_PACKET_SIZE)) {
            for (start in 0 until stride) {
                if (start + stride * (SNIFF_PACKETS - 1) >= length) break
                if ((0 until SNIFF_PACKETS).all { probe[start + it * stride] == SYNC }) {
                    val phase = ((base + start) % M2tsPacketInput.M2TS_PACKET_SIZE).toInt()
                    return Detection(stride, phase)
                }
            }
        }
        return null
    }

    /** Переводит позиции seek-карты TsExtractor из логических (188) в физические (192). */
    private class M2tsOutput(
        private val delegate: ExtractorOutput,
        private val packets: M2tsPacketInput,
    ) : ExtractorOutput {
        override fun track(id: Int, type: Int): TrackOutput = delegate.track(id, type)
        override fun endTracks() = delegate.endTracks()
        override fun seekMap(seekMap: SeekMap) = delegate.seekMap(M2tsSeekMap(seekMap, packets))
    }

    private class M2tsSeekMap(
        private val delegate: SeekMap,
        private val packets: M2tsPacketInput,
    ) : SeekMap {
        override fun isSeekable(): Boolean = delegate.isSeekable
        override fun getDurationUs(): Long = delegate.durationUs
        override fun isEstimated(): Boolean = delegate.isEstimated
        override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
            val points = delegate.getSeekPoints(timeUs)
            val first = map(points.first)
            return if (points.first == points.second) {
                SeekMap.SeekPoints(first)
            } else {
                SeekMap.SeekPoints(first, map(points.second))
            }
        }

        private fun map(point: SeekPoint) = SeekPoint(point.timeUs, packets.toPhysical(point.position))
    }

    companion object {
        /** 4000 TS-пакетов ≈ 750 КБ ≈ 125 мс при максимальном для BD 48 Мбит/с. */
        const val M2TS_TIMESTAMP_SEARCH_BYTES = 4000 * TsExtractor.TS_PACKET_SIZE

        private const val SNIFF_PACKETS = 5
        private const val SYNC: Byte = 0x47
    }
}
