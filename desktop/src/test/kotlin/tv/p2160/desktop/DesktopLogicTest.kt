package tv.p2160.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.p2160.core.iptv.M3uParser
import tv.p2160.desktop.media.formatClock
import tv.p2160.desktop.media.formatRemaining
import tv.p2160.desktop.media.parseRange
import tv.p2160.desktop.media.shouldOfferResume
import tv.p2160.torrent.FileSpan
import tv.p2160.torrent.PiecePlanner
import java.io.File
import java.nio.file.Files

class DesktopLogicTest {
    @Test
    fun clockAndRemaining() {
        assertEquals("1:02", formatClock(62.0))
        assertEquals("1:02:03", formatClock(3723.0))
        assertEquals("--:--", formatClock(-1.0))
        assertEquals("-5:00", formatRemaining(0.0, 300.0))
    }

    @Test
    fun resumePolicyMatchesWatchedTail() {
        assertFalse(shouldOfferResume(3.0, 100.0))
        assertTrue(shouldOfferResume(40.0, 200.0))
        assertFalse(shouldOfferResume(980.0, 1000.0))
        assertFalse(shouldOfferResume(580.0, 600.0))
        assertTrue(shouldOfferResume(400.0, 600.0))
    }

    @Test
    fun httpRange() {
        assertEquals(0L to 100L, parseRange(null, 100))
        assertEquals(10L to 21L, parseRange("bytes=10-20", 100))
        assertEquals(10L to 100L, parseRange("bytes=10-", 100))
        assertEquals(90L to 100L, parseRange("bytes=-10", 100))
        assertEquals(null, parseRange("bytes=100-200", 100))
    }

    @Test
    fun m3uParserFromSharedSource() {
        val text = "#EXTM3U\n#EXTINF:-1 tvg-id=\"ch1\" group-title=\"Новости\",Первый\nhttp://example/1.m3u8\n"
        val playlist = M3uParser.parse(text, "http://example/list.m3u")
        assertEquals(1, playlist.channels.size)
        assertEquals("Первый", playlist.channels[0].name)
        assertEquals("http://example/1.m3u8", playlist.channels[0].url)
        assertEquals("Новости", playlist.channels[0].group)
    }

    @Test
    fun piecePlannerHeadIsSharedWithAndroid() {
        val span = FileSpan(offset = 0, size = 10_000_000, pieceLength = 1_048_576)
        val head = PiecePlanner.headPieces(span)
        assertTrue(head.first == 0)
        assertTrue(head.last >= 3)
        val deadlines = PiecePlanner.deadlines(span, emptyList(), wantHead = true, wantTail = true) { false }
        assertTrue(deadlines.isNotEmpty())
    }

    @Test
    fun rangeServerServesASlice() {
        val bytes = ByteArray(1000) { it.toByte() }
        val server = tv.p2160.desktop.media.RangeServer()
        try {
            val url = server.publish(object : tv.p2160.desktop.media.ByteSource {
                override val length = bytes.size.toLong()
                override fun read(offset: Long, dst: ByteArray, dstOff: Int, len: Int): Int {
                    if (offset >= length) return -1
                    val n = minOf(len.toLong(), length - offset).toInt()
                    System.arraycopy(bytes, offset.toInt(), dst, dstOff, n)
                    return n
                }
                override fun close() = Unit
            })
            val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            connection.setRequestProperty("Range", "bytes=4-7")
            assertEquals(206, connection.responseCode)
            val body = connection.inputStream.readBytes()
            assertEquals(4, body.size)
            assertEquals(4.toByte(), body[0])
            assertEquals(7.toByte(), body[3])
        } finally {
            server.close()
        }
    }

    @Test
    fun libraryRoundTrip() {
        val dir = Files.createTempDirectory("p2160-lib").toFile()
        val store = tv.p2160.desktop.media.LibraryStore(dir)
        store.rememberRecent(tv.p2160.desktop.media.RecentItem("file", "a.mkv", "/a.mkv", "/a.mkv"))
        store.rememberPosition("/a.mkv", 12.5)
        store.rememberVolume(40)
        val again = tv.p2160.desktop.media.LibraryStore(dir)
        assertEquals(40, again.volume)
        assertEquals(12.5, again.position("/a.mkv")!!, 0.01)
        assertEquals("a.mkv", again.recent().first().title)
        dir.deleteRecursively()
    }
}
