package tv.p2160.core

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.p2160.core.engine.NightAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

class NightAudioProcessorTest {

    private val rate = 48_000

    /** Пропускает моно/стерео/5.1 сигнал через процессор и возвращает пики по участкам. */
    private fun run(processor: NightAudioProcessor, channels: Int, samples: ShortArray): ShortArray {
        processor.configure(AudioProcessor.AudioFormat(rate, channels, C.ENCODING_PCM_16BIT))
        processor.flush()
        val input = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder())
        samples.forEach(input::putShort)
        input.flip()
        processor.queueInput(input)
        val out = processor.output.order(ByteOrder.nativeOrder())
        return ShortArray(out.remaining() / 2) { out.short }
    }

    /** 2 с тихого тона (−40 dBFS), затем 2 с громкого (−3 dBFS), стерео. */
    private fun quietThenLoud(): ShortArray {
        val frames = rate * 4
        return ShortArray(frames * 2) { i ->
            val n = i / 2
            val amp = if (n < rate * 2) 0.01 else 0.7
            (amp * sin(2 * PI * 440 * n / rate) * 32767).toInt().toShort()
        }
    }

    private fun peak(samples: ShortArray, fromFrame: Int, toFrame: Int, channels: Int): Int {
        var p = 0
        for (i in fromFrame * channels until toFrame * channels) p = max(p, abs(samples[i].toInt()))
        return p
    }

    @Test
    fun disabledIsBitExactPassthrough() {
        val input = quietThenLoud()
        val output = run(NightAudioProcessor(), 2, input)
        assertArrayEquals(input, output)
    }

    @Test
    fun compressesDynamicRange() {
        val input = quietThenLoud()
        val output = run(NightAudioProcessor().apply { enabled = true }, 2, input)
        // Берём установившиеся участки (после атаки/спада).
        val quietIn = peak(input, rate, rate * 2, 2).toDouble()
        val loudIn = peak(input, rate * 3, rate * 4, 2).toDouble()
        val quietOut = peak(output, rate, rate * 2, 2).toDouble()
        val loudOut = peak(output, rate * 3, rate * 4, 2).toDouble()
        assertTrue("тихое должно стать громче: $quietIn → $quietOut", quietOut > quietIn * 2)
        assertTrue("громкое не должно стать громче: $loudIn → $loudOut", loudOut <= loudIn)
        assertTrue("разница громкости должна сократиться", loudOut / quietOut < loudIn / quietIn / 3)
        assertTrue("без клиппинга", output.all { abs(it.toInt()) < 32767 })
    }

    @Test
    fun downmixes51ToStereo() {
        val processor = NightAudioProcessor(downmixToStereo = true).apply { enabled = true }
        val format = processor.configure(AudioProcessor.AudioFormat(rate, 6, C.ENCODING_PCM_16BIT))
        assertTrue("выход — стерео", format.channelCount == 2)
        processor.flush()
        val frames = rate
        val input = ByteBuffer.allocateDirect(frames * 6 * 2).order(ByteOrder.nativeOrder())
        // Голос только в центре.
        for (n in 0 until frames) for (c in 0 until 6) input.putShort(if (c == 2) (0.3 * sin(2 * PI * 300 * n / rate) * 32767).toInt().toShort() else 0)
        input.flip()
        processor.queueInput(input)
        val out = processor.output.order(ByteOrder.nativeOrder())
        assertTrue("стерео-кадров столько же", out.remaining() == frames * 4)
        val samples = ShortArray(out.remaining() / 2) { out.short }
        val left = (frames / 2 until frames).maxOf { abs(samples[it * 2].toInt()) }
        val right = (frames / 2 until frames).maxOf { abs(samples[it * 2 + 1].toInt()) }
        assertTrue("центр слышен в обоих каналах: $left / $right", left > 3000 && abs(left - right) < 50)
    }

    @Test
    fun boostsCenterChannelIn51() {
        val frames = rate
        // Одинаковый тон во всех 6 каналах.
        val input = ShortArray(frames * 6) { i -> (0.1 * sin(2 * PI * 300 * (i / 6) / rate) * 32767).toInt().toShort() }
        val output = run(NightAudioProcessor().apply { enabled = true }, 6, input)
        fun channelPeak(c: Int) = (rate / 2 until rate).maxOf { abs(output[it * 6 + c].toInt()) }
        assertTrue("центр громче фронта", channelPeak(2) > channelPeak(0) * 1.8)
        assertTrue("сабвуфер тише фронта", channelPeak(3) < channelPeak(0))
    }
}
