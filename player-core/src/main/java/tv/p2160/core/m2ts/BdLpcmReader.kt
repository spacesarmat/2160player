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
 * Blu-ray LPCM (stream_type 0x80). Каждый PES = 4 байта заголовка + PCM big-endian:
 * - байты 0–1: размер аудиоданных;
 * - байт 2: channel_assignment (4 бита) | sampling_frequency (4 бита: 1=48k, 4=96k, 5=192k);
 * - байт 3: bits_per_sample (2 бита: 1=16, 2=20, 3=24) | прочее.
 *
 * Нечётное число каналов в потоке дополнено до чётного «пустым» каналом. На выходе —
 * AUDIO_RAW little-endian (ENCODING_PCM_16BIT / ENCODING_PCM_24BIT, 20 бит идут как 24) без
 * канала-заполнителя: это самый «штатный» путь для MediaCodecAudioRenderer/DefaultAudioSink.
 * Порядок каналов BD (L R C LFE Ls Rs [Lb Rb]) совпадает с порядком Android для 5.1/7.1.
 */
@OptIn(UnstableApi::class)
class BdLpcmReader(
    private val language: String?,
    private val roleFlags: Int = 0,
) : ElementaryStreamReader {

    private lateinit var output: TrackOutput
    private lateinit var formatId: String
    private var format: Format? = null

    private var pes = ByteArray(8 * 1024)
    private var pesLength = 0
    private var pesTimeUs = C.TIME_UNSET
    private var nextTimeUs = C.TIME_UNSET
    private val sample = ParsableByteArray()

    override fun seek() {
        pesLength = 0
        pesTimeUs = C.TIME_UNSET
        nextTimeUs = C.TIME_UNSET
    }

    override fun createTracks(extractorOutput: ExtractorOutput, idGenerator: TsPayloadReader.TrackIdGenerator) {
        idGenerator.generateNewId()
        formatId = idGenerator.formatId
        output = extractorOutput.track(idGenerator.trackId, C.TRACK_TYPE_AUDIO)
    }

    override fun packetStarted(pesTimeUs: Long, flags: Int) {
        this.pesTimeUs = pesTimeUs
        pesLength = 0
    }

    override fun consume(data: ParsableByteArray) {
        val n = data.bytesLeft()
        if (pesLength + n > pes.size) pes = pes.copyOf(maxOf(pes.size * 2, pesLength + n))
        data.readBytes(pes, pesLength, n)
        pesLength += n
    }

    override fun packetFinished() {
        if (pesLength > HEADER_SIZE) outputSample()
        pesLength = 0
    }

    override fun endOfInputReached() {
        packetFinished()
    }

    private fun outputSample() {
        val channelAssignment = (pes[2].toInt() shr 4) and 0x0F
        val sampleRate = when (pes[2].toInt() and 0x0F) {
            1 -> 48_000
            4 -> 96_000
            5 -> 192_000
            else -> return
        }
        val bits = when ((pes[3].toInt() shr 6) and 0x03) {
            1 -> 16
            2, 3 -> 24 // 20 бит лежат в 24-битных словах
            else -> return
        }
        val channels = CHANNELS[channelAssignment]
        if (channels == 0) return
        val streamChannels = channels + (channels and 1)
        val bytesPerSample = bits / 8
        val inFrameSize = streamChannels * bytesPerSample
        val payloadSize = ((pes[0].toInt() and 0xFF) shl 8) or (pes[1].toInt() and 0xFF)
        val available = minOf(payloadSize, pesLength - HEADER_SIZE)
        val frames = available / inFrameSize
        if (frames == 0) return

        val encoding = if (bits == 16) C.ENCODING_PCM_16BIT else C.ENCODING_PCM_24BIT
        val current = format
        if (current == null || current.channelCount != channels || current.sampleRate != sampleRate ||
            current.pcmEncoding != encoding
        ) {
            val newFormat = Format.Builder()
                .setId(formatId)
                .setContainerMimeType(MimeTypes.VIDEO_MP2T)
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setChannelCount(channels)
                .setSampleRate(sampleRate)
                .setPcmEncoding(encoding)
                .setLanguage(language)
                .setRoleFlags(roleFlags)
                .build()
            format = newFormat
            output.format(newFormat)
        }

        val outSize = frames * channels * bytesPerSample
        sample.reset(outSize)
        val out = sample.data
        var src = HEADER_SIZE
        var dst = 0
        for (f in 0 until frames) {
            for (c in 0 until channels) {
                // big-endian → little-endian
                if (bytesPerSample == 2) {
                    out[dst] = pes[src + 1]
                    out[dst + 1] = pes[src]
                } else {
                    out[dst] = pes[src + 2]
                    out[dst + 1] = pes[src + 1]
                    out[dst + 2] = pes[src]
                }
                src += bytesPerSample
                dst += bytesPerSample
            }
            src += (streamChannels - channels) * bytesPerSample // канал-заполнитель
        }

        val timeUs = if (pesTimeUs != C.TIME_UNSET) pesTimeUs else nextTimeUs
        if (timeUs == C.TIME_UNSET) return
        output.sampleData(sample, outSize)
        output.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, outSize, 0, null)
        nextTimeUs = timeUs + frames * C.MICROS_PER_SECOND / sampleRate
    }

    private companion object {
        const val HEADER_SIZE = 4
        /** Число каналов по channel_assignment (как в ffmpeg pcm-bluray). */
        val CHANNELS = intArrayOf(0, 1, 0, 2, 3, 3, 4, 4, 5, 6, 7, 8, 0, 0, 0, 0)
    }
}
