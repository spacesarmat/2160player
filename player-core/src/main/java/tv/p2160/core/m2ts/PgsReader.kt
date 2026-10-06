package tv.p2160.core.m2ts

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.ElementaryStreamReader
import androidx.media3.extractor.ts.TsPayloadReader

/**
 * PGS-субтитры Blu-ray (stream_type 0x90). Поток сегментов: тип (1 байт) + длина (2 байта) + данные;
 * 0x16 PCS начинает display set, далее 0x17 WDS / 0x14 PDS / 0x15 ODS, 0x80 END его закрывает.
 *
 * Один display set (PCS…END включительно) = один сэмпл APPLICATION_PGS с временем PTS того PES,
 * в котором начался PCS. Это ровно тот формат, который разбирает PgsParser (как для PGS в MKV):
 * при штатной настройке TsExtractor транскодирует такие сэмплы в cues прямо при извлечении.
 * Сегменты до первого PCS (после seek) пропускаются.
 */
@OptIn(UnstableApi::class)
class PgsReader(
    private val language: String?,
    private val roleFlags: Int = 0,
) : ElementaryStreamReader {

    private lateinit var output: TrackOutput

    private var pesTimeUs = C.TIME_UNSET

    private val header = ByteArray(3)
    private var headerBytes = 0
    private var segmentType = 0
    private var segmentRemaining = 0
    private var segmentTimeUs = C.TIME_UNSET

    private var displaySet = ByteArray(64 * 1024)
    private var displaySetSize = 0
    private var displaySetTimeUs = C.TIME_UNSET
    private var inDisplaySet = false
    private val sample = ParsableByteArray()

    override fun seek() {
        headerBytes = 0
        segmentRemaining = 0
        inDisplaySet = false
        displaySetSize = 0
        pesTimeUs = C.TIME_UNSET
    }

    override fun createTracks(extractorOutput: ExtractorOutput, idGenerator: TsPayloadReader.TrackIdGenerator) {
        idGenerator.generateNewId()
        output = extractorOutput.track(idGenerator.trackId, C.TRACK_TYPE_TEXT)
        output.format(
            Format.Builder()
                .setId(idGenerator.formatId)
                .setContainerMimeType(MimeTypes.VIDEO_MP2T)
                .setSampleMimeType(MimeTypes.APPLICATION_PGS)
                .setLanguage(language)
                .setRoleFlags(roleFlags)
                .build()
        )
    }

    override fun packetStarted(pesTimeUs: Long, flags: Int) {
        this.pesTimeUs = pesTimeUs
    }

    override fun consume(data: ParsableByteArray) {
        while (data.bytesLeft() > 0) {
            if (segmentRemaining == 0) {
                if (headerBytes == 0) segmentTimeUs = pesTimeUs
                header[headerBytes++] = data.readUnsignedByte().toByte()
                if (headerBytes < 3) continue
                headerBytes = 0
                segmentType = header[0].toInt() and 0xFF
                segmentRemaining = ((header[1].toInt() and 0xFF) shl 8) or (header[2].toInt() and 0xFF)
                if (segmentType == SEGMENT_PCS) {
                    inDisplaySet = true
                    displaySetSize = 0
                    displaySetTimeUs = segmentTimeUs
                }
                append(header, 0, 3)
                if (segmentRemaining == 0) onSegmentEnd()
            } else {
                val n = minOf(segmentRemaining, data.bytesLeft())
                if (inDisplaySet) ensureCapacity(n)
                if (inDisplaySet) {
                    data.readBytes(displaySet, displaySetSize, n)
                    displaySetSize += n
                } else {
                    data.skipBytes(n)
                }
                segmentRemaining -= n
                if (segmentRemaining == 0) onSegmentEnd()
            }
        }
    }

    private fun onSegmentEnd() {
        if (segmentType != SEGMENT_END || !inDisplaySet) return
        inDisplaySet = false
        if (displaySetTimeUs == C.TIME_UNSET) return
        sample.reset(displaySet, displaySetSize)
        output.sampleData(sample, displaySetSize)
        output.sampleMetadata(displaySetTimeUs, C.BUFFER_FLAG_KEY_FRAME, displaySetSize, 0, null)
        displaySetSize = 0
    }

    private fun append(bytes: ByteArray, offset: Int, length: Int) {
        if (inDisplaySet) ensureCapacity(length)
        if (!inDisplaySet) return
        System.arraycopy(bytes, offset, displaySet, displaySetSize, length)
        displaySetSize += length
    }

    private fun ensureCapacity(extra: Int) {
        if (displaySetSize + extra > MAX_DISPLAY_SET_SIZE) {
            // Мусор вместо PGS — сбрасываемся до следующего PCS.
            inDisplaySet = false
            displaySetSize = 0
            return
        }
        if (displaySetSize + extra > displaySet.size) {
            displaySet = displaySet.copyOf(maxOf(displaySet.size * 2, displaySetSize + extra))
        }
    }

    private companion object {
        const val SEGMENT_PCS = 0x16
        const val SEGMENT_END = 0x80
        const val MAX_DISPLAY_SET_SIZE = 8 * 1024 * 1024
    }
}
