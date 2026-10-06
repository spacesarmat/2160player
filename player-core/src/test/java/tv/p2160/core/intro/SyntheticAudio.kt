package tv.p2160.core.intro

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** Синтетический звук для тестов: «тема» с ритмом и мелодией, фоновые «музыка + шум». */
object SyntheticAudio {

    /** Тема длиной [seconds]: ноты по ритму 120 bpm, свипы и шумовые «барабаны». */
    fun theme(seed: Int, seconds: Double, rate: Int): FloatArray {
        val rnd = Random(seed)
        val n = (seconds * rate).toInt()
        val out = FloatArray(n)
        val beat = 0.5
        val scale = doubleArrayOf(0.0, 2.0, 4.0, 5.0, 7.0, 9.0, 11.0, 12.0)
        var t = 0.0
        while (t < seconds) {
            val f0 = 220.0 * 2.0.pow(scale[rnd.nextInt(scale.size)] / 12) * (if (rnd.nextBoolean()) 2 else 1)
            val dur = beat * (1 + rnd.nextInt(2))
            note(out, rate, t, dur, f0, 0.25, rnd.nextInt(3) + 2)
            if (rnd.nextInt(4) == 0) chirp(out, rate, t, 0.3, 400.0 + rnd.nextDouble(800.0), 2500.0, 0.15)
            if ((t / beat).toInt() % 2 == 1) burst(out, rate, t, 0.08, 0.2, rnd)
            t += dur
        }
        return out
    }

    /** Фон серии: случайные ноты (другой «саундтрек») и шум. */
    fun bed(seed: Int, seconds: Double, rate: Int, gain: Double): FloatArray {
        val rnd = Random(seed)
        val n = (seconds * rate).toInt()
        val out = FloatArray(n)
        var t = 0.0
        while (t < seconds) {
            val dur = 0.2 + rnd.nextDouble(0.9)
            note(out, rate, t, dur, 150.0 + rnd.nextDouble(1500.0), 0.2 * gain, rnd.nextInt(4) + 1)
            if (rnd.nextInt(3) == 0) burst(out, rate, t, 0.1 + rnd.nextDouble(0.3), 0.1 * gain, rnd)
            t += dur * (0.5 + rnd.nextDouble())
        }
        for (i in 0 until n) out[i] += ((rnd.nextFloat() - 0.5f) * 0.02f * gain).toFloat()
        return out
    }

    /** Вставка [part] с усилением [gain] в [base] на [atSec] (фон под темой приглушается). */
    fun insert(base: FloatArray, part: FloatArray, atSec: Double, rate: Int, gain: Double, bedLevel: Double = 0.05) {
        val at = (atSec * rate).toInt()
        for (i in part.indices) {
            val j = at + i
            if (j in base.indices) base[j] = (base[j] * bedLevel + part[i] * gain).toFloat()
        }
    }

    fun noise(base: FloatArray, level: Double, seed: Int) {
        val rnd = Random(seed)
        for (i in base.indices) base[i] += ((rnd.nextFloat() - 0.5f) * 2 * level).toFloat()
    }

    fun toPcm(x: FloatArray): ShortArray = ShortArray(x.size) { (x[it].coerceIn(-1f, 1f) * 32767).toInt().toShort() }

    /** Передискретизация через [Resampler] (как в декодере). */
    fun resample(x: FloatArray, from: Int, to: Int): ShortArray {
        val out = ArrayList<Short>((x.size.toLong() * to / from).toInt() + 16)
        val r = Resampler(from, to)
        var i = 0
        while (i < x.size) {
            val n = minOf(8192, x.size - i)
            r.process(x.copyOfRange(i, i + n), n) { s, c -> for (k in 0 until c) out.add(s[k]) }
            i += n
        }
        return out.toShortArray()
    }

    private fun note(out: FloatArray, rate: Int, start: Double, dur: Double, f: Double, amp: Double, harmonics: Int) {
        val s = (start * rate).toInt()
        val len = (dur * rate).toInt()
        for (k in 0 until len) {
            val i = s + k
            if (i >= out.size) break
            val tt = k.toDouble() / rate
            val env = (1 - exp(-tt * 60)) * exp(-tt * 2.5)
            var v = 0.0
            for (h in 1..harmonics) v += sin(2 * PI * f * h * tt) / h
            out[i] += (v * amp * env).toFloat()
        }
    }

    private fun chirp(out: FloatArray, rate: Int, start: Double, dur: Double, f1: Double, f2: Double, amp: Double) {
        val s = (start * rate).toInt()
        val len = (dur * rate).toInt()
        var phase = 0.0
        for (k in 0 until len) {
            val i = s + k
            if (i >= out.size) break
            val f = f1 + (f2 - f1) * k / len
            phase += 2 * PI * f / rate
            out[i] += (sin(phase) * amp * sin(PI * k / len)).toFloat()
        }
    }

    private fun burst(out: FloatArray, rate: Int, start: Double, dur: Double, amp: Double, seedFrom: Random) {
        // Свой генератор: последовательность основного не зависит от частоты дискретизации.
        val rnd = Random(seedFrom.nextInt())
        val s = (start * rate).toInt()
        val len = (dur * rate).toInt()
        for (k in 0 until len) {
            val i = s + k
            if (i >= out.size) break
            out[i] += ((rnd.nextFloat() - 0.5f) * 2 * amp * exp(-k * 8.0 / len)).toFloat()
        }
    }
}
