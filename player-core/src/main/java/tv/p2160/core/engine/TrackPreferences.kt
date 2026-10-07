package tv.p2160.core.engine

import android.content.Context
import org.json.JSONObject

/** Что пользователь выбрал: язык и «тип» озвучки (подсказка по названию), субтитры или их отсутствие. */
data class TrackChoice(
    val audioLanguage: String?,
    /** Нормализованная подсказка из названия дорожки: "dub", "mvo", "original"… */
    val audioHint: String?,
    /** null — субтитры выключены. */
    val textLanguage: String?,
    val textForced: Boolean = false,
)

/** Минимальное описание дорожки для правил (без зависимостей от Media3 — удобно тестировать). */
data class TrackCandidate(
    val index: Int,
    val language: String?,
    val label: String?,
    val channels: Int = 0,
    val forced: Boolean = false,
)

/**
 * Обучаемый выбор дорожек. Запоминает ручной выбор пользователя:
 * - по сериалу (точнее всего);
 * - по набору языков файла («ja+ru» → японская озвучка + русские субтитры).
 */
object TrackRules {

    /** Ключ «привычки»: отсортированные языки аудио. */
    fun contextKey(audio: List<TrackCandidate>): String? =
        audio.mapNotNull { normalizeLang(it.language) }.distinct().sorted().takeIf { it.isNotEmpty() }?.joinToString("+")

    fun normalizeLang(code: String?): String? {
        val c = code?.lowercase()?.substringBefore('-')?.takeIf { it.isNotBlank() && it != "und" } ?: return null
        return ISO3[c] ?: c
    }

    /** Подсказка типа озвучки по названию. */
    fun hintOf(label: String?): String? {
        val l = label?.lowercase() ?: return null
        return when {
            "comment" in l || "коммент" in l -> "commentary"
            "orig" in l || "оригин" in l -> "original"
            Regex("\\bdub\\b|дубл").containsMatchIn(l) -> "dub"
            Regex("\\bmvo\\b|многогол").containsMatchIn(l) -> "mvo"
            Regex("\\bdvo\\b|двухгол").containsMatchIn(l) -> "dvo"
            Regex("\\bavo\\b|автор").containsMatchIn(l) -> "avo"
            else -> null
        }
    }

    /** Лучшая аудиодорожка под выбор: язык → тип озвучки → не комментарий → больше каналов. */
    fun pickAudio(choice: TrackChoice, audio: List<TrackCandidate>): TrackCandidate? {
        val lang = normalizeLang(choice.audioLanguage) ?: return null
        val sameLang = audio.filter { normalizeLang(it.language) == lang }
        if (sameLang.isEmpty()) return null
        return sameLang.maxWithOrNull(
            compareBy<TrackCandidate> { if (choice.audioHint != null && hintOf(it.label) == choice.audioHint) 1 else 0 }
                .thenBy { if (hintOf(it.label) == "commentary" && choice.audioHint != "commentary") 0 else 1 }
                .thenBy { it.channels }
                .thenByDescending { it.index }
        )
    }

    /** Субтитры под выбор; null в [Result.track] при textLanguage == null означает «выключить». */
    fun pickText(choice: TrackChoice, text: List<TrackCandidate>): TrackCandidate? {
        val lang = normalizeLang(choice.textLanguage) ?: return null
        val sameLang = text.filter { normalizeLang(it.language) == lang }
        return sameLang.firstOrNull { it.forced == choice.textForced } ?: sameLang.firstOrNull()
    }

    /**
     * Самая похожая сохранённая привычка для набора языков файла: выбранный язык озвучки
     * должен быть в файле, сходство наборов (Жаккар) ≥ 0,5. Точное совпадение — 1,0.
     */
    fun bestContext(stored: Map<String, TrackChoice>, languages: Set<String>): TrackChoice? =
        stored.entries
            .filter { (_, c) -> normalizeLang(c.audioLanguage)?.let { it in languages } == true }
            .map { (key, c) ->
                val set = key.split('+').toSet()
                val score = (set intersect languages).size.toDouble() / (set union languages).size
                score to c
            }
            .filter { it.first >= 0.5 }
            .maxByOrNull { it.first }?.second

    private val ISO3 = mapOf(
        "rus" to "ru", "eng" to "en", "jpn" to "ja", "ukr" to "uk", "ger" to "de", "deu" to "de",
        "fre" to "fr", "fra" to "fr", "spa" to "es", "ita" to "it", "kor" to "ko", "chi" to "zh", "zho" to "zh",
        "por" to "pt", "pol" to "pl", "tur" to "tr", "bel" to "be", "kaz" to "kk",
    )
}

/** Хранилище привычек (SharedPreferences, JSON). */
class TrackPreferences private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("p2160_track_prefs", Context.MODE_PRIVATE)

    fun forSeries(seriesKey: String?): TrackChoice? = seriesKey?.let { read("series:$it") }

    fun forContext(contextKey: String?): TrackChoice? = contextKey?.let { key ->
        read("ctx:$key") ?: run {
            // Точной привычки нет — ищем похожую («en+ru» подходит к «en+ru+uk»).
            val stored = prefs.all.keys.filter { it.startsWith("ctx:") }
                .mapNotNull { k -> read(k)?.let { k.removePrefix("ctx:") to it } }.toMap()
            TrackRules.bestContext(stored, key.split('+').toSet())
        }
    }

    fun remember(seriesKey: String?, contextKey: String?, choice: TrackChoice) {
        val edit = prefs.edit()
        seriesKey?.let { edit.putString("series:$it", write(choice)) }
        contextKey?.let { edit.putString("ctx:$it", write(choice)) }
        edit.apply()
    }

    fun clear() = prefs.edit().clear().apply()

    private fun read(key: String): TrackChoice? = prefs.getString(key, null)?.let { raw ->
        runCatching {
            val j = JSONObject(raw)
            TrackChoice(
                audioLanguage = j.optString("a").ifEmpty { null },
                audioHint = j.optString("ah").ifEmpty { null },
                textLanguage = j.optString("t").ifEmpty { null },
                textForced = j.optBoolean("tf"),
            )
        }.getOrNull()
    }

    private fun write(c: TrackChoice): String = JSONObject()
        .put("a", c.audioLanguage.orEmpty())
        .put("ah", c.audioHint.orEmpty())
        .put("t", c.textLanguage.orEmpty())
        .put("tf", c.textForced)
        .toString()

    companion object {
        @Volatile private var instance: TrackPreferences? = null
        fun get(context: Context): TrackPreferences =
            instance ?: synchronized(this) { instance ?: TrackPreferences(context).also { instance = it } }
    }
}
