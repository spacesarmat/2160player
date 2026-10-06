package tv.p2160.core.intro

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Аудио-отпечаток участка файла: по одному 32-битному слову на кадр и маска надёжных бит.
 * Ненадёжные биты (полосы у порога шума) при сравнении считаются «половиной совпадения»,
 * кадр с маской 0 (тишина, края) — случайным. Так тихие и разреженные места не дают ложного сходства.
 *
 * @param startMs время начала участка в файле.
 * @param hopMs шаг между кадрами.
 * @param leadMs сдвиг «содержимого» кадра относительно его начала (центр окна).
 */
class Fingerprint(
    val startMs: Long,
    val hopMs: Double,
    val frames: IntArray,
    val masks: IntArray,
    val leadMs: Double = 0.0,
) {
    init {
        require(frames.size == masks.size)
    }

    val size: Int get() = frames.size
    val durationMs: Long get() = (frames.size * hopMs).toLong()

    /** Время (мс от начала файла), к которому относится кадр [index]. */
    fun timeOf(index: Int): Long = startMs + (index * hopMs + leadMs).toLong()

    /** Индекс кадра для времени файла [ms] (с обрезкой по краям). */
    fun indexOf(ms: Long): Int = ((ms - startMs - leadMs) / hopMs).roundToInt().coerceIn(0, frames.size)

    fun slice(from: Int, to: Int): Fingerprint =
        Fingerprint(startMs + (from * hopMs).toLong(), hopMs, frames.copyOfRange(from, to), masks.copyOfRange(from, to), leadMs)

    /** Расстояние между кадром [i] и кадром [j] отпечатка [other], 0…32 (16 — случайные кадры). */
    fun distance(i: Int, other: Fingerprint, j: Int): Int = bitDistance(frames[i], masks[i], other.frames[j], other.masks[j])

    companion object {
        fun bitDistance(a: Int, ma: Int, b: Int, mb: Int): Int {
            val m = ma and mb
            return Integer.bitCount((a xor b) and m) + (33 - Integer.bitCount(m)) / 2
        }
    }
}

/**
 * Отпечатки в духе Philips/Chromaprint: окно 4096 сэмплов (~0.37 с при 11 025 Гц), шаг ~0.12 с,
 * 32 логарифмические полосы 300–3000 Гц, объединённые в 16 пар и сглаженные по 3 кадрам.
 * 16 бит — форма спектра (знак «лапласиана» по частоте), 16 бит — тренд энергии каждой пары (t+2 против t−2).
 * Полосы тише самой громкой на ~39 дБ обрезаются: пустые полосы не дают случайных бит.
 * Устойчиво к громкости, сжатию, тихому фону; на реальных сериях заметно надёжнее классического Philips.
 */
class Fingerprinter(val config: Config = Config()) {

    data class Config(
        val sampleRate: Int = 11_025,
        val frameSize: Int = 4096,
        val hop: Int = 1365,
        val minHz: Double = 300.0,
        val maxHz: Double = 3000.0,
        /** Кадры тише этого уровня (дБ от полной шкалы) считаются тишиной. */
        val silenceDb: Double = -55.0,
    ) {
        val hopMs: Double get() = hop * 1000.0 / sampleRate
        /** Признаки симметричны относительно кадра — его время соответствует центру окна. */
        val leadMs: Double get() = frameSize * 500.0 / sampleRate
    }

    private val window = FloatArray(config.frameSize) { (0.5 - 0.5 * cos(2 * PI * it / (config.frameSize - 1))).toFloat() }
    private val edges: IntArray = run {
        val binHz = config.sampleRate.toDouble() / config.frameSize
        val e = IntArray(BANDS + 1) { b ->
            (config.minHz * (config.maxHz / config.minHz).pow(b.toDouble() / BANDS) / binHz).roundToInt()
        }
        for (b in 1..BANDS) if (e[b] <= e[b - 1]) e[b] = e[b - 1] + 1
        e
    }

    /** Потоковое вычисление: PCM подаётся кусками, память — одно окно и несколько кадров признаков. */
    fun stream(startMs: Long = 0L): Stream = Stream(startMs)

    fun fingerprint(samples: ShortArray, startMs: Long = 0L): Fingerprint =
        stream(startMs).apply { feed(samples, 0, samples.size) }.finish()

    inner class Stream internal constructor(private val startMs: Long) : PcmSink {
        private val n = config.frameSize
        private val buf = FloatArray(n)
        private var fill = 0
        private val fft = Fft(n)
        private val re = DoubleArray(n)
        private val im = DoubleArray(n)
        private val band = DoubleArray(BANDS)
        /** Кольцо энергий пар (лог) за последние SPAN кадров и флаги «не тишина». */
        private val ring = Array(SPAN) { DoubleArray(PAIRS) }
        private val ringValid = BooleanArray(SPAN)
        /** Пары у порога шума (бит k) по кадрам кольца. */
        private val ringLow = IntArray(SPAN)
        private val smooth = Array(2 * LAG + 1) { DoubleArray(PAIRS) }
        private val silence = 10.0.pow(config.silenceDb / 10) // средняя мощность
        private var frames = 0
        private var out = IntArray(1024)
        private var outMask = IntArray(1024)
        private var count = 0

        override fun accept(samples: ShortArray, count: Int) = feed(samples, 0, count)

        fun feed(samples: ShortArray, offset: Int, length: Int) {
            var i = offset
            val end = offset + length
            while (i < end) {
                val take = minOf(n - fill, end - i)
                for (k in 0 until take) buf[fill + k] = samples[i + k] / 32768f
                fill += take
                i += take
                if (fill == n) {
                    frame()
                    System.arraycopy(buf, config.hop, buf, 0, n - config.hop)
                    fill = n - config.hop
                }
            }
        }

        fun finish(): Fingerprint {
            // Хвостовым кадрам не хватает «будущего» — оставляем их пустыми, индексы не сдвигаются.
            while (count < frames) emit(0, 0)
            return Fingerprint(startMs, config.hopMs, out.copyOf(count), outMask.copyOf(count), config.leadMs)
        }

        private fun frame() {
            var power = 0.0
            for (k in 0 until n) {
                val s = buf[k]
                power += s * s
                re[k] = (s * window[k]).toDouble()
                im[k] = 0.0
            }
            power /= n
            fft.transform(re, im)
            var max = 0.0
            for (b in 0 until BANDS) {
                var e = 0.0
                for (k in edges[b] until edges[b + 1]) e += re[k] * re[k] + im[k] * im[k]
                band[b] = e
                if (e > max) max = e
            }
            val floor = ln(max + 1e-12) - DYNAMIC
            val slot = frames % SPAN
            val pairs = ring[slot]
            var low = 0
            for (k in 0 until PAIRS) {
                pairs[k] = maxOf(ln(band[2 * k] + 1e-12), floor) + maxOf(ln(band[2 * k + 1] + 1e-12), floor)
                if (pairs[k] < 2 * floor + LOW_MARGIN) low = low or (1 shl k)
            }
            ringLow[slot] = low
            ringValid[slot] = power > silence
            frames++
            if (frames < SPAN) {
                if (frames > SPAN / 2) emit(0, 0) // первые кадры без «прошлого»
                return
            }
            word(frames - 1 - SPAN / 2)
        }

        /** Слово и маска для кадра [t]; все кадры t−SPAN/2…t+SPAN/2 уже в кольце. */
        private fun word(t: Int) {
            for (u in t - SPAN / 2..t + SPAN / 2) if (!ringValid[u % SPAN]) return emit(0, 0)
            for (j in 0..2 * LAG) {
                val center = t - LAG + j
                val sm = smooth[j]
                for (k in 0 until PAIRS) {
                    var v = 0.0
                    for (dt in -SMOOTH..SMOOTH) v += ring[(center + dt) % SPAN][k]
                    sm[k] = v
                }
            }
            // Пара «пустая» в окне, если у порога во всех его кадрах.
            fun lowIn(center: Int): Int {
                var m = -1
                for (dt in -SMOOTH..SMOOTH) m = m and ringLow[(center + dt) % SPAN]
                return m
            }
            val nowLow = lowIn(t)
            val beforeLow = lowIn(t - LAG)
            val afterLow = lowIn(t + LAG)
            val now = smooth[LAG]
            val before = smooth[0]
            val after = smooth[2 * LAG]
            var word = 0
            var unreliable = 0
            for (k in 0 until PAIRS) {
                val kl = if (k > 0) k - 1 else 0
                val kh = if (k < PAIRS - 1) k + 1 else PAIRS - 1
                if (2 * now[k] - now[kl] - now[kh] > 0) word = word or (1 shl k)
                if (nowLow shr kl and 1 != 0 && nowLow shr k and 1 != 0 && nowLow shr kh and 1 != 0) unreliable = unreliable or (1 shl k)
                if (after[k] - before[k] > 0) word = word or (1 shl (PAIRS + k))
                if (beforeLow shr k and 1 != 0 && afterLow shr k and 1 != 0) unreliable = unreliable or (1 shl (PAIRS + k))
            }
            emit(word, unreliable.inv())
        }

        private fun emit(word: Int, mask: Int) {
            if (count == out.size) {
                out = out.copyOf(count * 2)
                outMask = outMask.copyOf(count * 2)
            }
            out[count] = word
            outMask[count] = mask
            count++
        }
    }

    private companion object {
        const val BANDS = 32
        const val PAIRS = 16
        /** Шаг тренда по времени, кадров. */
        const val LAG = 2
        /** Полуширина сглаживания по времени, кадров. */
        const val SMOOTH = 1
        /** Кадров в окне признаков: t−LAG−SMOOTH … t+LAG+SMOOTH. */
        const val SPAN = 2 * (LAG + SMOOTH) + 1
        /** Динамический диапазон полос (натуральный логарифм энергии, ≈39 дБ). */
        const val DYNAMIC = 9.0
        /** Пара считается «у порога», если выше него меньше чем на ~4 дБ (сумма двух полос). */
        const val LOW_MARGIN = 1.0
    }
}

/** Комплексное БПФ по основанию 2, на месте. */
internal class Fft(private val n: Int) {
    private val levels = Integer.numberOfTrailingZeros(n)
    private val cosT = DoubleArray(n / 2) { cos(2 * PI * it / n) }
    private val sinT = DoubleArray(n / 2) { sin(2 * PI * it / n) }
    private val rev = IntArray(n) { Integer.reverse(it) ushr (32 - levels) }

    init {
        require(n >= 2 && n and (n - 1) == 0) { "n must be a power of 2" }
    }

    fun transform(re: DoubleArray, im: DoubleArray) {
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var i = 0
            while (i < n) {
                var k = 0
                for (j in i until i + half) {
                    val l = j + half
                    val c = cosT[k]
                    val s = sinT[k]
                    val tr = re[l] * c + im[l] * s
                    val ti = -re[l] * s + im[l] * c
                    re[l] = re[j] - tr
                    im[l] = im[j] - ti
                    re[j] += tr
                    im[j] += ti
                    k += step
                }
                i += size
            }
            size *= 2
        }
    }
}
