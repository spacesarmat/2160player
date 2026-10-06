package tv.p2160.core.intro

import java.io.Closeable
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/** Приёмник моно PCM 16 бит. Буфер переиспользуется — копировать, если нужно хранить. */
fun interface PcmSink {
    fun accept(samples: ShortArray, count: Int)
}

/** Источник звука файла: моно 16 бит на заданной частоте, произвольный отрезок времени. */
interface PcmSource : Closeable {
    /** Длительность файла, мс; −1 — неизвестна. */
    val durationMs: Long

    /**
     * Отдаёт отрезок [startMs, startMs + lengthMs) в [sink] на частоте [outRate].
     * @return число отданных сэмплов; меньше ожидаемого — конец файла, ошибка или отмена.
     */
    fun read(startMs: Long, lengthMs: Long, outRate: Int, sink: PcmSink, cancelled: () -> Boolean): Long
}

/** Источник поверх готового массива (тесты, отладка). */
class ArrayPcmSource(private val samples: ShortArray, private val rate: Int) : PcmSource {
    override val durationMs: Long get() = samples.size * 1000L / rate

    override fun read(startMs: Long, lengthMs: Long, outRate: Int, sink: PcmSink, cancelled: () -> Boolean): Long {
        val from = (startMs * rate / 1000).toInt().coerceIn(0, samples.size)
        val to = ((startMs + lengthMs) * rate / 1000).coerceAtMost(samples.size.toLong()).toInt()
        val resampler = Resampler(rate, outRate)
        var produced = 0L
        val chunk = FloatArray(4096)
        var i = from
        while (i < to && !cancelled()) {
            val n = minOf(chunk.size, to - i)
            for (k in 0 until n) chunk[k] = samples[i + k] / 32768f
            resampler.process(chunk, n) { s, c -> produced += c; sink.accept(s, c) }
            i += n
        }
        return produced
    }

    override fun close() = Unit
}

/**
 * Потоковый передискретизатор моно-сигнала: ФНЧ (окно Хэмминга) на входной частоте
 * и линейная интерполяция в точках выхода. Для полос до 3 кГц этого достаточно.
 */
class Resampler(private val inRate: Int, private val outRate: Int) {
    private val step = inRate.toDouble() / outRate
    private val taps: FloatArray = if (inRate > outRate) lowPass(inRate, 0.45 * outRate) else floatArrayOf(1f)
    private val order = taps.size - 1
    /** Входные сэмплы: [order] сэмплов истории + новые. */
    private var buf = FloatArray(order + 8192)
    private var len = order
    /** Позиция следующего выходного сэмпла в координатах buf; +order/2 — компенсация задержки фильтра. */
    private var pos = order * 1.5
    private val out = ShortArray(4096)

    fun process(input: FloatArray, count: Int, sink: PcmSink) {
        if (len + count > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + count))
        System.arraycopy(input, 0, buf, len, count)
        len += count
        var o = 0
        while (true) {
            val i = pos.toInt()
            if (i + 1 >= len) break
            val frac = (pos - i).toFloat()
            val a = filtered(i)
            val b = filtered(i + 1)
            val v = (a + (b - a) * frac) * 32767f
            out[o++] = v.coerceIn(-32768f, 32767f).toInt().toShort()
            if (o == out.size) {
                sink.accept(out, o); o = 0
            }
            pos += step
        }
        if (o > 0) sink.accept(out, o)
        // Оставляем историю для фильтра.
        val keepFrom = (pos.toInt() - order).coerceAtLeast(0)
        System.arraycopy(buf, keepFrom, buf, 0, len - keepFrom)
        len -= keepFrom
        pos -= keepFrom
    }

    private fun filtered(n: Int): Float {
        if (order == 0) return buf[n]
        var acc = 0f
        var k = 0
        var j = n
        while (k <= order) {
            acc += taps[k] * buf[j]
            k++; j--
        }
        return acc
    }

    private companion object {
        fun lowPass(rate: Int, cutoffHz: Double): FloatArray {
            // Ширина перехода ~3 кГц: этого хватает, чтобы наложения не попали в полосы до 3 кГц.
            var n = ceil(3.3 * rate / 3000.0).toInt()
            if (n % 2 == 0) n++
            val fc = cutoffHz / rate
            val m = (n - 1) / 2.0
            val h = DoubleArray(n) { i ->
                val x = i - m
                val sinc = if (x == 0.0) 2 * fc else sin(2 * PI * fc * x) / (PI * x)
                sinc * (0.54 - 0.46 * cos(2 * PI * i / (n - 1)))
            }
            val sum = h.sum()
            return FloatArray(n) { (h[it] / sum).toFloat() }
        }
    }
}
