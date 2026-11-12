package tv.p2160.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.p2160.core.iptv.M3uParser
import tv.p2160.desktop.media.SmbPaths
import tv.p2160.desktop.media.playlistOpensAsStream

class SourceAdapterTest {
    @Test
    fun smbChildDoesNotDoubleTheSeparator() {
        assertEquals("movie.mkv", SmbPaths.child("", "movie.mkv"))
        assertEquals("films\\a.mkv", SmbPaths.child("films", "a.mkv"))
        assertEquals("films\\a.mkv", SmbPaths.child("films/", "a.mkv"))
        assertEquals("films\\a.mkv", SmbPaths.child("films\\", "a.mkv"))
    }

    @Test
    fun hlsManifestIsOneStreamAndAChannelListIsNot() {
        val hls = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nhttp://example/a.m3u8\n"
        assertTrue(playlistOpensAsStream(hls, "http://example/master.m3u8"))
        val iptv = "#EXTM3U\n#EXTINF:-1 tvg-id=\"ch1\" group-title=\"Новости\",Первый\nhttp://example/1.m3u8\n"
        assertFalse(playlistOpensAsStream(iptv, "http://example/list.m3u"))
        val parsed = M3uParser.parse(iptv, "http://example/list.m3u")
        assertEquals("Первый", parsed.channels[0].name)
        assertEquals("http://example/1.m3u8", parsed.channels[0].url)
        assertEquals("Новости", parsed.channels[0].group)
    }
}
