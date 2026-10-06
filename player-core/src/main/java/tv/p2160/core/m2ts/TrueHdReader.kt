package tv.p2160.core.m2ts

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Ac3Util
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.Ac3Reader
import androidx.media3.extractor.ts.ElementaryStreamReader
import androidx.media3.extractor.ts.TsPayloadReader

/**
 * Dolby TrueHD на Blu-ray (stream_type 0x83). В одном PID идут PES двух видов:
 * кадры AC-3 «ядра» (sync 0x0B77) и access unit'ы TrueHD/MLP
 * (2 байта: check nibble + длина в 16-битных словах, 2 байта timing, у части AU — major sync
 * 0xF8726FBA). Вид PES определяем по первым двум байтам: у AU TrueHD 0x0B77 невозможно
 * (это длина 5870 байт, а реальные AU < 4 КБ).
 *
 * TrueHD отдаётся как AUDIO_TRUEHD, сэмплами по [Ac3Util.TRUEHD_RECHUNK_SAMPLE_COUNT] AU — ровно
 * так же, как Matroska/MP4 (TrueHdSampleRechunker): этого ждут DefaultAudioSink в passthrough
 * (кадров на сэмпл = 16 × AU) и FFmpeg-декодер. Выдача начинается с первого AU с major sync.
 *
 * AC-3 ядро при [exposeAc3Core] выводится отдельной дорожкой (id = PID + 0x2000) через штатный
 * [Ac3Reader]; иначе отбрасывается.
 */
@OptIn(UnstableApi::class)
class TrueHdReader(
    private val language: String?,
    private val roleFlags: Int = 0,
    private val exposeAc3Core: Boolean = false,
    private val ac3CoreLabel: String? = DEFAULT_CORE_LABEL,
) : ElementaryStreamReader {

    private lateinit var output: TrackOutput
    private lateinit var formatId: String
    private var format: Format? = null
    private val coreReader: Ac3Reader? = if (exposeAc3Core) Ac3Reader(language, roleFlags, MimeTypes.VIDEO_MP2T) else null

    private var pes = ByteArray(16 * 1024)
    private var pesLength = 0
    private var pesTimeUs = C.TIME_UNSET
    private var pesFlags = 0

    /** Хвост AU, разрезанного границей PES; [carryTimeUs] — его метка времени. */
    private var carry = ByteArray(0)
    private var carryTimeUs = C.TIME_UNSET
    private var synced = false

    private var chunk = ByteArray(64 * 1024)
    private var chunkSize = 0
    private var chunkUnits = 0
    private var chunkTimeUs = C.TIME_UNSET
    private val chunkArray = ParsableByteArray()

    private var auDurationUs = 1_000_000.0 * AU_SAMPLES / 48_000

    override fun seek() {
        pesLength = 0
        carry = ByteArray(0)
        carryTimeUs = C.TIME_UNSET
        synced = false
        chunkSize = 0
        chunkUnits = 0
        chunkTimeUs = C.TIME_UNSET
        coreReader?.seek()
    }

    override fun createTracks(extractorOutput: ExtractorOutput, idGenerator: TsPayloadReader.TrackIdGenerator) {
        idGenerator.generateNewId()
        formatId = idGenerator.formatId
        output = extractorOutput.track(idGenerator.trackId, C.TRACK_TYPE_AUDIO)
        // Ac3Reader сам вызовет generateNewId() → PID + 0x2000.
        coreReader?.createTracks(LabeledExtractorOutput(extractorOutput, ac3CoreLabel), idGenerator)
    }

    override fun packetStarted(pesTimeUs: Long, flags: Int) {
        this.pesTimeUs = pesTimeUs
        pesFlags = flags
        pesLength = 0
    }

    override fun consume(data: ParsableByteArray) {
        val n = data.bytesLeft()
        if (pesLength + n > pes.size) pes = pes.copyOf(maxOf(pes.size * 2, pesLength + n))
        data.readBytes(pes, pesLength, n)
        pesLength += n
    }

    override fun packetFinished() {
        if (pesLength >= 2) {
            if ((pes[0].toInt() and 0xFF) == 0x0B && (pes[1].toInt() and 0xFF) == 0x77) {
                coreReader?.let {
                    it.packetStarted(pesTimeUs, pesFlags)
                    it.consume(ParsableByteArray(pes, pesLength))
                    it.packetFinished()
                }
            } else {
                parseTrueHd()
            }
        }
        pesLength = 0
    }

    override fun endOfInputReached() {
        flushChunk()
        coreReader?.endOfInputReached()
    }

    private fun parseTrueHd() {
        // Данные = хвост прошлого PES + текущий PES.
        val data: ByteArray
        val length: Int
        val dataStartTimeUs: Long
        if (carry.isNotEmpty()) {
            data = ByteArray(carry.size + pesLength)
            System.arraycopy(carry, 0, data, 0, carry.size)
            System.arraycopy(pes, 0, data, carry.size, pesLength)
            length = data.size
            dataStartTimeUs = carryTimeUs
        } else {
            data = pes
            length = pesLength
            dataStartTimeUs = pesTimeUs
        }
        val carryLength = carry.size
        carry = ByteArray(0)

        var pos = 0
        var units = 0
        if (!synced) {
            pos = findMajorSync(data, 0, length)
            if (pos < 0) return
            synced = true
        }
        while (pos + 4 <= length) {
            val auLength = (((data[pos].toInt() and 0x0F) shl 8) or (data[pos + 1].toInt() and 0xFF)) * 2
            if (auLength < 4 || auLength > MAX_AU_SIZE) {
                // Потеряли синхронизацию — ищем следующий major sync.
                synced = false
                pos = findMajorSync(data, pos + 1, length)
                if (pos < 0) return
                synced = true
                continue
            }
            if (pos + auLength > length) break
            // Время AU: от PES, в котором он начался (хвост — от прошлого PES).
            val timeUs = when {
                pos < carryLength -> dataStartTimeUs
                pesTimeUs == C.TIME_UNSET -> C.TIME_UNSET
                else -> pesTimeUs + ((units - (if (carryLength > 0) 1 else 0)) * auDurationUs).toLong()
            }
            onAccessUnit(data, pos, auLength, timeUs)
            units++
            pos += auLength
        }
        if (pos < length) {
            carry = data.copyOfRange(pos, length)
            carryTimeUs = when {
                pos < carryLength -> dataStartTimeUs
                pesTimeUs == C.TIME_UNSET -> C.TIME_UNSET
                else -> pesTimeUs + ((units - (if (carryLength > 0) 1 else 0)) * auDurationUs).toLong()
            }
        }
    }

    private fun onAccessUnit(data: ByteArray, offset: Int, length: Int, timeUs: Long) {
        if (hasMajorSync(data, offset, length)) maybeUpdateFormat(data, offset)
        if (format == null) return
        if (chunkUnits == 0) {
            if (timeUs == C.TIME_UNSET) return
            chunkTimeUs = timeUs
        }
        if (chunkSize + length > chunk.size) chunk = chunk.copyOf(maxOf(chunk.size * 2, chunkSize + length))
        System.arraycopy(data, offset, chunk, chunkSize, length)
        chunkSize += length
        chunkUnits++
        if (chunkUnits == Ac3Util.TRUEHD_RECHUNK_SAMPLE_COUNT) flushChunk()
    }

    private fun flushChunk() {
        if (chunkUnits > 0 && chunkTimeUs != C.TIME_UNSET) {
            chunkArray.reset(chunk, chunkSize)
            output.sampleData(chunkArray, chunkSize)
            output.sampleMetadata(chunkTimeUs, C.BUFFER_FLAG_KEY_FRAME, chunkSize, 0, null)
        }
        chunkSize = 0
        chunkUnits = 0
        chunkTimeUs = C.TIME_UNSET
    }

    private fun maybeUpdateFormat(data: ByteArray, offset: Int) {
        val rateCode = (data[offset + 8].toInt() shr 4) and 0x0F
        val baseRate = if (rateCode and 0x08 != 0) 44_100 else 48_000
        val sampleRate = baseRate shl (rateCode and 0x07)
        auDurationUs = 1_000_000.0 * AU_SAMPLES / baseRate
        val b9 = data[offset + 9].toInt() and 0xFF
        val b10 = data[offset + 10].toInt() and 0xFF
        val b11 = data[offset + 11].toInt() and 0xFF
        val arrangement6 = ((b9 and 0x0F) shl 1) or (b10 shr 7)
        val arrangement8 = ((b10 and 0x1F) shl 8) or b11
        val channels = channelCount(arrangement8).takeIf { it > 0 } ?: channelCount(arrangement6)
        val current = format
        if (current != null && current.sampleRate == sampleRate && current.channelCount == channels) return
        val newFormat = Format.Builder()
            .setId(formatId)
            .setContainerMimeType(MimeTypes.VIDEO_MP2T)
            .setSampleMimeType(MimeTypes.AUDIO_TRUEHD)
            .setChannelCount(if (channels > 0) channels else Format.NO_VALUE)
            .setSampleRate(sampleRate)
            .setLanguage(language)
            .setRoleFlags(roleFlags)
            .build()
        format = newFormat
        output.format(newFormat)
    }

    private fun hasMajorSync(data: ByteArray, offset: Int, length: Int): Boolean =
        length >= 12 &&
            data[offset + 4] == 0xF8.toByte() && data[offset + 5] == 0x72.toByte() &&
            data[offset + 6] == 0x6F.toByte() && data[offset + 7] == 0xBA.toByte()

    /** Начало AU с major sync (сигнатура на смещении 4) или -1. */
    private fun findMajorSync(data: ByteArray, from: Int, length: Int): Int {
        var i = maxOf(from, 0) + 4
        while (i + 8 <= length) {
            if (data[i] == 0xF8.toByte() && data[i + 1] == 0x72.toByte() &&
                data[i + 2] == 0x6F.toByte() && data[i + 3] == 0xBA.toByte()
            ) return i - 4
            i++
        }
        return -1
    }

    private fun channelCount(arrangement: Int): Int {
        var count = 0
        for (i in CHANNELS_PER_BIT.indices) {
            if (arrangement and (1 shl i) != 0) count += CHANNELS_PER_BIT[i]
        }
        return count
    }

    companion object {
        const val DEFAULT_CORE_LABEL = "AC-3 core"
        private const val AU_SAMPLES = 40
        private const val MAX_AU_SIZE = 8 * 1024
        /** Каналов на бит channel_arrangement TrueHD (как thd_chancount в ffmpeg). */
        private val CHANNELS_PER_BIT = intArrayOf(2, 1, 1, 2, 2, 2, 2, 1, 1, 2, 2, 1, 1)
    }
}
