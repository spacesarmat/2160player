package tv.p2160.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.p2160.desktop.media.TorrentNatives
import tv.p2160.torrent.FileSpan
import tv.p2160.torrent.PiecePlanner
import tv.p2160.torrent.SessionConfig
import tv.p2160.torrent.TorrentSession
import java.io.File

class TorrentNativeTest {
    @Test
    fun resourcePathMatchesThePlatformJars() {
        assertEquals("lib/x86_64/libtorrent4j.so", TorrentNatives.resourcePath("Linux", "amd64"))
        assertEquals("lib/arm/libtorrent4j.so", TorrentNatives.resourcePath("Linux", "arm"))
        assertEquals("lib/arm64/libtorrent4j.dylib", TorrentNatives.resourcePath("Mac OS X", "aarch64"))
        assertEquals("lib/x86_64/libtorrent4j.dll", TorrentNatives.resourcePath("Windows 11", "amd64"))
    }

    @Test
    fun pieceWindowIsNotEmpty() {
        val span = FileSpan(0, 10_000_000, 1_048_576)
        val deadlines = PiecePlanner.deadlines(span, emptyList(), wantHead = true, wantTail = true) { false }
        assertTrue(deadlines.isNotEmpty())
    }

    @Test
    fun ensureLoadedStartsASession() {
        TorrentNatives.ensureLoaded()
        val path = System.getProperty("libtorrent4j.jni.path")
        assertTrue(path != null && File(path).isFile && File(path).name.startsWith("libtorrent4j"))
        val session = TorrentSession(SessionConfig(userAgent = "2160Player/0.2.26 libtorrent/2.0"))
        try {
            session.start()
            assertTrue(session.isRunning)
        } finally {
            session.close()
        }
    }
}
