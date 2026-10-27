package tv.p2160.app

import java.util.Locale

/**
 * Коды языков для «предпочитать субтитры / озвучку». Порядок в списке - порядок предпочтения.
 * Свой код по-прежнему можно вписать: не все языки есть в коротком списке.
 */
object LanguageCodes {
    val common: List<String> = listOf(
        "ru", "en", "uk", "be", "kk", "de", "fr", "es", "it", "pt", "pl", "tr",
        "zh", "ja", "ko", "ar", "hi", "nl", "sv", "cs", "ro", "hu", "el", "he", "th", "vi",
    )

    fun displayName(code: String, locale: Locale = Locale.getDefault()): String {
        val loc = Locale.forLanguageTag(code.replace('_', '-'))
        val name = loc.getDisplayLanguage(locale).trim()
        if (name.isEmpty() || name.equals(code, ignoreCase = true)) return code
        return name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
    }

    /** «ru, en», «ru en» и «ru;en» → `["ru", "en"]`. Слова вроде «english» и обрезки не код. */
    fun parse(raw: String): List<String> =
        raw.split(',', ' ', ';', '\n')
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { CODE.matches(it) }
            .distinct()

    private val CODE = Regex("^[a-z]{2,3}(-[a-z]{2,4})?$")
}
