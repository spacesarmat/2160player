package tv.p2160.core.m2ts

import androidx.annotation.OptIn
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.TsPayloadReader

/** TrackOutput, который правит каждый Format перед передачей дальше. */
@OptIn(UnstableApi::class)
internal class FormatTransformingTrackOutput(
    private val delegate: TrackOutput,
    private val transform: (Format) -> Format,
) : TrackOutput {
    override fun format(format: Format) = delegate.format(transform(format))

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int =
        delegate.sampleData(input, length, allowEndOfInput, sampleDataPart)

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) =
        delegate.sampleData(data, length, sampleDataPart)

    override fun sampleMetadata(
        timeUs: Long,
        flags: Int,
        size: Int,
        offset: Int,
        cryptoData: TrackOutput.CryptoData?,
    ) = delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)

    override fun durationUs(durationUs: Long) = delegate.durationUs(durationUs)
}

/** ExtractorOutput, оборачивающий каждую дорожку в [FormatTransformingTrackOutput]. */
@OptIn(UnstableApi::class)
internal open class TransformingExtractorOutput(
    private val delegate: ExtractorOutput,
    private val transform: (trackId: Int, Format) -> Format,
) : ExtractorOutput {
    override fun track(id: Int, type: Int): TrackOutput =
        FormatTransformingTrackOutput(delegate.track(id, type)) { transform(id, it) }

    override fun endTracks() = delegate.endTracks()
    override fun seekMap(seekMap: SeekMap) = delegate.seekMap(seekMap)
}

/** Ставит подпись дорожке AC-3 ядра TrueHD (если своей подписи у формата нет). */
@OptIn(UnstableApi::class)
internal class LabeledExtractorOutput(delegate: ExtractorOutput, label: String?) :
    TransformingExtractorOutput(delegate, { _, format ->
        if (label == null || format.label != null) format else format.buildUpon().setLabel(label).build()
    })

/**
 * Обёртка над любым TsPayloadReader: язык и подпись дорожек по PID из внешнего источника
 * (STN-таблица MPLS — в PMT Blu-ray ISO-639 обычно нет). PID = trackId & 0x1FFF: TsExtractor
 * в режиме SINGLE/MULTI_PMT выдаёт trackId = PID, вторичные дорожки того же PID — PID + 0x2000·n.
 */
@OptIn(UnstableApi::class)
internal class PidLabelingPayloadReader(
    private val delegate: TsPayloadReader,
    private val languageForPid: ((Int) -> String?)?,
    private val labelForPid: ((Int) -> String?)?,
) : TsPayloadReader by delegate {

    override fun init(
        timestampAdjuster: TimestampAdjuster,
        extractorOutput: ExtractorOutput,
        idGenerator: TsPayloadReader.TrackIdGenerator,
    ) {
        delegate.init(timestampAdjuster, TransformingExtractorOutput(extractorOutput, ::apply), idGenerator)
    }

    private fun apply(trackId: Int, format: Format): Format {
        val pid = trackId and PID_MASK
        val language = languageForPid?.invoke(pid)
        val label = labelForPid?.invoke(pid)
        if (language == null && label == null) return format
        val builder = format.buildUpon()
        if (language != null) builder.setLanguage(language)
        if (label != null) {
            // Вторичная дорожка (напр. AC-3 ядро) сохраняет своё уточнение.
            val own = format.label
            builder.setLabel(if (own != null && trackId > PID_MASK) "$label — $own" else label)
        }
        return builder.build()
    }

    private companion object {
        const val PID_MASK = 0x1FFF
    }
}
