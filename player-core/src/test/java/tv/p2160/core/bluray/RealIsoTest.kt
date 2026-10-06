package tv.p2160.core.bluray

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Проверка на настоящем незашифрованном ISO с NAS. Читаются только метаданные и начало m2ts.
 * Пропускается, если файла нет.
 */
class RealIsoTest {

    private val iso = File(System.getenv("BLURAY_TEST_ISO") ?: "")

    @Test
    fun aquamanIso() {
        assumeTrue("ISO not available: $iso", iso.isFile)
        val started = System.currentTimeMillis()
        val source = CachedRandomAccessSource(FileRandomAccessSource(iso), maxBlocks = 256)
        UdfFileSystem(source).use { fs ->
            val bdmv = fs.list("BDMV")!!.map { it.name }
            assertTrue(bdmv.toString(), "PLAYLIST" in bdmv && "STREAM" in bdmv)
            val streams = fs.list("BDMV/STREAM")!!
            assertTrue(streams.any { it.name.endsWith(".m2ts", ignoreCase = true) })

            val disc = BlurayDisc.open(fs)
            val main = disc.mainTitle()
            assertNotNull(main)
            main!!

            val out = StringBuilder()
            out.append("Volume: ${fs.volumeId}, disc title: ${disc.discTitle}, obfuscated: ${disc.looksObfuscated}\n")
            out.append("Playlists: ${disc.titles.size}; >= 10 min:\n")
            disc.titles.filter { it.durationMs >= BlurayDisc.MIN_MAIN_MS }.forEach {
                out.append("  ${it.playlistName}  ${formatMs(it.durationMs)}  clips=${it.items.size} chapters=${it.chapters.size}\n")
            }
            out.append("Main title:\n").append(main.describe())

            val minutes = main.durationMs / 60_000
            assertTrue("duration $minutes min", minutes in 135..150)
            assertTrue(main.chapters.size > 5)
            assertTrue(main.audioStreams.isNotEmpty())
            assertTrue(main.audioStreams.all { it.language != null })

            // m2ts: TP_extra_header 4 байта, затем 0x47 каждые 192 байта.
            val clip = main.items.first()
            val extents = fs.extents(clip.m2tsPath)!!
            val size = fs.stat(clip.m2tsPath)!!.size
            out.append("Main m2ts ${clip.m2tsPath}: ${size / (1 shl 20)} MiB in ${extents.size} extents\n")
            assertEquals(size, extents.sumOf { it.length })
            fs.open(clip.m2tsPath).use { m2ts ->
                val head = m2ts.readBytes(0, 192 * 32)
                for (i in 0 until 32) assertEquals("packet $i", 0x47, head[i * 192 + 4].toInt() and 0xFF)
                // Сразу за границей первого экстента — тоже пакеты (проверка склейки экстентов).
                if (extents.size > 1) {
                    val boundary = extents[0].length / 192 * 192
                    val pkt = m2ts.readBytes(boundary + 192, 192)
                    assertEquals(0x47, pkt[4].toInt() and 0xFF)
                }

                // EP_map: точка входа в середине фильма попадает на границу пакета.
                val info = disc.clipInfo(clip.clipName)
                assertNotNull(info)
                val ep = info!!.videoEpMap
                if (ep != null) {
                    val mid = clip.inTime45k + clip.duration45k / 2
                    val offset = ep.byteOffsetAt(mid)
                    val pkt = m2ts.readBytes(offset, 192)
                    assertEquals(0x47, pkt[4].toInt() and 0xFF)
                    out.append("CLPI ${clip.clipName}: ${info.streams.size} streams, EP_map ${ep.pts45k.size} points; mid ${formatMs(ticksToMs(clip.duration45k / 2))} -> byte $offset\n")
                }
            }
            out.append("Elapsed: ${System.currentTimeMillis() - started} ms\n")
            println(out)
        }
    }
}
