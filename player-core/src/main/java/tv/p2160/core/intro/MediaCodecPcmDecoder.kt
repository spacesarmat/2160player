package tv.p2160.core.intro

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import tv.p2160.core.source.SeekableFiles
import java.io.File
import java.nio.ByteOrder
import java.util.Locale

/**
 * Декодирование звуковой дорожки платформенным MediaCodec в моно PCM низкой частоты.
 *
 * Источники: smb:// (прокси-дескриптор [SeekableFiles], Android 8+), content://, file://, http(s) с заголовками.
 * Выбирается первая дорожка, для которой есть декодер (AAC/AC-3/E-AC-3/Opus… предпочтительнее DTS/TrueHD,
 * для которых декодеров в системе обычно нет). Нет подходящей — [open] вернёт null.
 *
 * Важно про сеть: MediaExtractor читает контейнер подряд, поэтому для отрезка в N минут по сети
 * приходится тянуть весь перемежающийся поток (видео тоже) за эти N минут, если контейнер не позволяет
 * пропускать чужие блоки. Для 4K-ремукса (~60 Мбит/с) 10 минут — это до ~4.5 ГБ, поэтому окна держим короткими.
 */
class MediaCodecPcmDecoder private constructor(
    private val extractor: MediaExtractor,
    private val pfd: ParcelFileDescriptor?,
    private val format: MediaFormat,
    private val codecName: String,
) : PcmSource {

    override val durationMs: Long =
        if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000 else -1L

    override fun read(startMs: Long, lengthMs: Long, outRate: Int, sink: PcmSink, cancelled: () -> Boolean): Long {
        val codec = MediaCodec.createByCodecName(codecName)
        var produced = 0L
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            val startUs = startMs * 1000
            val endUs = (startMs + lengthMs) * 1000
            val wanted = lengthMs * outRate / 1000
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            var channels = format.intOr(MediaFormat.KEY_CHANNEL_COUNT, 2)
            var rate = format.intOr(MediaFormat.KEY_SAMPLE_RATE, 48_000)
            var floatPcm = false
            var resampler: Resampler? = null
            var mono = FloatArray(4096)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var idle = 0
            val limited = PcmSink { s, c ->
                val n = minOf(c.toLong(), wanted - produced).toInt()
                if (n > 0) {
                    sink.accept(s, n)
                    produced += n
                }
            }

            while (produced < wanted && !cancelled()) {
                if (!inputDone) {
                    val idx = codec.dequeueInputBuffer(5_000)
                    if (idx >= 0) {
                        val buf = codec.getInputBuffer(idx)!!
                        val size = extractor.readSampleData(buf, 0)
                        val t = extractor.sampleTime
                        if (size < 0 || t > endUs + 500_000) {
                            codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(idx, 0, size, t, 0)
                            extractor.advance()
                        }
                    }
                }
                val out = codec.dequeueOutputBuffer(info, 5_000)
                // Декодер замолчал после конца входа (бывает у некоторых реализаций) — выходим.
                if (out == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (inputDone && ++idle > 400) break
                } else {
                    idle = 0
                }
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    channels = f.intOr(MediaFormat.KEY_CHANNEL_COUNT, channels)
                    rate = f.intOr(MediaFormat.KEY_SAMPLE_RATE, rate)
                    floatPcm = f.intOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT) == AudioFormat.ENCODING_PCM_FLOAT
                    resampler = null
                } else if (out >= 0) {
                    if (info.size > 0 && channels > 0) {
                        val bb = codec.getOutputBuffer(out)!!.order(ByteOrder.nativeOrder())
                        bb.position(info.offset)
                        bb.limit(info.offset + info.size)
                        val bytesPerSample = if (floatPcm) 4 else 2
                        val frames = info.size / (bytesPerSample * channels)
                        // Отрезаем начало до startMs (поиск идёт к предыдущему опорному кадру).
                        val skip = if (info.presentationTimeUs < startUs) {
                            ((startUs - info.presentationTimeUs) * rate / 1_000_000).toInt().coerceIn(0, frames)
                        } else 0
                        val n = frames - skip
                        if (n > 0) {
                            if (mono.size < n) mono = FloatArray(n)
                            if (floatPcm) {
                                val fb = bb.asFloatBuffer()
                                for (i in 0 until n) {
                                    var acc = 0f
                                    val base = (skip + i) * channels
                                    for (c in 0 until channels) acc += fb.get(base + c)
                                    mono[i] = acc / channels
                                }
                            } else {
                                val sb = bb.asShortBuffer()
                                for (i in 0 until n) {
                                    var acc = 0
                                    val base = (skip + i) * channels
                                    for (c in 0 until channels) acc += sb.get(base + c)
                                    mono[i] = acc / (32768f * channels)
                                }
                            }
                            val r = resampler ?: Resampler(rate, outRate).also { resampler = it }
                            r.process(mono, n, limited)
                        }
                    }
                    codec.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "decode failed", e)
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
        return produced
    }

    override fun close() {
        runCatching { extractor.release() }
        runCatching { pfd?.close() }
    }

    companion object {
        private const val TAG = "p2160.intro"

        /** null — источник не открылся или нет декодируемой звуковой дорожки. */
        fun open(
            context: Context,
            uri: Uri,
            headers: Map<String, String> = emptyMap(),
            preferredLanguage: String? = null,
        ): MediaCodecPcmDecoder? {
            val extractor = MediaExtractor()
            var pfd: ParcelFileDescriptor? = null
            try {
                when (uri.scheme?.lowercase()) {
                    "http", "https" -> extractor.setDataSource(uri.toString(), headers)
                    "smb" -> {
                        pfd = SeekableFiles.open(context, uri) ?: return fail(extractor, null)
                        extractor.setDataSource(pfd.fileDescriptor)
                    }
                    "content" -> {
                        pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return fail(extractor, null)
                        extractor.setDataSource(pfd.fileDescriptor)
                    }
                    "file", null -> {
                        pfd = ParcelFileDescriptor.open(File(uri.path ?: return fail(extractor, null)), ParcelFileDescriptor.MODE_READ_ONLY)
                        extractor.setDataSource(pfd.fileDescriptor)
                    }
                    else -> extractor.setDataSource(context, uri, headers)
                }
                val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS)
                var best: Triple<Int, MediaFormat, String>? = null
                var bestScore = Int.MIN_VALUE
                for (i in 0 until extractor.trackCount) {
                    val f = extractor.getTrackFormat(i)
                    val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                    if (!mime.startsWith("audio/")) continue
                    val name = runCatching { codecs.findDecoderForFormat(f) }.getOrNull() ?: continue
                    val score = scoreTrack(mime, f.intOr(MediaFormat.KEY_CHANNEL_COUNT, 2), f.stringOr(MediaFormat.KEY_LANGUAGE), preferredLanguage, i)
                    if (score > bestScore) {
                        bestScore = score
                        best = Triple(i, f, name)
                    }
                }
                val (track, format, name) = best ?: return fail(extractor, pfd)
                extractor.selectTrack(track)
                return MediaCodecPcmDecoder(extractor, pfd, format, name)
            } catch (e: Exception) {
                Log.w(TAG, "open failed: $uri", e)
                return fail(extractor, pfd)
            }
        }

        /** Чем больше, тем лучше: язык плеера, «лёгкий» кодек, стерео, порядок в файле. */
        internal fun scoreTrack(mime: String, channels: Int, language: String?, preferred: String?, index: Int): Int {
            var s = 0
            if (sameLanguage(language, preferred)) s += 1000
            s += when {
                mime == MediaFormat.MIMETYPE_AUDIO_AAC -> 300
                mime == MediaFormat.MIMETYPE_AUDIO_AC3 || mime == MediaFormat.MIMETYPE_AUDIO_EAC3 -> 280
                mime == MediaFormat.MIMETYPE_AUDIO_OPUS || mime == MediaFormat.MIMETYPE_AUDIO_VORBIS -> 260
                mime == MediaFormat.MIMETYPE_AUDIO_MPEG || mime == MediaFormat.MIMETYPE_AUDIO_FLAC -> 240
                mime.contains("dts") || mime.contains("truehd") || mime.contains("true-hd") -> 0
                else -> 100
            }
            if (channels <= 2) s += 50
            return s - index
        }

        /** «ru» и «rus» — один язык. */
        internal fun sameLanguage(a: String?, b: String?): Boolean {
            if (a.isNullOrBlank() || b.isNullOrBlank()) return false
            if (a.equals(b, ignoreCase = true)) return true
            fun iso3(x: String) = runCatching { Locale(x.substringBefore('-')).isO3Language }.getOrDefault(x).lowercase()
            return iso3(a) == iso3(b)
        }

        private fun fail(extractor: MediaExtractor, pfd: ParcelFileDescriptor?): MediaCodecPcmDecoder? {
            runCatching { extractor.release() }
            runCatching { pfd?.close() }
            return null
        }

        private fun MediaFormat.intOr(key: String, def: Int): Int =
            if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(def) else def

        private fun MediaFormat.stringOr(key: String): String? =
            if (containsKey(key)) runCatching { getString(key) }.getOrNull() else null
    }
}
