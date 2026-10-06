package tv.p2160.core.engine

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/**
 * «Ночной звук»: громкие сцены тише, диалоги громче.
 *
 * 1. Выделение диалогов: в 5.1/7.1 усиливаем центральный канал (там почти всегда голос),
 *    приглушаем сабвуфер и окружение; в стерео поднимаем «середину» (M/S).
 * 2. Компрессор по пику кадра (порог −30 dBFS, 4:1, быстрая атака, медленный спад) + компенсация.
 * 3. Лимитер, чтобы после подъёма тихих мест ничего не клиппировало.
 *
 * Процессор всегда стоит в цепочке, а [enabled] переключается на лету без пересоздания плеера.
 * Работает с PCM 16 бит — для этого в ночном режиме отключается passthrough на ресивер.
 */
@OptIn(UnstableApi::class)
class NightAudioProcessor(
    /**
     * Сводить многоканальный звук в стерео. Включается, когда ночной режим активен при старте:
     * ночью звук обычно идёт в динамики ТВ/наушники, а многие ТВ не принимают 6-канальный PCM.
     */
    private val downmixToStereo: Boolean = false,
) : BaseAudioProcessor() {

    /** Включён ли ночной режим. Можно менять в любой момент. */
    @Volatile var enabled: Boolean = false

    private var channels = 0
    private var downmixing = false
    private var sampleRate = 0
    private var envelope = 0f
    private var gain = 1f
    private var attack = 0f
    private var release = 0f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        channels = inputAudioFormat.channelCount
        sampleRate = inputAudioFormat.sampleRate
        attack = coefficient(ATTACK_MS)
        release = coefficient(RELEASE_MS)
        downmixing = downmixToStereo && channels > 2
        return if (downmixing) AudioProcessor.AudioFormat(sampleRate, 2, C.ENCODING_PCM_16BIT) else inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        if (downmixing) {
            queueDownmix(inputBuffer)
            return
        }
        val out = replaceOutputBuffer(size)
        if (!enabled) {
            out.put(inputBuffer)
            out.flip()
            return
        }
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val frame = FloatArray(channels)
        while (input.remaining() >= channels * 2) {
            for (c in 0 until channels) frame[c] = input.short / 32768f
            process(frame)
            for (c in 0 until channels) {
                out.putShort((frame[c] * 32767f).coerceIn(-32768f, 32767f).toInt().toShort())
            }
        }
        // Хвост, не кратный кадру (не должен встречаться), переносим как есть.
        while (input.hasRemaining()) out.put(input.get())
        out.flip()
    }

    private fun queueDownmix(inputBuffer: ByteBuffer) {
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val frames = input.remaining() / (channels * 2)
        val out = replaceOutputBuffer(frames * 4)
        val frame = FloatArray(channels)
        val stereo = FloatArray(2)
        repeat(frames) {
            for (c in 0 until channels) frame[c] = input.short / 32768f
            if (enabled) emphasizeDialog(frame)
            downmix(frame, stereo)
            if (enabled) compress(stereo)
            for (v in stereo) out.putShort((v * 32767f).coerceIn(-32768f, 32767f).toInt().toShort())
        }
        input.position(input.limit())
        out.flip()
    }

    /** ITU-подобное сведение: FL/FR + центр −3 дБ + тылы −3 дБ + немного сабвуфера; нормируем. */
    private fun downmix(frame: FloatArray, out: FloatArray) {
        var l = frame[0]
        var r = frame.getOrElse(1) { frame[0] }
        val c = frame.getOrElse(2) { 0f } * 0.707f
        val lfe = if (channels >= 6) frame[3] * 0.3f else 0f
        l += c + lfe
        r += c + lfe
        if (channels >= 6) { l += frame[4] * 0.707f; r += frame[5] * 0.707f }
        if (channels >= 8) { l += frame[6] * 0.707f; r += frame[7] * 0.707f }
        val norm = if (channels >= 6) 0.4f else 0.6f
        out[0] = l * norm
        out[1] = r * norm
    }

    /** Обработка одного кадра (все каналы одного момента времени) на месте. */
    internal fun process(frame: FloatArray) {
        emphasizeDialog(frame)
        compress(frame)
    }

    private fun compress(frame: FloatArray) {
        var peak = 0f
        for (s in frame) peak = max(peak, abs(s))
        // Огибающая: быстро растёт на громком, медленно спадает.
        envelope = if (peak > envelope) attack * envelope + (1 - attack) * peak else release * envelope + (1 - release) * peak

        val levelDb = if (envelope > 1e-6f) 20f * log10(envelope) else -120f
        val compressedDb = if (levelDb > THRESHOLD_DB) THRESHOLD_DB + (levelDb - THRESHOLD_DB) / RATIO else levelDb
        val target = dbToGain(compressedDb - levelDb + MAKEUP_DB)
        // Сглаживаем сам коэффициент, чтобы не было щелчков.
        gain = if (target < gain) attack * gain + (1 - attack) * target else release * gain + (1 - release) * target

        for (c in frame.indices) {
            var v = frame[c] * gain
            // Мягкий лимитер у −1 dBFS.
            if (abs(v) > LIMIT) v = Math.copySign(LIMIT + (abs(v) - LIMIT) / (1 + (abs(v) - LIMIT) / (1 - LIMIT)), v)
            frame[c] = v
        }
    }

    private fun emphasizeDialog(frame: FloatArray) {
        when {
            channels >= 3 -> {
                // Порядок Android: FL FR FC LFE BL BR [SL SR].
                for (c in frame.indices) {
                    frame[c] *= when (c) {
                        2 -> CENTER_GAIN
                        3 -> if (channels >= 6) LFE_GAIN else SIDE_GAIN
                        else -> SIDE_GAIN
                    }
                }
            }
            channels == 2 -> {
                val mid = (frame[0] + frame[1]) * 0.5f * MID_GAIN
                val side = (frame[0] - frame[1]) * 0.5f * SIDE_STEREO_GAIN
                frame[0] = mid + side
                frame[1] = mid - side
            }
        }
    }

    override fun onFlush() {
        envelope = 0f
        gain = 1f
    }

    override fun onReset() {
        channels = 0
        sampleRate = 0
    }

    private fun coefficient(ms: Float): Float =
        if (sampleRate <= 0) 0f else exp(-1f / (ms / 1000f * sampleRate))

    private fun dbToGain(db: Float): Float = 10f.pow(db / 20f)

    private companion object {
        const val THRESHOLD_DB = -30f
        const val RATIO = 4f
        const val MAKEUP_DB = 9f
        const val ATTACK_MS = 5f
        const val RELEASE_MS = 300f
        const val LIMIT = 0.89f
        const val CENTER_GAIN = 1.6f
        const val LFE_GAIN = 0.4f
        const val SIDE_GAIN = 0.75f
        const val MID_GAIN = 1.25f
        const val SIDE_STEREO_GAIN = 0.7f
    }
}
