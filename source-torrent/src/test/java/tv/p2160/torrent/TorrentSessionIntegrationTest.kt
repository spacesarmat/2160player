package tv.p2160.torrent

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files

/**
 * Интеграционный тест с настоящей сетью и нативным libtorrent (Windows x64).
 * Запуск: `P2160_TORRENT_IT=1 ./gradlew :source-torrent:testDebugUnitTest --tests '*Integration*'`.
 *
 * Big Buck Bunny (CC BY 3.0, Blender Foundation) — торрент WebTorrent с веб-сидом.
 */
class TorrentSessionIntegrationTest {

    @Test
    fun fetchesMetadataAndStreamsHeadSequentially() {
        assumeTrue("set P2160_TORRENT_IT=1 to run", System.getenv("P2160_TORRENT_IT") == "1")
        assumeTrue("Windows x64 natives only", System.getProperty("os.name").orEmpty().lowercase().contains("windows"))
        loadNatives()

        val dir = Files.createTempDirectory("p2160-torrent-it").toFile()
        var metadataBytes: ByteArray? = null
        var resumeBytes: ByteArray? = null
        val session = TorrentSession(SessionConfig(), object : TorrentSession.Callbacks {
            override fun onMetadata(id: String, torrent: ByteArray?) { metadataBytes = torrent }
            override fun onResumeData(id: String, data: ByteArray) { resumeBytes = data }
        })
        try {
            session.start()
            val t0 = System.currentTimeMillis()
            var id = session.addMagnet(MAGNET, dir)
            assertEquals("dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c", id)
            var viaMagnet = session.awaitMetadata(id, 120_000)
            val metaMs = System.currentTimeMillis() - t0
            println("IT: metadata via magnet: $viaMagnet in ${metaMs} ms, dht nodes=${session.dhtNodes()}")
            if (!viaMagnet) {
                // Запасной путь: .torrent по HTTP (как addFromUri в приложении).
                session.remove(id, deleteFiles = true)
                id = session.addTorrentFile(httpGet(TORRENT_URL), dir)
                assertTrue(session.awaitMetadata(id, 10_000))
            }
            val files = session.files(id)
            assertNotNull(files)
            println("IT: files = " + files!!.joinToString { "${it.path} (${it.size})" })
            val video = files.filter { it.isVideo }.maxBy { it.size }
            session.select(id, video.index)

            session.openStream(id, video.index).use { stream ->
                val buf = ByteArray(256 * 1024)
                val want = 4L * 1024 * 1024
                var pos = 0L
                val s0 = System.currentTimeMillis()
                val head = ByteArray(16)
                while (pos < want) {
                    val n = stream.read(pos, buf, 0, buf.size, stallTimeoutMs = 120_000)
                    assertTrue(n > 0)
                    if (pos == 0L) System.arraycopy(buf, 0, head, 0, 16)
                    pos += n
                }
                val ms = System.currentTimeMillis() - s0
                println("IT: read first ${pos / 1024} KiB in $ms ms (${pos * 1000 / maxOf(ms, 1) / 1024} KiB/s); " + session.stats(id))
                // MP4: размер бокса + "ftyp"
                assertArrayEquals("ftyp".toByteArray(), head.copyOfRange(4, 8))

                // Перемотка в середину: дедлайны должны переехать к новой позиции.
                val mid = video.size / 2
                val m0 = System.currentTimeMillis()
                val n = stream.read(mid, buf, 0, 64 * 1024, stallTimeoutMs = 120_000)
                assertTrue(n > 0)
                println("IT: seek to 50% served in ${System.currentTimeMillis() - m0} ms")
                assertTrue(stream.isAvailable(mid, n.toLong()))
            }
            session.requestResumeData(id)
            Thread.sleep(2_000)
            println("IT: torrent bytes from metadata=${metadataBytes?.size}, resume bytes=${resumeBytes?.size}, via magnet=$viaMagnet")
        } finally {
            session.close()
            dir.deleteRecursively()
        }
    }

    private fun loadNatives() {
        val res = javaClass.classLoader!!.getResource("lib/x86_64/libtorrent4j.dll") ?: error("libtorrent4j-windows not on classpath")
        val out = File(System.getProperty("java.io.tmpdir"), "p2160-libtorrent4j-${res.toString().hashCode()}.dll")
        if (!out.exists()) res.openStream().use { input -> out.outputStream().use { input.copyTo(it) } }
        System.setProperty("libtorrent4j.jni.path", out.absolutePath)
    }

    private fun httpGet(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        return c.inputStream.use { it.readBytes() }
    }

    companion object {
        const val MAGNET = "magnet:?xt=urn:btih:dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c&dn=Big+Buck+Bunny" +
            "&tr=udp%3A%2F%2Fexplodie.org%3A6969&tr=udp%3A%2F%2Ftracker.coppersurfer.tk%3A6969" +
            "&tr=udp%3A%2F%2Ftracker.empire-js.us%3A1337&tr=udp%3A%2F%2Ftracker.leechers-paradise.org%3A6969" +
            "&tr=udp%3A%2F%2Ftracker.opentrackr.org%3A1337&tr=wss%3A%2F%2Ftracker.btorrent.xyz" +
            "&tr=wss%3A%2F%2Ftracker.fastcast.nz&tr=wss%3A%2F%2Ftracker.openwebtorrent.com" +
            "&ws=https%3A%2F%2Fwebtorrent.io%2Ftorrents%2F&xs=https%3A%2F%2Fwebtorrent.io%2Ftorrents%2Fbig-buck-bunny.torrent"
        const val TORRENT_URL = "https://webtorrent.io/torrents/big-buck-bunny.torrent"
    }
}
