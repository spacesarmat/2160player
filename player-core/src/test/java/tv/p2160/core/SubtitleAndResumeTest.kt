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
    fun sidecarStemRequiresDotBoundary() {
        assertTrue(SubtitleSupport.matchesStem("Movie", "Movie.srt"))
        assertTrue(SubtitleSupport.matchesStem("Movie", "Movie.ru.srt"))
        assertFalse(SubtitleSupport.matchesStem("Movie", "Movie2.srt"))
        assertFalse(SubtitleSupport.matchesStem("Show.S01E1", "Show.S01E10.srt"))
        assertTrue(SubtitleSupport.matchesStem("Show.S01E01", "Show.S01E01.en.ass"))
        assertFalse(SubtitleSupport.matchesStem("", "Movie.srt"))
    }

    @Test
    fun octetStreamDoesNotHideSrtExtension() {
        val header = "1\n00:00:01,000 --> 00:00:02,000\nHi\n".toByteArray()
        assertEquals(
            "application/x-subrip",
            SubtitleSupport.resolveMime("Tears of Steel.ru.srt", "application/octet-stream", header),
        )
        assertEquals("application/x-subrip", SubtitleSupport.mimeFromReported("application/x-subrip"))
        assertNull(SubtitleSupport.mimeFromReported("application/octet-stream"))
        assertNull(SubtitleSupport.mimeFromReported("text/plain"))
    }

    @Test
    fun sniffPicksFormatWhenNameHasNoExtension() {
        val srt = "1\r\n00:00:01,000 --> 00:00:02,000\r\nHi\r\n".toByteArray()
        val vtt = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHi\n".toByteArray()
        val ass = "[Script Info]\nScriptType: v4.00+\n".toByteArray()
        val ttml = "<?xml version=\"1.0\"?><tt xmlns=\"http://www.w3.org/ns/ttml\">".toByteArray()
        assertEquals("application/x-subrip", SubtitleSupport.resolveMime("msf:100", "application/octet-stream", srt))
        assertEquals("text/vtt", SubtitleSupport.resolveMime(null, "text/plain", vtt))
        assertEquals("text/x-ssa", SubtitleSupport.sniffMime(ass))
        assertEquals("application/ttml+xml", SubtitleSupport.sniffMime(ttml))
        assertEquals("text/vtt", SubtitleSupport.resolveMime("clip.vtt", "application/octet-stream", srt))
    }

    @Test
    fun toUnicodeStripsBomAndDecodesCp1251AndUtf16() {
        assertEquals(srt, SubtitleSupport.toUnicode(srt.toByteArray(Charsets.UTF_8)))
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + srt.toByteArray()
        assertEquals(srt, SubtitleSupport.toUnicode(bom))
        assertEquals(srt, SubtitleSupport.toUnicode(srt.toByteArray(Charset.forName("windows-1251"))))
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + srt.toByteArray(Charsets.UTF_16LE)
        assertEquals(srt, SubtitleSupport.toUnicode(utf16))
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
        // Короче 30 с: «осталось меньше 30 с» не помечает ролик досмотренным с начала.
        assertFalse(ResumeStore.isFinished(0, 20_000))
        assertFalse(ResumeStore.isFinished(5_000, 20_000))
        assertTrue(ResumeStore.isFinished(19_500, 20_000))
        // Длиннее 30 с: остаток меньше 30 с - досмотрено, даже если это ещё не 97 %.
        assertFalse(ResumeStore.isFinished(0, 40_000))
        assertTrue(ResumeStore.isFinished(15_000, 40_000))
    }

    @Test
    fun httpResumeKeyKeepsPortAndBracketsIpv6() {
        assertEquals(
            "http://nas.local/Movies/a.mkv",
            ResumeStore.networkKey("http", "nas.local", -1, "/Movies/a.mkv"),
        )
        assertEquals(
            "http://nas.local/Movies/a.mkv",
            ResumeStore.networkKey("http", "nas.local", 80, "/Movies/a.mkv"),
        )
        assertEquals(
            "http://192.168.1.10:8096/Videos/a.mkv",
            ResumeStore.networkKey("http", "192.168.1.10", 8096, "/Videos/a.mkv"),
        )
        assertEquals(
            "https://example.com/a.mkv",
            ResumeStore.networkKey("https", "example.com", 443, "/a.mkv"),
        )
        assertEquals(
            "http://[2001:db8::1]:8080/a.mkv",
            ResumeStore.networkKey("http", "2001:db8::1", 8080, "/a.mkv"),
        )
        assertNull(ResumeStore.networkKey("http", null, 8080, "/a.mkv"))
    }
}
