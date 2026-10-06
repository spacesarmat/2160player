package tv.p2160.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.p2160.core.resume.ResumeStore
import tv.p2160.core.subtitle.SubtitleSupport
import java.nio.charset.Charset

class SubtitleAndResumeTest {

    private val srt = "1\n00:00:01,000 --> 00:00:02,000\nПривет, мир!\n"

    @Test
    fun utf8IsLeftAsIs() {
        assertNull(SubtitleSupport.decode(srt.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun utf8WithBomIsLeftAsIs() {
        assertNull(SubtitleSupport.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + srt.toByteArray()))
    }

    @Test
    fun cp1251IsConvertedToText() {
        val bytes = srt.toByteArray(Charset.forName("windows-1251"))
        assertEquals(srt, SubtitleSupport.decode(bytes))
    }

    @Test
    fun subtitleMimeAndLanguageFromName() {
        assertEquals("application/x-subrip", SubtitleSupport.mimeForName("Movie.ru.srt"))
        assertEquals("text/x-ssa", SubtitleSupport.mimeForName("Movie.ASS"))
        assertNull(SubtitleSupport.mimeForName("Movie.mkv"))
        assertEquals("ru", SubtitleSupport.languageFromName("Movie.2020.ru.srt"))
        assertEquals("eng", SubtitleSupport.languageFromName("Movie.eng.forced.ass"))
        assertNull(SubtitleSupport.languageFromName("Movie.srt"))
    }

    @Test
    fun finishedDetection() {
        val hour = 3_600_000L
        assertFalse(ResumeStore.isFinished(hour / 2, hour))
        assertTrue(ResumeStore.isFinished(hour - 20_000, hour))
        assertTrue(ResumeStore.isFinished((hour * 0.98).toLong(), hour))
        assertFalse(ResumeStore.isFinished(10_000, 0))
    }
}
