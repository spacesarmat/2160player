package tv.p2160.core.intro

import android.content.Context
import android.net.Uri
import tv.p2160.core.resume.ResumeStore
import java.io.File

/**
 * Автоопределение заставки и титров по звуку (как intro-skipper у Plex/Jellyfin, но на устройстве).
 *
 * Начало текущей серии (по умолчанию 10 мин) сравнивается с началом 1–2 соседних, конец (6 мин) — с их концами.
 * Общий отрезок 15–150 с — заставка; общий отрезок у конца — титры. Отпечатки файлов, результаты и шаблоны
 * сериала хранятся в filesDir/intro: при следующей серии того же сериала обычно хватает звука только её самой.
 *
 * Сеть: звук читается через MediaExtractor, а он тянет весь поток файла за нужный отрезок — для 4K-ремукса
 * 10 минут это гигабайты. Детектор стоит запускать в фоне, с паузой после старта воспроизведения.
 */
class IntroDetector private constructor(context: Context, config: IntroAnalyzer.Config) {

    private val app = context.applicationContext
    private val analyzer = IntroAnalyzer(IntroStore(File(app.filesDir, "intro")), config)

    /**
     * Поиск для [current] по соседям [siblings] (лучше всего предыдущая и следующая серии того же сезона).
     * @param seriesKey ключ сериала ([tv.p2160.core.engine.SegmentDetector.seriesKey]) — для шаблонов; null — без них.
     * @param durationMs длительность текущего файла, если известна плееру.
     * @param preferredLanguage язык выбранной звуковой дорожки (ISO 639-1/2) — чтобы сравнивать одну и ту же озвучку.
     */
    suspend fun detect(
        current: Uri,
        siblings: List<Uri>,
        headers: Map<String, String> = emptyMap(),
        seriesKey: String? = null,
        durationMs: Long = -1,
        preferredLanguage: String? = null,
    ): DetectionResult {
        fun episode(uri: Uri, duration: Long = -1) = IntroAnalyzer.Episode(keyFor(uri), duration) {
            MediaCodecPcmDecoder.open(app, uri, headers, preferredLanguage)
        }
        return analyzer.analyze(episode(current, durationMs), siblings.map { episode(it) }, seriesKey)
    }

    /** Готовый результат без декодирования (null — ещё не искали). */
    fun cached(current: Uri): DetectionResult? = analyzer.cached(keyFor(current))

    companion object {
        @Volatile private var instance: IntroDetector? = null

        fun get(context: Context): IntroDetector =
            instance ?: synchronized(this) { instance ?: IntroDetector(context, IntroAnalyzer.Config()).also { instance = it } }

        /** Ключ файла: как у истории просмотра; для локальных файлов — ещё размер и время изменения. */
        fun keyFor(uri: Uri): String {
            val base = ResumeStore.keyFor(uri)
            if (uri.scheme == "file" || uri.scheme == null) {
                val f = uri.path?.let(::File)
                if (f != null && f.exists()) return "$base|${f.length()}|${f.lastModified()}"
            }
            return base
        }

        /** Соседи для элемента [index] плейлиста: предыдущий и следующий, затем дальше. */
        fun <T> pickSiblings(items: List<T>, index: Int, max: Int = 2): List<T> =
            (1 until items.size).asSequence()
                .flatMap { sequenceOf(index - it, index + it) }
                .filter { it in items.indices }
                .take(max)
                .map { items[it] }
                .toList()
    }
}
