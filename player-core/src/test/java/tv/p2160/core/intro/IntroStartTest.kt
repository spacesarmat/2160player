package tv.p2160.core.intro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntroStartTest {
    @Test
    fun startsOnlyAfterReadyAndStopsOnErrorOrCap() {
        assertFalse(IntroStart.allowed(ready = false, hasError = false, attempts = 0))
        assertFalse(IntroStart.allowed(ready = true, hasError = true, attempts = 0))
        assertTrue(IntroStart.allowed(ready = true, hasError = false, attempts = 0))
        assertTrue(IntroStart.allowed(ready = true, hasError = false, attempts = 2))
        assertFalse(IntroStart.allowed(ready = true, hasError = false, attempts = IntroStart.MAX_ATTEMPTS))
    }

    @Test
    fun backoffGrowsAndDeadSourceIsNotRetriedForever() {
        assertTrue(IntroStart.delayMs(1) > IntroStart.delayMs(0))
        assertTrue(IntroStart.delayMs(2) > IntroStart.delayMs(1))
        assertTrue(IntroStart.retryAfter(sourceOpened = false, attempts = 1))
        assertFalse(IntroStart.retryAfter(sourceOpened = true, attempts = 1))
        assertFalse(IntroStart.retryAfter(sourceOpened = false, attempts = IntroStart.MAX_ATTEMPTS))
    }

    @Test
    fun httpExtractorOpensOnlyAfterTheHostAnswers() {
        assertTrue(IntroStart.openExtractor("file", hostAnswered = null))
        assertTrue(IntroStart.openExtractor("smb", hostAnswered = false))
        assertFalse(IntroStart.openExtractor("http", hostAnswered = false))
        assertFalse(IntroStart.openExtractor("https", hostAnswered = null))
        assertTrue(IntroStart.openExtractor("https", hostAnswered = true))
    }
}
