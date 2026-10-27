package tv.p2160.app.camera

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import com.pedro.encoder.BaseEncoder
import com.pedro.encoder.video.VideoEncoder
import com.pedro.library.base.StreamBase

/**
 * Задержка трансляции камеры/экрана.
 *
 * Всегда (и без настройки): у NDI не крутится H.264, кадр копируется один раз в native, у видеокодека
 * низкая задержка (приоритет realtime, без B-кадров, GOP по-прежнему 1 с).
 * Настройка «Минимальная задержка» укорачивает очереди и SRT. По умолчанию она выключена: на слабой сети
 * короткая очередь чаще теряет кадры.
 *
 * Сквозную задержку «стекло–стекло» здесь не измерить (нет телефона). Прежние замеры: NDI до ПК ~0,08–0,1 с,
 * RTSP 1080p30 ~0,45 с. На устройстве `adb logcat -s Ndi:I` пишет `pipeline Nus` - от метки кадра до возврата
 * из отправки NDI (сжатие SpeedHQ на телефоне, без сети и приёмника). Если часы кадра и `nanoTime` не совпали,
 * строка не пишется.
 */
internal object StreamLatency {
    const val SEND_QUEUE_DEFAULT = 120
    const val SEND_QUEUE_LOW = 30
    /** SRT latency, миллисекунды. У библиотеки по умолчанию 120. В URL OBS это микросекунды. */
    const val SRT_LATENCY_DEFAULT_MS = 120
    const val SRT_LATENCY_LOW_MS = 80
    /** SO_SNDBUF принятого RTSP-сокета в режиме минимальной задержки. null - не трогать буфер ОС. */
    const val RTSP_SNDBUF_LOW = 64 * 1024
    const val NDI_IMAGES_DEFAULT = 3
    const val NDI_IMAGES_LOW = 2
    /** [NDIlib_send_timecode_synthesize]: библиотека сама ставит метку. Совпадает с `Long.MAX_VALUE`. */
    const val SYNTHESIZE = Long.MAX_VALUE

    fun sendQueue(lowLatency: Boolean): Int = if (lowLatency) SEND_QUEUE_LOW else SEND_QUEUE_DEFAULT

    fun srtLatencyMs(lowLatency: Boolean): Int = if (lowLatency) SRT_LATENCY_LOW_MS else SRT_LATENCY_DEFAULT_MS

    fun rtspSendBuffer(lowLatency: Boolean): Int? = if (lowLatency) RTSP_SNDBUF_LOW else null

    fun ndiImages(lowLatency: Boolean): Int = if (lowLatency) NDI_IMAGES_LOW else NDI_IMAGES_DEFAULT

    /**
     * Очередь забита хотя бы наполовину - в режиме минимальной задержки её лучше выбросить, чем копить.
     * Обычный режим кадры не выбрасывает пачкой: битрейт и так снижается, когда очередь заполнена на треть.
     */
    fun shouldDropStale(lowLatency: Boolean, items: Int, size: Int): Boolean =
        lowLatency && size > 0 && items * 2 >= size

    /** Метка кадра, наносекунды → таймкод NDI (единицы по 100 нс). Нет метки - синтез. */
    fun videoTimecode100ns(timestampNs: Long): Long =
        if (timestampNs > 0) timestampNs / 100 else SYNTHESIZE

    /**
     * Таймкод звука от общей точки отсчёта (первый кадр видео) и числа уже отданных сэмплов.
     * Пока кадра не было - синтез, чтобы не мешать разные часы.
     */
    fun audioTimecode100ns(origin100ns: Long, samplesBefore: Long, sampleRate: Int): Long {
        if (origin100ns == Long.MIN_VALUE || sampleRate <= 0) return SYNTHESIZE
        return origin100ns + samplesBefore * 10_000_000L / sampleRate
    }

    /**
     * Наносекунды между меткой кадра и моментом после отправки. null - часы явно разные
     * (огромная дельта) или метки нет: такое число нельзя называть задержкой.
     */
    fun captureToSendUs(captureNs: Long, sendNs: Long): Long? {
        if (captureNs <= 0L || sendNs <= 0L) return null
        val us = (sendNs - captureNs) / 1_000L
        if (us !in -50_000L..2_000_000L) return null
        return us
    }
}

/**
 * Общие часы NDI для видео и звука. Видео - метка кадра. Звук - число сэмплов от первого кадра,
 * чтобы дорожки не разъезжались. Звук, пришедший раньше первого кадра, уходит с синтезом;
 * следующий кусок уже стоит на времени этого кадра.
 */
internal class NdiClock {
    private var origin100ns: Long = Long.MIN_VALUE
    private var samplesSent: Long = 0
    private var sampleRate: Int = 0

    fun onVideo(timestampNs: Long): Long {
        val tc = StreamLatency.videoTimecode100ns(timestampNs)
        if (origin100ns == Long.MIN_VALUE && tc != StreamLatency.SYNTHESIZE) {
            val already = if (sampleRate > 0) samplesSent * 10_000_000L / sampleRate else 0L
            origin100ns = tc - already
        }
        return tc
    }

    fun onAudio(pcmBytes: Int, rate: Int, channels: Int): Long {
        if (rate > 0) sampleRate = rate
        val samples = if (channels > 0 && pcmBytes > 0) pcmBytes / (2 * channels) else 0
        val tc = StreamLatency.audioTimecode100ns(origin100ns, samplesSent, sampleRate)
        samplesSent += samples
        return tc
    }

    fun reset() {
        origin100ns = Long.MIN_VALUE
        samplesSent = 0
        sampleRate = 0
    }
}

/**
 * Ключи низкой задержки на уже подготовленном видеокодеке RootEncoder.
 * Профиль не форсируем в Baseline: на части устройств это режет качество или не стартует.
 * B-кадры выключаются отдельно. Если устройство ключи не принимает - возвращаем прежний формат,
 * а если и он не встал - [VideoEncoder.reset] собирает кодек заново, как библиотека.
 */
internal object EncoderLatency {
    private const val TAG = "CameraStream"

    fun tune(stream: StreamBase, fps: Int) {
        val encoder = videoEncoder(stream) ?: return
        val codec = mediaCodec(encoder) ?: return
        val current = runCatching { codec.inputFormat }.getOrNull() ?: return
        val backup = copyFormat(current)
        val tuned = copyFormat(current)
        tuned.setInteger(MediaFormat.KEY_PRIORITY, 0)
        tuned.setInteger(MediaFormat.KEY_OPERATING_RATE, fps.coerceAtLeast(1))
        if (Build.VERSION.SDK_INT >= 26) tuned.setInteger(MediaFormat.KEY_LATENCY, 1)
        if (Build.VERSION.SDK_INT >= 29) tuned.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        if (Build.VERSION.SDK_INT >= 30) tuned.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        val surface = configure(codec, tuned) ?: configure(codec, backup) ?: run {
            Log.w(TAG, "low-latency encoder rejected, restoring library format")
            runCatching { encoder.reset() }
            return
        }
        encoder.setInputSurface(surface)
        Log.i(TAG, "video encoder low-latency keys applied")
    }

    /** Остановить кодировщики H.264/AAC, если NDI забирает сырой кадр сам. Повторный stop библиотека переживёт. */
    fun stopEncoders(stream: StreamBase) {
        listOf("getVideoEncoder", "getVideoEncoderRecord", "getAudioEncoder").forEach { name ->
            runCatching {
                val enc = baseEncoder(stream, name) ?: return@forEach
                if (enc.isRunning) enc.stop()
            }.onFailure { Log.w(TAG, "stop $name", it) }
        }
    }

    private fun configure(codec: MediaCodec, format: MediaFormat): Surface? = runCatching {
        codec.reset()
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.createInputSurface()
    }.getOrNull()

    private fun videoEncoder(stream: StreamBase): VideoEncoder? =
        invoke(stream, "getVideoEncoder") as? VideoEncoder

    private fun baseEncoder(stream: StreamBase, getter: String): BaseEncoder? =
        invoke(stream, getter) as? BaseEncoder

    private fun invoke(stream: StreamBase, getter: String): Any? = runCatching {
        val method = StreamBase::class.java.getDeclaredMethod(getter)
        method.isAccessible = true
        method.invoke(stream)
    }.getOrNull()

    private fun mediaCodec(encoder: BaseEncoder): MediaCodec? = runCatching {
        val field = BaseEncoder::class.java.getDeclaredField("codec")
        field.isAccessible = true
        field.get(encoder) as? MediaCodec
    }.getOrNull()

    private fun copyFormat(src: MediaFormat): MediaFormat {
        val dst = MediaFormat()
        val keys: Iterable<String> = if (Build.VERSION.SDK_INT >= 29) src.keys else KNOWN_KEYS
        for (key in keys) copyKey(src, dst, key)
        return dst
    }

    private fun copyKey(src: MediaFormat, dst: MediaFormat, key: String) {
        if (Build.VERSION.SDK_INT >= 29 && !src.containsKey(key)) return
        runCatching { dst.setInteger(key, src.getInteger(key)); return }
        if (Build.VERSION.SDK_INT >= 29) runCatching { dst.setLong(key, src.getLong(key)); return }
        runCatching { dst.setFloat(key, src.getFloat(key)); return }
        runCatching {
            val text = src.getString(key)
            if (text != null) dst.setString(key, text)
        }
    }

    /** Строки, а не новые константы: список читается и на API 24, где части ключей ещё нет. */
    private val KNOWN_KEYS = listOf(
        MediaFormat.KEY_MIME,
        MediaFormat.KEY_WIDTH,
        MediaFormat.KEY_HEIGHT,
        MediaFormat.KEY_COLOR_FORMAT,
        MediaFormat.KEY_BIT_RATE,
        MediaFormat.KEY_FRAME_RATE,
        MediaFormat.KEY_I_FRAME_INTERVAL,
        MediaFormat.KEY_BITRATE_MODE,
        MediaFormat.KEY_PROFILE,
        MediaFormat.KEY_LEVEL,
        MediaFormat.KEY_COLOR_STANDARD,
        MediaFormat.KEY_COLOR_TRANSFER,
        MediaFormat.KEY_COLOR_RANGE,
        MediaFormat.KEY_MAX_INPUT_SIZE,
        MediaFormat.KEY_PRIORITY,
        MediaFormat.KEY_OPERATING_RATE,
        "latency",
    )
}
