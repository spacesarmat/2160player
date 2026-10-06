package tv.p2160.core.i18n

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** Языковой пакет: встроенный (assets) или пользовательский (импортированный файл). */
data class LanguagePack(
    val code: String,
    val name: String,
    val author: String?,
    val builtIn: Boolean,
)

/** Набор строк выбранного языка с цепочкой отката: пакет → английский → ключ. */
class Strings internal constructor(
    val locale: Locale,
    private val primary: Map<String, String>,
    private val fallback: Map<String, String>,
) {
    operator fun get(key: String): String = primary[key] ?: fallback[key] ?: key

    fun format(key: String, vararg args: Any?): String =
        runCatching { String.format(locale, get(key), *args) }.getOrDefault(get(key))

    companion object {
        val Empty = Strings(Locale.ENGLISH, emptyMap(), emptyMap())
    }
}

val LocalStrings = staticCompositionLocalOf { Strings.Empty }

/** Строка по ключу в Compose: `tr("player.back")`, `tr("player.resumed", time)`. */
@Composable
@ReadOnlyComposable
fun tr(key: String, vararg args: Any?): String =
    if (args.isEmpty()) LocalStrings.current[key] else LocalStrings.current.format(key, *args)

/**
 * Локализация на JSON-пакетах.
 *
 * Встроенные пакеты: `assets/i18n/<модуль>/<код>.json` — каждый модуль (ядро, приложение)
 * кладёт свой файл, при загрузке они объединяются. Пользовательские пакеты лежат в
 * `filesDir/i18n/<код>.json` и перекрывают встроенные — так можно и добавить новый язык,
 * и поправить существующий перевод.
 *
 * Формат файла:
 * ```json
 * { "_meta": { "code": "uk", "name": "Українська", "author": "Имя" },
 *   "player.back": "Назад", "player.resumed": "Продовжено з %1$s" }
 * ```
 */
class I18n private constructor(private val context: Context) {

    private val userDir = File(context.filesDir, "i18n").apply { mkdirs() }

    private val _strings = MutableStateFlow(Strings.Empty)
    val strings: StateFlow<Strings> = _strings.asStateFlow()

    /** Текущие строки (для кода вне Compose). */
    val current: Strings get() = _strings.value

    private var selected: String = SYSTEM

    init {
        // Строки доступны сразу, даже если PlayerSettings ещё не создавался.
        apply(context.getSharedPreferences("p2160_settings", Context.MODE_PRIVATE).getString("language", SYSTEM) ?: SYSTEM)
    }

    fun available(): List<LanguagePack> {
        val builtIn = builtInCodes().map { code ->
            val meta = readMeta(loadBuiltIn(code))
            LanguagePack(code, meta?.optString("name") ?: code, meta?.optString("author"), builtIn = true)
        }
        val user = userDir.listFiles { f -> f.extension == "json" }.orEmpty().mapNotNull { file ->
            val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return@mapNotNull null
            val meta = readMeta(listOf(json))
            LanguagePack(file.nameWithoutExtension, meta?.optString("name") ?: file.nameWithoutExtension, meta?.optString("author"), builtIn = false)
        }
        return (user + builtIn).distinctBy { it.code }.sortedBy { it.name.lowercase() }
    }

    /** Применяет язык: код пакета или [SYSTEM]. */
    fun apply(code: String) {
        selected = code
        val available = available().map { it.code }.toSet()
        val resolved = when {
            code != SYSTEM && code in available -> code
            else -> Locale.getDefault().language.takeIf { it in available } ?: FALLBACK
        }
        val fallback = flatten(loadBuiltIn(FALLBACK))
        val primary = flatten(loadBuiltIn(resolved)) + flatten(listOfNotNull(loadUser(resolved)))
        _strings.value = Strings(Locale.forLanguageTag(resolved), primary, fallback)
    }

    /**
     * Импорт пользовательского пакета. Возвращает установленный пакет или бросает
     * [IllegalArgumentException] с понятным описанием проблемы.
     */
    fun import(uri: Uri): LanguagePack {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IllegalArgumentException("cannot read file")
        val json = runCatching { JSONObject(text) }.getOrElse { throw IllegalArgumentException("invalid JSON: ${it.message}") }
        val meta = json.optJSONObject(META) ?: throw IllegalArgumentException("missing \"_meta\" section")
        val code = meta.optString("code").trim().lowercase()
        require(code.matches(Regex("[a-z]{2,3}(-[a-z0-9]{2,8})?"))) { "invalid language code: \"$code\"" }
        File(userDir, "$code.json").writeText(json.toString(2))
        if (selected == code) apply(code)
        return LanguagePack(code, meta.optString("name", code), meta.optString("author"), builtIn = false)
    }

    fun removeUserPack(code: String) {
        File(userDir, "$code.json").delete()
        apply(selected)
    }

    /** Шаблон для переводчика: все ключи с английскими строками и текущим переводом в комментарии. */
    fun exportTemplate(baseCode: String = FALLBACK): String {
        val base = flatten(loadBuiltIn(baseCode)).toSortedMap()
        val json = JSONObject()
        json.put(META, JSONObject().put("code", "xx").put("name", "Language name").put("author", "Your name").put("base", baseCode))
        base.forEach { (k, v) -> json.put(k, v) }
        return json.toString(2)
    }

    private fun builtInCodes(): Set<String> =
        context.assets.list(ASSET_ROOT).orEmpty().flatMap { module ->
            context.assets.list("$ASSET_ROOT/$module").orEmpty()
                .filter { it.endsWith(".json") }.map { it.removeSuffix(".json") }
        }.toSet()

    private fun loadBuiltIn(code: String): List<JSONObject> =
        context.assets.list(ASSET_ROOT).orEmpty().mapNotNull { module ->
            runCatching {
                context.assets.open("$ASSET_ROOT/$module/$code.json").use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
            }.getOrNull()
        }

    private fun loadUser(code: String): JSONObject? =
        File(userDir, "$code.json").takeIf { it.exists() }?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }

    private fun readMeta(files: List<JSONObject>): JSONObject? = files.firstNotNullOfOrNull { it.optJSONObject(META) }

    private fun flatten(files: List<JSONObject>): Map<String, String> = buildMap {
        files.forEach { json ->
            json.keys().forEach { key -> if (key != META) json.optString(key).takeIf { it.isNotEmpty() }?.let { put(key, it) } }
        }
    }

    companion object {
        const val SYSTEM = "system"
        const val FALLBACK = "en"
        private const val META = "_meta"
        private const val ASSET_ROOT = "i18n"

        @Volatile private var instance: I18n? = null

        fun get(context: Context): I18n = instance ?: synchronized(this) {
            instance ?: I18n(context.applicationContext).also { instance = it }
        }
    }
}
