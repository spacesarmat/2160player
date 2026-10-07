package tv.p2160.core.engine

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink

/** Можно ли отдавать звук на выход без декодирования (AC3/E-AC3/DTS/TrueHD passthrough). */
class PassthroughGuard {
    /** true — всё декодируем сами (ночной режим или ТВ, не сумевший открыть passthrough). */
    @Volatile var disabled: Boolean = false

    /** Выход не смог открыть сжатый поток (AUDIO_TRACK_INIT_FAILED) — passthrough больше не включаем. */
    @Volatile var failed: Boolean = false
}

/**
 * Обёртка AudioSink: когда [PassthroughGuard.disabled], сообщает, что сжатые форматы не
 * поддерживаются, — рендерер выбирает декодер (системный или FFmpeg) и отдаёт PCM.
 */
@OptIn(UnstableApi::class)
internal class GuardedAudioSink(sink: AudioSink, private val guard: PassthroughGuard) : ForwardingAudioSink(sink) {
    private fun blocked(format: Format) = guard.disabled && format.sampleMimeType != MimeTypes.AUDIO_RAW

    override fun supportsFormat(format: Format): Boolean = !blocked(format) && super.supportsFormat(format)

    override fun getFormatSupport(format: Format): Int =
        if (blocked(format)) AudioSink.SINK_FORMAT_UNSUPPORTED else super.getFormatSupport(format)
}
