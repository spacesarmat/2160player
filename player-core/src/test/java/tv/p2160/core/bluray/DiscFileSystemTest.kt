package tv.p2160.core.bluray

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class DiscFileSystemTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val mpls = buildMpls(
        listOf(ItemSpec("00001", 0, 45_000L * 60 * 15)),
        listOf(MarkSpec(1, 0, 0), MarkSpec(1, 0, 45_000L * 60)),
    )

    @Test
    fun udf102() = checkUdf(metadata = false)

    @Test
    fun udf250WithMetadataPartition() = checkUdf(metadata = true)

    private fun checkUdf(metadata: Boolean) {
        val builder = UdfImageBuilder(metadata, mpls)
        val fs = UdfFileSystem(ByteArraySource(builder.build()))
        assertEquals("TEST", fs.volumeId)

        val root = fs.list("")!!
        assertEquals(listOf("BDMV", "Ünïcødé файл.txt"), root.map { it.name }) // удалённый пропущен
        assertTrue(root[0].isDirectory)
        assertEquals(11L, root[1].size)
        assertEquals("hello world", String(fs.readAll("/Ünïcødé файл.txt")))

        assertEquals(listOf("PLAYLIST", "STREAM"), fs.list("bdmv")!!.map { it.name })
        assertArrayEquals(mpls, fs.readAll("BDMV/PLAYLIST/00001.MPLS"))
        assertNull(fs.list("BDMV/NOPE"))
        assertNull(fs.stat("BDMV/PLAYLIST/00001.mpls/x"))

        // Фрагментированный файл: два экстента в разных местах раздела.
        val start = UdfImageBuilder.PART_START.toLong() * UdfImageBuilder.BS
        assertEquals(
            listOf(DiscExtent(start + 100 * 2048, 4096), DiscExtent(start + 80 * 2048, 1000)),
            fs.extents("BDMV/STREAM/00001.m2ts"),
        )
        val m2ts = fs.open("BDMV/STREAM/00001.m2ts")
        assertEquals(builder.m2ts.size.toLong(), m2ts.size)
        assertArrayEquals(builder.m2ts, m2ts.readBytes(0, m2ts.size.toInt()))
        // Чтение через границу экстентов.
        assertArrayEquals(builder.m2ts.copyOfRange(4000, 4200), m2ts.readBytes(4000, 200))
        assertEquals(-1, m2ts.read(m2ts.size, ByteArray(1), 0, 1))

        val disc = BlurayDisc.open(fs)
        assertEquals("BDMV", disc.bdmvPath)
        val main = disc.mainTitle()!!
        assertEquals("00001", main.playlistName)
        assertEquals(15 * 60_000L, main.durationMs)
        assertEquals(listOf(0L, 60_000L), main.chapters)
        assertEquals("eng", main.audioStreams.single().language)
        assertNull(disc.discTitle)
    }

    @Test
    fun corruptedDirectoryDoesNotHang() {
        val image = UdfImageBuilder(true, mpls).build()
        // Портим корневой каталог (метаблок 2 → физ. блок 12): огромная длина implementation use.
        val dirOff = (UdfImageBuilder.PART_START + 12) * 2048
        val second = dirOff + 40 // второй FID (после FID родителя длиной 40)
        image[second + 36] = 0xFF.toByte(); image[second + 37] = 0xFF.toByte()
        val fs = UdfFileSystem(ByteArraySource(image))
        assertEquals(emptyList<DiscEntry>(), fs.list(""))
        assertNull(fs.stat("BDMV"))
    }

    @Test
    fun garbageIsNotUdf() {
        val junk = ByteArray(600 * 2048) { (it * 31 + 7).toByte() }
        try {
            UdfFileSystem(ByteArraySource(junk)); fail()
        } catch (_: IOException) {
        }
    }

    @Test
    fun directoryFileSystemCaseInsensitive() {
        val bdmv = tmp.newFolder("disc", "BDMV")
        tmp.newFolder("disc", "BDMV", "PLAYLIST")
        tmp.newFolder("disc", "BDMV", "META", "DL")
        java.io.File(bdmv, "PLAYLIST/00800.mpls").writeBytes(mpls)
        java.io.File(bdmv, "META/DL/bdmt_eng.xml").writeText(
            "<?xml version=\"1.0\"?><disclib><di:discinfo><di:title><di:name>Tom &amp; Jerry</di:name></di:title></di:discinfo></disclib>",
        )
        val fs = DirectoryFileSystem(FileDirectoryProvider(java.io.File(tmp.root, "disc")))
        assertEquals(mpls.size.toLong(), fs.stat("bdmv/playlist/00800.MPLS")!!.size)
        assertFalse(fs.stat("bdmv/playlist/00800.MPLS")!!.isDirectory)
        val disc = BlurayDisc.open(fs)
        assertEquals("Tom & Jerry", disc.discTitle)
        assertEquals("00800", disc.mainTitle()!!.playlistName)

        // Папку BDMV можно открыть и напрямую.
        val direct = BlurayDisc.open(DirectoryFileSystem(FileDirectoryProvider(bdmv)))
        assertEquals("", direct.bdmvPath)
        assertEquals("STREAM/00001.m2ts", direct.titles.single().items.single().m2tsPath)
    }

    @Test
    fun cachedSourceAndExtents() {
        val data = ByteArray(300_000) { (it % 251).toByte() }
        var upstreamReads = 0
        val upstream = object : RandomAccessSource {
            val inner = ByteArraySource(data)
            override val size get() = inner.size
            override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                upstreamReads++
                return inner.read(position, buffer, offset, minOf(length, 10_000)) // короткие чтения
            }
            override fun close() {}
        }
        val cached = CachedRandomAccessSource(upstream, blockSize = 4096, maxBlocks = 8, readAheadBlocks = 4)
        assertArrayEquals(data.copyOfRange(5000, 20000), cached.readBytes(5000, 15000))
        val reads = upstreamReads
        assertArrayEquals(data.copyOfRange(4096, 16384), cached.readBytes(4096, 12288)) // из кэша
        assertEquals(reads, upstreamReads)
        assertArrayEquals(data.copyOfRange(299_000, 300_000), cached.readBytes(299_000, 1000)) // хвост
        assertEquals(-1, cached.read(300_000, ByteArray(4), 0, 4))

        val ext = ExtentRandomAccessSource(ByteArraySource(data), listOf(DiscExtent(100, 10), DiscExtent(-1, 5), DiscExtent(0, 3)))
        assertEquals(18L, ext.size)
        val expected = data.copyOfRange(100, 110) + ByteArray(5) + data.copyOfRange(0, 3)
        assertArrayEquals(expected, ext.readBytes(0, 18))
        assertArrayEquals(expected.copyOfRange(8, 16), ext.readBytes(8, 8))
    }
}
