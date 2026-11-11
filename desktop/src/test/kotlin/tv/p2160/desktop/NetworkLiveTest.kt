package tv.p2160.desktop

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import tv.p2160.core.source.dlna.ContentDirectory
import tv.p2160.core.source.dlna.DlnaProbe
import tv.p2160.desktop.media.SmbBrowser
import tv.p2160.desktop.media.SmbSaved

/** Локальные серверы. В CI не запускается: P2160_NET_IT=1. */
class NetworkLiveTest {
    @Test
    fun smbListsTheSampleFile() {
        assumeTrue(System.getenv("P2160_NET_IT") == "1")
        val browser = SmbBrowser()
        try {
            val server = SmbSaved("127.0.0.1")
            val files = browser.list(server, "share", "")
            assertTrue(files.toString(), files.any { it.name.endsWith(".mp4") && !it.directory })
        } finally {
            browser.close()
        }
    }

    @Test
    fun dlnaProbeReadsTheRoot() {
        assumeTrue(System.getenv("P2160_NET_IT") == "1")
        val server = DlnaProbe.resolve("127.0.0.1:8200")
        assertNotNull(server)
        val objects = ContentDirectory(server!!).browseAll("0")
        assertTrue(objects.toString(), objects.isNotEmpty())
    }
}
