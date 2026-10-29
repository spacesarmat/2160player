package tv.p2160.core.engine

import android.content.Context
import android.media.MediaCodec
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter

/**
 * После ошибки декодера MediaCodec уже в состоянии Released, а ExoPlayer при остановке
 * всё равно вызывает flush или queueInputBuffer. Исключение ловится в плеере и пишется
 * как «Disable failed.» по разу на каждый упавший декодер.
 * Повторный вызов после такой ошибки ничего не делает. Первый сбой настоящего буфера
 * по-прежнему уходит наверх, чтобы сменился декодер.
 */
@OptIn(UnstableApi::class)
internal class ReleasedCodecAdapterFactory(context: Context) : MediaCodecAdapter.Factory {
    private val delegate = MediaCodecAdapter.Factory.getDefault(context)

    override fun createAdapter(configuration: MediaCodecAdapter.Configuration): MediaCodecAdapter =
        ReleasedCodecAdapter(delegate.createAdapter(configuration))
}

@OptIn(UnstableApi::class)
internal class ReleasedCodecAdapter(private val delegate: MediaCodecAdapter) : MediaCodecAdapter by delegate {
    private var dead = false

    override fun queueInputBuffer(index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int) {
        if (dead) return
        try {
            delegate.queueInputBuffer(index, offset, size, presentationTimeUs, flags)
        } catch (e: IllegalStateException) {
            dead = true
            val endOfStream = size == 0 && flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            if (!endOfStream) throw e
        }
    }

    override fun flush() {
        if (dead) return
        try {
            delegate.flush()
        } catch (_: IllegalStateException) {
            dead = true
        }
    }

    override fun release() {
        try {
            delegate.release()
        } catch (_: IllegalStateException) {
            // Кодек уже отпущен ошибкой декодера.
        }
        dead = true
    }
}
