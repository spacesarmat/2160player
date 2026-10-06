package tv.p2160.core.intro

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Поиск общего звукового отрезка в двух отпечатках: перебор всех сдвигов,
 * на каждом — самый длинный участок, где среднее расстояние Хэмминга по окну ниже порога.
 */
object IntroMatcher {

    data class Params(
        val minLenMs: Long = 15_000,
        val maxLenMs: Long = 150_000,
        /** Окно сглаживания, кадров (~1 с). */
        val window: Int = 8,
        /** Порог среднего числа несовпавших бит в окне (из 32; у случайных пар ~16). */
        val maxAvgBits: Double = 10.0,
        /** Порог для кадра на краю отрезка при уточнении границ. */
        val edgeBits: Int = 11,
        /**
         * Допустимый разрыв внутри отрезка: эффекты и реплики поверх темы, разные вставки в заставке
         * (у Star Trek: Discovery внутри титров ~15 с различаются, отсюда 8 с с подтверждением).
         */
        val gapMs: Long = 8_000,
        /** После разрыва совпадение должно продержаться столько, чтобы отрезок продолжился. */
        val confirmMs: Long = 1_000,
        /** Минимальная длина затравки на постоянном сдвиге. */
        val seedMs: Long = 4_000,
    )

    /** Найденный общий отрезок: времена в файлах A и B (мс), кадры [aFrom, aTo). */
    data class Match(
        val aStartMs: Long,
        val aEndMs: Long,
        val bStartMs: Long,
        val bEndMs: Long,
        val meanBits: Double,
        val confidence: Float,
        val aFrom: Int,
        val aTo: Int,
        val bFrom: Int,
        val bTo: Int,
    ) {
        val lengthMs: Long get() = aEndMs - aStartMs
    }

    /** Итог по текущему файлу после сведения нескольких пар. */
    data class Found(val startMs: Long, val endMs: Long, val confidence: Float, val match: Match)

    /** Оценка кандидата (кадры A и B, полуинтервалы); больше — лучше. */
    fun interface Scorer {
        fun score(aFrom: Int, aTo: Int, bFrom: Int, bTo: Int, aSize: Int, bSize: Int): Double
    }

    val byLength = Scorer { aFrom, aTo, _, _, _, _ -> (aTo - aFrom).toDouble() }

    /** Титры: длиннее — лучше, но доходящие до конца окна в обоих файлах — сильно лучше. */
    fun creditsScorer(nearEndFrames: Int) = Scorer { aFrom, aTo, _, bTo, aSize, bSize ->
        val len = (aTo - aFrom).toDouble()
        if (aSize - aTo <= nearEndFrames && bSize - bTo <= nearEndFrames) len + 100_000 else len
    }

    /**
     * Лучший общий отрезок A и B. Сначала перебор всех постоянных сдвигов даёт «затравки» (≥ [Params.seedMs]),
     * затем каждая растягивается кадр за кадром с подстройкой сдвига на ±1 кадр — так переживается
     * небольшое расхождение темпа (у реальных релизов встречается ~2%).
     */
    fun bestCommon(a: Fingerprint, b: Fingerprint, params: Params = Params(), scorer: Scorer = byLength): Match? {
        val fa = a.frames
        val fb = b.frames
        val ma = a.masks
        val mb = b.masks
        val hop = a.hopMs
        val minF = max(1, (params.minLenMs / hop).toInt())
        val maxF = (params.maxLenMs / hop).toInt()
        val gapF = (params.gapMs / hop).toInt()
        val seedF = max(2 * params.window, (params.seedMs / hop).toInt())

        var best: Match? = null
        var bestScore = Double.NEGATIVE_INFINITY
        val paths = ArrayList<Path>()
        for (seed in seeds(fa, ma, fb, mb, params, seedF)) {
            val mid = (seed.from + seed.to) / 2
            if (paths.any { mid >= it.from && mid < it.to && abs(it.offsetAt(mid) - seed.d) <= 2 }) continue
            val path = extend(fa, ma, fb, mb, seed, params, gapF, hop)
            paths += path
            var s = path.from
            var e = path.to
            while (s < e && dist(fa, ma, s, fb, mb, s + path.offsetAt(s)) > params.edgeBits) s++
            while (e > s && dist(fa, ma, e - 1, fb, mb, e - 1 + path.offsetAt(e - 1)) > params.edgeBits) e--
            val len = e - s
            if (len < minF || len > maxF) continue
            val bFrom = s + path.offsetAt(s)
            val bTo = e + path.offsetAt(e - 1)
            val score = scorer.score(s, e, bFrom, bTo, fa.size, fb.size)
            var bits = 0L
            for (i in s until e) bits += dist(fa, ma, i, fb, mb, i + path.offsetAt(i))
            val mean = bits.toDouble() / len
            if (score > bestScore || (score == bestScore && mean < (best?.meanBits ?: Double.MAX_VALUE))) {
                bestScore = score
                best = Match(
                    aStartMs = a.timeOf(s), aEndMs = a.timeOf(e),
                    bStartMs = b.timeOf(bFrom), bEndMs = b.timeOf(bTo),
                    meanBits = mean, confidence = confidenceOf(mean, len * hop),
                    aFrom = s, aTo = e, bFrom = bFrom, bTo = bTo,
                )
            }
        }
        return best
    }

    private class Seed(val from: Int, val to: Int, val d: Int)

    /** Путь совпадения: кадры A [from, to) и сдвиг до B для каждого кадра. */
    private class Path(val from: Int, val to: Int, private val offsets: IntArray) {
        fun offsetAt(i: Int): Int = offsets[(i - from).coerceIn(0, offsets.size - 1)]
    }

    /** Все постоянные сдвиги (кадр i файла A ↔ кадр i + d файла B); самые длинные участки — затравки. */
    private fun seeds(fa: IntArray, ma: IntArray, fb: IntArray, mb: IntArray, params: Params, seedF: Int): List<Seed> {
        val w = params.window
        val limit = params.maxAvgBits * w
        val hist = IntArray(w)
        val top = ArrayList<Seed>()
        fun add(from: Int, to: Int, d: Int) {
            if (to - from < seedF) return
            if (top.size == MAX_SEEDS && to - from <= top.last().let { it.to - it.from }) return
            // Соседние диагонали одного совпадения не должны вытеснять другие кандидаты.
            val twin = top.indexOfFirst { abs(it.d - d) <= 3 && min(it.to, to) - max(it.from, from) > 0 }
            if (twin >= 0) {
                if (top[twin].to - top[twin].from >= to - from) return
                top.removeAt(twin)
            }
            val seed = Seed(from, to, d)
            val at = top.indexOfFirst { it.to - it.from < to - from }.let { if (it < 0) top.size else it }
            top.add(at, seed)
            if (top.size > MAX_SEEDS) top.removeAt(top.size - 1)
        }
        for (d in -(fa.size - seedF)..(fb.size - seedF)) {
            val i0 = max(0, -d)
            val i1 = min(fa.size, fb.size - d)
            if (i1 - i0 < seedF) continue
            var sum = 0
            var slot = 0
            var filled = 0
            var runFrom = -1
            var runTo = -1
            for (i in i0 until i1) {
                val h = dist(fa, ma, i, fb, mb, i + d)
                if (filled == w) sum -= hist[slot] else filled++
                hist[slot] = h
                sum += h
                if (++slot == w) slot = 0
                if (filled == w && sum <= limit) {
                    val from = i - w + 1
                    if (runFrom >= 0 && from <= runTo) {
                        runTo = i + 1
                    } else {
                        if (runFrom >= 0) add(runFrom, runTo, d)
                        runFrom = from
                        runTo = i + 1
                    }
                }
            }
            if (runFrom >= 0) add(runFrom, runTo, d)
        }
        return top
    }

    /** Растягивает затравку в обе стороны, подстраивая сдвиг на ±1 кадр по сглаженному расстоянию. */
    private fun extend(fa: IntArray, ma: IntArray, fb: IntArray, mb: IntArray, seed: Seed, params: Params, gapF: Int, hopMs: Double): Path {
        val w = params.window
        val confirmF = max(1, (params.confirmMs / hopMs).toInt())
        fun walk(start: Int, step: Int): IntArray {
            val offs = ArrayList<Int>()
            var d = seed.d
            var i = start
            var good = 0 // сколько шагов до последнего принятого кадра включительно
            var streak = 0
            while (offs.size - good <= gapF) {
                var bestC = d
                var bestAvg = windowAvg(fa, ma, fb, mb, i, d, w)
                if (bestAvg.isNaN()) break
                for (c in intArrayOf(d - 1, d + 1)) {
                    val v = windowAvg(fa, ma, fb, mb, i, c, w)
                    if (!v.isNaN() && v < bestAvg - 1.0) {
                        bestAvg = v
                        bestC = c
                    }
                }
                offs += bestC
                val contiguous = good == offs.size - 1
                // После разрыва продолжение должно быть заметно лучше порога и держаться [Params.confirmMs].
                val limit = if (contiguous) params.maxAvgBits else params.maxAvgBits - BRIDGE_MARGIN
                if (bestAvg <= limit) {
                    d = bestC
                    streak++
                    if (contiguous || streak >= confirmF) good = offs.size
                } else {
                    streak = 0
                }
                i += step
            }
            return IntArray(good) { offs[it] }
        }
        val fwd = walk(seed.to, 1)
        val back = walk(seed.from - 1, -1)
        val from = seed.from - back.size
        val to = seed.to + fwd.size
        val offsets = IntArray(to - from)
        for (k in back.indices) offsets[seed.from - 1 - k - from] = back[k]
        for (k in seed.from until seed.to) offsets[k - from] = seed.d
        for (k in fwd.indices) offsets[seed.to + k - from] = fwd[k]
        return Path(from, to, offsets)
    }

    /** Среднее расстояние в окне с центром [i] при сдвиге [d]; NaN — окно почти вне файлов. */
    private fun windowAvg(fa: IntArray, ma: IntArray, fb: IntArray, mb: IntArray, i: Int, d: Int, w: Int): Double {
        if (i < 0 || i >= fa.size || i + d < 0 || i + d >= fb.size) return Double.NaN
        var sum = 0
        var n = 0
        for (j in i - w / 2 until i + w / 2) {
            if (j < 0 || j >= fa.size || j + d < 0 || j + d >= fb.size) continue
            sum += dist(fa, ma, j, fb, mb, j + d)
            n++
        }
        return if (n < w / 2) Double.NaN else sum.toDouble() / n
    }

    /** Заставка текущего файла по началам соседних серий. */
    fun findIntro(current: Fingerprint, siblings: List<Fingerprint>, params: Params = Params()): Found? =
        combine(siblings.map { bestCommon(current, it, params) })?.let { snapToStart(it, current) }

    /** Первые кадры отпечатка всегда пустые: заставка «почти с нуля» начинается с нуля. */
    private fun snapToStart(f: Found, current: Fingerprint): Found =
        if (current.startMs == 0L && f.startMs <= START_SNAP_MS) f.copy(startMs = 0) else f

    /**
     * Титры по хвостам: [current] и [siblings] — отпечатки последних минут.
     * Предпочитаются отрезки, доходящие почти до конца окна.
     */
    fun findCredits(
        current: Fingerprint,
        siblings: List<Fingerprint>,
        params: Params = CREDITS,
        nearEndMs: Long = 12_000,
    ): Found? {
        val scorer = creditsScorer((nearEndMs / current.hopMs).toInt())
        return combine(siblings.map { bestCommon(current, it, params, scorer) })
    }

    /** Поиск сохранённого шаблона (отпечатка заставки/титров сериала) в файле. */
    fun matchTemplate(template: Fingerprint, target: Fingerprint, params: Params = Params()): Found? {
        val p = params.copy(
            minLenMs = max(params.minLenMs, (template.durationMs * 0.5).toLong()),
            maxLenMs = max(params.maxLenMs, template.durationMs + 1_000),
        )
        val m = bestCommon(target, template, p) ?: return null
        return Found(m.aStartMs, m.aEndMs, m.confidence, m).takeIf { it.confidence >= MIN_CONFIDENCE }
    }

    /**
     * Сведение пар «текущий — сосед». Одна пара принимается при достаточной уверенности;
     * из нескольких нужны две согласные (перекрытие ≥ половины), иначе — только очень уверенная.
     */
    fun combine(matches: List<Match?>): Found? {
        val ok = matches.filterNotNull().filter { it.confidence >= MIN_CONFIDENCE }
        if (ok.isEmpty()) return null
        if (matches.size == 1) return ok[0].let { Found(it.aStartMs, it.aEndMs, it.confidence, it) }
        var bestPair: Found? = null
        for (x in ok.indices) for (y in x + 1 until ok.size) {
            val p = ok[x]
            val q = ok[y]
            val overlap = min(p.aEndMs, q.aEndMs) - max(p.aStartMs, q.aStartMs)
            if (overlap < min(p.lengthMs, q.lengthMs) / 2) continue
            // Близкие границы — осторожная (лучше секунда заставки, чем секунда серии); далёкие — значит,
            // одна пара оборвалась на различающемся куске, а переползание за край ограничено подтверждением.
            val start = if (abs(p.aStartMs - q.aStartMs) <= 3_000) max(p.aStartMs, q.aStartMs) else min(p.aStartMs, q.aStartMs)
            val end = if (abs(p.aEndMs - q.aEndMs) <= 3_000) min(p.aEndMs, q.aEndMs) else max(p.aEndMs, q.aEndMs)
            val conf = min(1f, (p.confidence + q.confidence) / 2 + 0.2f)
            val f = Found(start, end, conf, if (p.confidence >= q.confidence) p else q)
            if (bestPair == null || f.confidence > bestPair.confidence) bestPair = f
        }
        if (bestPair != null) return bestPair
        val top = ok.maxBy { it.confidence }
        return Found(top.aStartMs, top.aEndMs, top.confidence, top).takeIf { top.confidence >= SINGLE_CONFIDENCE }
    }

    /** Уверенность: доля «запаса» среднего расстояния до порога случайности. */
    fun confidenceOf(meanBits: Double, lengthMs: Double): Float {
        val bits = ((12.0 - meanBits) / 8.0).coerceIn(0.0, 1.0)
        val len = (lengthMs / 25_000.0).coerceIn(0.5, 1.0)
        return (bits * len).toFloat()
    }

    /** Титры: длиннее, но с короткими разрывами — иначе к ним прилипает анонс следующей серии. */
    val CREDITS = Params(maxLenMs = 300_000, gapMs = 3_000)

    private const val MIN_CONFIDENCE = 0.2f
    private const val SINGLE_CONFIDENCE = 0.6f
    private const val MAX_SEEDS = 24
    private const val BRIDGE_MARGIN = 2.0
    const val START_SNAP_MS = 2_000L

    private fun dist(fa: IntArray, ma: IntArray, i: Int, fb: IntArray, mb: IntArray, j: Int): Int =
        Fingerprint.bitDistance(fa[i], ma[i], fb[j], mb[j])
}
