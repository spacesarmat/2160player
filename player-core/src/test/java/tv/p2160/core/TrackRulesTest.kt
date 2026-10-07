package tv.p2160.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.p2160.core.engine.TrackCandidate
import tv.p2160.core.engine.TrackChoice
import tv.p2160.core.engine.TrackRules

class TrackRulesTest {

    private val movie = listOf(
        TrackCandidate(0, "rus", "MVO", 2),
        TrackCandidate(1, "rus", "Dub [License]", 6),
        TrackCandidate(2, "eng", "Original", 6),
        TrackCandidate(3, "eng", "Commentary", 2),
    )

    @Test
    fun contextKeyIsSortedNormalizedLanguages() {
        assertEquals("en+ru", TrackRules.contextKey(movie))
        assertEquals("ja+ru", TrackRules.contextKey(listOf(TrackCandidate(0, "ru", null), TrackCandidate(1, "jpn", null))))
        assertNull(TrackRules.contextKey(listOf(TrackCandidate(0, "und", null))))
    }

    @Test
    fun fuzzyContextMatch() {
        val stored = mapOf(
            "en+ru" to TrackChoice("en", "original", "ru"),
            "ja+ru" to TrackChoice("ja", null, "ru"),
        )
        assertEquals("en", TrackRules.bestContext(stored, setOf("en", "ru", "uk"))?.audioLanguage)
        assertEquals("ja", TrackRules.bestContext(stored, setOf("ja", "ru"))?.audioLanguage)
        assertNull(TrackRules.bestContext(stored, setOf("fr", "de")))
        // Нужный язык озвучки отсутствует — привычка не подходит.
        assertNull(TrackRules.bestContext(mapOf("en+ru" to TrackChoice("en", null, null)), setOf("ru", "uk")))
    }

    @Test
    fun hints() {
        assertEquals("dub", TrackRules.hintOf("Dub [License]"))
        assertEquals("dub", TrackRules.hintOf("Дублированный"))
        assertEquals("mvo", TrackRules.hintOf("MVO (НТВ)"))
        assertEquals("original", TrackRules.hintOf("Original"))
        assertEquals("commentary", TrackRules.hintOf("Director's commentary"))
        assertNull(TrackRules.hintOf("Stereo"))
    }

    @Test
    fun picksSameDubTypeAndAvoidsCommentary() {
        assertEquals(1, TrackRules.pickAudio(TrackChoice("ru", "dub", null), movie)?.index)
        assertEquals(0, TrackRules.pickAudio(TrackChoice("ru", "mvo", null), movie)?.index)
        // Без подсказки: не комментарий, больше каналов.
        assertEquals(2, TrackRules.pickAudio(TrackChoice("en", null, null), movie)?.index)
        assertNull(TrackRules.pickAudio(TrackChoice("ja", null, null), movie))
    }

    @Test
    fun picksSubtitlesPreferringForcedFlag() {
        val subs = listOf(TrackCandidate(0, "rus", "Forced", forced = true), TrackCandidate(1, "rus", "Full"), TrackCandidate(2, "eng", null))
        assertEquals(1, TrackRules.pickText(TrackChoice("ja", null, "ru", textForced = false), subs)?.index)
        assertEquals(0, TrackRules.pickText(TrackChoice("ja", null, "ru", textForced = true), subs)?.index)
        assertNull(TrackRules.pickText(TrackChoice("ru", null, null), subs))
    }
}
