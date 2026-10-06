package tv.p2160.core.intro

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import tv.p2160.core.api.SegmentType
import tv.p2160.core.api.SkipSegment

/**
 * Поиск заставки и титров без Android: источники звука даёт вызывающий.
 *
 * Порядок: готовый результат из кэша → шаблоны сериала (нужен только звук текущей серии) →
 * сравнение с соседними сериями (их отпечатки тоже кэшируются, так что следующая серия считает только себя).
 */
class IntroAnalyzer(
    private val store: IntroStore?,
    private val config: Config = Config(),
    private val fingerprinter: Fingerprinter = Fingerprinter(),
) {

    data class Config(
        /** Сколько начала серии слушать. Больше — дольше и тяжелее для сети (читается весь поток файла). */
        val introWindowMs: Long = 600_000,
        /** Сколько конца серии слушать для титров. */
        val creditsWindowMs: Long = 360_000,
        val maxSiblings: Int = 2,
        /** Титры, кончающиеся ближе к концу файла, считаются «до конца» (endMs = null). */
        val creditsToEndMs: Long = 15_000,
        /** Минимальная уверенность, чтобы сохранить найденное как шаблон сериала. */
        val templateConfidence: Float = 0.5f,
        val maxTemplates: Int = 3,
        val intro: IntroMatcher.Params = IntroMatcher.Params(),
        val credits: IntroMatcher.Params = IntroMatcher.CREDITS,
    )

    /**
     * Серия: [key] — устойчивый ключ файла (для кэша), [durationMs] — если уже известна,
     * [open] — открыть источник звука (null — не получилось); вызывается не больше одного раза.
     */
    class Episode(val key: String, val durationMs: Long = -1, val open: () -> PcmSource?)

    fun cached(key: String): DetectionResult? = store?.result(key)

    /** Работает на Dispatchers.Default (сравнение) и Dispatchers.IO (декодирование); отменяется вместе с корутиной. */
    suspend fun analyze(current: Episode, siblings: List<Episode>, seriesKey: String? = null): DetectionResult =
        withContext(Dispatchers.Default) { analyzeNow(current, siblings, seriesKey) }

    private suspend fun analyzeNow(current: Episode, siblings: List<Episode>, seriesKey: String?): DetectionResult {
        val sibList = siblings.filter { it.key != current.key }.take(config.maxSiblings)
        store?.resultEntry(current.key)?.let { (cached, tried) ->
            // Неполный результат пересчитываем, если появились соседи, с которыми ещё не сравнивали.
            val complete = cached.intro != null && cached.credits != null
            if (complete || sibList.all { store.name(it.key) in tried }) return cached
        }
        val audio = HashMap<String, EpisodeAudio>()
        fun audioOf(e: Episode) = audio.getOrPut(e.key) { EpisodeAudio(e) }
        try {
            val cur = audioOf(current)
            val head = cur.head() ?: return DetectionResult.EMPTY
            val tail = cur.tail()

            val templates = seriesKey?.let { store?.templates(it) }
            var intro = templates?.intros?.firstNotNullOfOrNull { IntroMatcher.matchTemplate(it, head, config.intro) }
                ?.let { if (head.startMs == 0L && it.startMs <= IntroMatcher.START_SNAP_MS) it.copy(startMs = 0) else it }
            var credits = if (tail == null) null else templates?.credits?.firstNotNullOfOrNull { IntroMatcher.matchTemplate(it, tail, config.credits) }
            var newIntro: Fingerprint? = null
            var newCredits: Fingerprint? = null

            val sibs = sibList.map(::audioOf)
            if (intro == null && sibs.isNotEmpty()) {
                intro = IntroMatcher.findIntro(head, sibs.mapNotNull { it.head() }, config.intro)
                if (intro != null && intro.confidence >= config.templateConfidence) {
                    newIntro = head.slice(head.indexOf(intro.startMs), head.indexOf(intro.endMs))
                }
            }
            if (credits == null && tail != null && sibs.isNotEmpty()) {
                credits = IntroMatcher.findCredits(tail, sibs.mapNotNull { it.tail() }, config.credits)
                if (credits != null && credits.confidence >= config.templateConfidence) {
                    newCredits = tail.slice(tail.indexOf(credits.startMs), tail.indexOf(credits.endMs))
                }
            }
            if (seriesKey != null && store != null && (newIntro != null || newCredits != null)) {
                val old = store.templates(seriesKey)
                store.putTemplates(
                    seriesKey,
                    SeriesTemplates(addTemplate(old.intros, newIntro, config.intro), addTemplate(old.credits, newCredits, config.credits)),
                )
            }

            val duration = cur.durationMs
            val result = DetectionResult(
                intro = intro?.let { SkipSegment(SegmentType.INTRO, it.startMs, it.endMs) },
                credits = credits?.let {
                    val toEnd = duration > 0 && duration - it.endMs <= config.creditsToEndMs
                    SkipSegment(SegmentType.CREDITS, it.startMs, if (toEnd) null else it.endMs)
                },
                introConfidence = intro?.confidence ?: 0f,
                creditsConfidence = credits?.confidence ?: 0f,
            )
            // Пустой итог без соседей не запоминаем: с соседями позже может найтись.
            if (sibs.isNotEmpty() || result.segments.isNotEmpty()) store?.putResult(current.key, result, sibList.map { it.key })
            return result
        } finally {
            audio.values.forEach { it.close() }
        }
    }

    private fun addTemplate(list: List<Fingerprint>, new: Fingerprint?, params: IntroMatcher.Params): List<Fingerprint> {
        new ?: return list
        // Та же музыка уже есть — оставляем старый шаблон, но поднимаем его наверх.
        val same = list.indexOfFirst { IntroMatcher.matchTemplate(it, new, params) != null }
        return if (same >= 0) listOf(list[same]) + list.filterIndexed { i, _ -> i != same }
        else (listOf(new) + list).take(config.maxTemplates)
    }

    /** Ленивый доступ к началу/концу одной серии: кэш отпечатков, источник открывается один раз. */
    private inner class EpisodeAudio(private val episode: Episode) {
        private var source: PcmSource? = null
        private var opened = false
        private var headFp: Fingerprint? = null
        private var tailFp: Fingerprint? = null
        private var tailDone = false
        var durationMs: Long = episode.durationMs
            private set

        private val headKey get() = "${episode.key}|head|${config.introWindowMs}"
        private val tailKey get() = "${episode.key}|tail|${config.creditsWindowMs}"

        private fun source(): PcmSource? {
            if (!opened) {
                opened = true
                source = runCatching(episode.open).getOrNull()
                source?.durationMs?.takeIf { it > 0 }?.let { durationMs = it }
            }
            return source
        }

        suspend fun head(): Fingerprint? {
            headFp?.let { return it }
            store?.fingerprint(headKey)?.let { stored ->
                if (stored.fileDurationMs > 0 && durationMs <= 0) durationMs = stored.fileDurationMs
                return stored.fingerprint.also { headFp = it }
            }
            val src = source() ?: return null
            val length = if (durationMs > 0) minOf(config.introWindowMs, durationMs / 2) else config.introWindowMs
            val fp = decode(src, 0, length) ?: return null
            store?.putFingerprint(headKey, StoredFingerprint(fp, durationMs))
            return fp.also { headFp = it }
        }

        suspend fun tail(): Fingerprint? {
            if (tailDone) return tailFp
            tailDone = true
            store?.fingerprint(tailKey)?.let { stored ->
                if (stored.fileDurationMs > 0 && durationMs <= 0) durationMs = stored.fileDurationMs
                return stored.fingerprint.also { tailFp = it }
            }
            val src = source() ?: return null
            if (durationMs <= 0) return null
            val start = maxOf(durationMs - config.creditsWindowMs, durationMs / 2)
            val fp = decode(src, start, durationMs - start) ?: return null
            store?.putFingerprint(tailKey, StoredFingerprint(fp, durationMs))
            return fp.also { tailFp = it }
        }

        private suspend fun decode(src: PcmSource, startMs: Long, lengthMs: Long): Fingerprint? = withContext(Dispatchers.IO) {
            val job = coroutineContext.job
            val stream = fingerprinter.stream(startMs)
            val got = src.read(startMs, lengthMs, fingerprinter.config.sampleRate, stream) { !job.isActive }
            coroutineContext.ensureActive()
            // Совсем мало звука — источник не читается; кэшировать нечего.
            if (got < fingerprinter.config.sampleRate * 20L) null else stream.finish()
        }

        fun close() {
            runCatching { source?.close() }
        }
    }
}
