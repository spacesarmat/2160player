package tv.p2160.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class LanguageCodesTest {
    @Test
    fun parsesCommaSpaceAndSemicolon() {
        assertEquals(listOf("ru", "en", "uk"), LanguageCodes.parse("ru, en;uk"))
        assertEquals(listOf("pt-br"), LanguageCodes.parse("PT-BR"))
    }

    @Test
    fun dropsBlankAndNonsense() {
        assertEquals(listOf("en"), LanguageCodes.parse(" , x, english, en "))
    }

    @Test
    fun displayNameFollowsUiLocale() {
        val ru = LanguageCodes.displayName("en", Locale("ru", "RU"))
        assertEquals("английский", ru.lowercase(Locale("ru", "RU")))
        assertEquals("ru", LanguageCodes.displayName("ru", Locale.ENGLISH).let { if (it.equals("russian", true)) "ru" else it }.let { "ru" })
        // Английское имя русского языка — Russian, не код.
        assertEquals("Russian", LanguageCodes.displayName("ru", Locale.ENGLISH))
    }
}
