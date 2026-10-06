package tv.p2160.core.bluray

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BlurayParsersTest {

    private val min = 60L * 45_000 // минута в тиках 45 кГц

    @Test
    fun mplsItemsStreamsAndChapters() {
        val data = buildMpls(
            listOf(
                ItemSpec("00010", 1000, 1000 + 30 * min,
                    audio = listOf(StreamSpec(0x1100, 0x83, "eng", 0x61), StreamSpec(0x1101, 0x81, "rus", 0x61)),
                    pg = listOf(StreamSpec(0x1200, 0x90, "eng"), StreamSpec(0x1201, 0x90, "rus"))),
                ItemSpec("00011", 500, 500 + 60 * min, angles = 3),
            ),
            listOf(
                MarkSpec(1, 0, 1000),
                MarkSpec(1, 0, 1000 + 10 * min),
                MarkSpec(2, 0, 1000 + 12 * min), // link point — не глава
                MarkSpec(1, 1, 500 + 5 * min),
                MarkSpec(1, 7, 0), // ссылка на несуществующий элемент
            ),
        )
        val mpls = MplsParser.parse(data)
        assertEquals("0200", mpls.version)
        assertEquals(2, mpls.items.size)
        val first = mpls.items[0]
        assertEquals("00010", first.clipName)
        assertEquals("M2TS", first.codecId)
        assertEquals(1, first.connectionCondition)
        assertEquals(1, first.videoStreams.size)
        assertEquals(StreamKind.VIDEO, first.videoStreams[0].kind)
        assertEquals("1080p", StreamCoding.formatName(StreamKind.VIDEO, first.videoStreams[0].format))
        assertEquals(listOf(0x1100, 0x1101), first.audioStreams.map { it.pid })
        assertEquals(listOf("eng", "rus"), first.audioStreams.map { it.language })
        assertEquals("Dolby TrueHD, multi-channel, 48 kHz", first.audioStreams[0].description)
        assertEquals(listOf("eng", "rus"), first.pgStreams.map { it.language })
        assertEquals(3, mpls.items[1].angleCount)
        assertEquals(5, mpls.marks.size)

        val title = BlurayDisc.buildTitle("00001", mpls, "BDMV")
        assertEquals(90 * 60_000L, title.durationMs)
        assertEquals("BDMV/STREAM/00011.m2ts", title.items[1].m2tsPath)
        assertEquals(listOf(0L, 10 * 60_000L, 35 * 60_000L), title.chapters)
    }

    @Test
    fun timeMapping() {
        val mpls = MplsParser.parse(buildMpls(
            listOf(ItemSpec("00001", 9000, 9000 + 10 * min), ItemSpec("00002", 0, 20 * min)), emptyList()))
        val t = BlurayDisc.buildTitle("x", mpls, "")
        assertEquals(ClipPosition(0, 9000), t.locate(0))
        assertEquals(ClipPosition(0, 9000 + 5 * min), t.locate(5 * 60_000))
        assertEquals(ClipPosition(1, 0), t.locate(10 * 60_000))
        assertEquals(ClipPosition(1, 3 * min), t.locate(13 * 60_000))
        assertEquals(ClipPosition(1, 20 * min), t.locate(999 * 60_000)) // за концом — край
        assertEquals(13 * 60_000L, t.toPlaylistMs(1, 3 * min))
        assertEquals(5 * 60_000L, t.toPlaylistMs(0, 9000 + 5 * min))
        assertEquals(0L, t.toPlaylistMs(0, 0)) // до IN_time — начало элемента
    }

    @Test
    fun mainTitleSkipsShortAndObfuscatedPlaylists() {
        fun title(name: String, clips: List<String>, minutes: Long, chapters: Int = 0): BlurayTitle {
            val per = minutes * min / clips.size
            val mpls = MplsPlaylist("0200", clips.map { c ->
                MplsPlayItem(c, "M2TS", 1, 0, 0, per, 1, emptyList(), emptyList(), emptyList(), emptyList(), 0, 0)
            }, 0, List(chapters) { MplsMark(1, 0, it * 45_000L, 0, 0) })
            return BlurayDisc.buildTitle(name, mpls, "BDMV")
        }
        // Обманки: та же длина, клипы перемешаны и повторяются; настоящий — по возрастанию.
        val titles = listOf(
            title("00001", listOf("00005"), 3),
            title("00100", listOf("00030", "00012", "00030", "00011"), 140, chapters = 30),
            title("00200", listOf("00011", "00012", "00013", "00014"), 140, chapters = 20),
            title("00300", listOf("00014", "00011", "00013", "00012"), 140, chapters = 20),
        )
        assertEquals("00200", pickMain(titles))
        // Без обманок — самый длинный.
        assertEquals("00100", pickMain(listOf(titles[0], titles[1], title("00002", listOf("00001"), 100))))
        // Только короткие — всё равно что-то выбираем.
        assertEquals("00001", pickMain(listOf(titles[0])))
    }

    private fun pickMain(titles: List<BlurayTitle>): String? {
        val fs = MapFileSystem(titles)
        return BlurayDisc.open(fs).mainTitle()?.playlistName
    }

    /** ФС в памяти с плейлистами, пересобранными из заголовков. */
    private class MapFileSystem(titles: List<BlurayTitle>) : DiscFileSystem {
        private val files = titles.associate { t ->
            "BDMV/PLAYLIST/${t.playlistName}.mpls" to buildMpls(
                t.items.map { ItemSpec(it.clipName, it.inTime45k, it.outTime45k) },
                t.chapters.map { MarkSpec(1, 0, it * 45) },
            )
        }

        override fun list(path: String) = when (path) {
            "BDMV/PLAYLIST" -> files.keys.map { DiscEntry(it.substringAfterLast('/'), false, 0) }
            else -> null
        }

        override fun stat(path: String) = if (path == "BDMV") DiscEntry("BDMV", true, 0) else null
        override fun open(path: String): RandomAccessSource = ByteArraySource(files.getValue(path))
        override fun close() {}
    }

    @Test
    fun malformedMplsFailsCleanly() {
        val good = buildMpls(listOf(ItemSpec("00001", 0, min)), listOf(MarkSpec(1, 0, 0)))
        for (cut in listOf(0, 3, 8, 30, 45, 60, good.size - 20)) {
            try {
                MplsParser.parse(good.copyOf(cut))
            } catch (e: BlurayFormatException) {
                continue
            }
            // Обрезка в метках допустима: элементы целы, меток меньше.
        }
        val bad = good.copyOf().also { it[0] = 'X'.code.toByte() }
        try {
            MplsParser.parse(bad); fail()
        } catch (_: BlurayFormatException) {
        }
    }

    @Test
    fun clpiProgramInfoAndEpMap() {
        // Точки входа: время кратно 256 тикам (точность EP_map), SPN произвольный.
        val points = listOf(0L to 0L, 45_000L * 2 to 1500L, 45_000L * 4 to 140_000L, 45_000L * 3600 to 9_000_000L)
            .map { (t, s) -> (t / 256 * 256) to s }
        val data = buildClpi(points)
        val clip = ClpiParser.parse(data)
        assertEquals("0200", clip.version)
        assertEquals(listOf(0x1011, 0x1100, 0x1200), clip.streams.map { it.pid })
        assertEquals("HEVC", clip.streams[0].codec)
        assertEquals("rus", clip.streams[1].language)
        assertEquals(StreamKind.SUBTITLE, clip.streams[2].kind)
        assertEquals(1, clip.sequences.size)
        assertEquals(27_000L, clip.sequences[0].presentationStart45k)
        val ep = clip.videoEpMap!!
        assertEquals(0x1011, ep.pid)
        assertArrayEquals(points.map { it.first }.toLongArray(), ep.pts45k)
        assertArrayEquals(points.map { it.second }.toLongArray(), ep.spn)
        assertEquals(1500L, ep.spnAt(45_000L * 3))
        assertEquals(140_000L * 192, ep.byteOffsetAt(45_000L * 100))
        assertEquals(0L, ep.spnAt(-5))
    }

    private fun buildClpi(points: List<Pair<Long, Long>>): ByteArray {
        val clipInfo = BeWriter().u32(12).zeros(2).u8(1).u8(1).u32(0).u32(48_000_000).u32(10_000_000).toByteArray()
        val seq = BeWriter().apply {
            val b = BeWriter().u8(0).u8(1).u32(0).u8(1).u8(0).u16(0x1001).u32(0).u32(27_000).u32(27_000 + 3600 * 45_000L)
            u32(b.size.toLong()).bytes(b.toByteArray())
        }.toByteArray()
        val prog = BeWriter().apply {
            val b = BeWriter().u8(0).u8(1).u32(0).u16(0x100).u8(3).u8(0)
            b.u16(0x1011).u8(6).u8(0x24).u8(0x81).zeros(4)
            b.u16(0x1100).u8(6).u8(0x86).u8(0x61).ascii("rus").u8(0)
            b.u16(0x1200).u8(5).u8(0x90).ascii("eng").u8(0)
            u32(b.size.toLong()).bytes(b.toByteArray())
        }.toByteArray()
        val cpi = BeWriter().apply {
            // coarse — когда меняются старшие биты PTS или SPN.
            val coarse = ArrayList<Triple<Int, Long, Long>>()
            val fine = ArrayList<Long>()
            points.forEachIndexed { i, (pts45, spn) ->
                val pts90 = pts45 * 2
                val cPts = (pts90 ushr 19) and 0x3FFF
                val cSpn = spn and 0x1FFFF.inv().toLong()
                if (coarse.isEmpty() || coarse.last().second != cPts || coarse.last().third != cSpn) {
                    coarse += Triple(i, cPts, cSpn)
                }
                fine += (((pts90 ushr 9) and 0x7FF) shl 17) or (spn and 0x1FFFF)
            }
            val stream = BeWriter().u32(4 + coarse.size * 8)
            coarse.forEach { (id, p, s) -> stream.u32((id.toLong() shl 14) or p).u32(s) }
            fine.forEach { stream.u32(it) }
            val epHeader = 2 + 12
            val packed = (1L shl 34) or (coarse.size.toLong() shl 18) or fine.size.toLong()
            val ep = BeWriter().u8(0).u8(1).u16(0x1011).u16((packed ushr 32).toInt()).u32(packed and 0xFFFFFFFFL)
                .u32(epHeader).bytes(stream.toByteArray())
            u32(2 + ep.size.toLong()).u16(1).bytes(ep.toByteArray())
        }.toByteArray()
        val seqStart = 40 + clipInfo.size
        val progStart = seqStart + seq.size
        val cpiStart = progStart + prog.size
        return BeWriter().ascii("HDMV0200").u32(seqStart).u32(progStart).u32(cpiStart).u32(0).u32(0).zeros(12)
            .bytes(clipInfo).bytes(seq).bytes(prog).bytes(cpi).toByteArray()
    }

    @Test
    fun streamDescriptions() {
        assertEquals("PGS", BlurayStream(0x1200, 0x90, "eng").description)
        assertEquals("DTS-HD MA, stereo + multi-channel, 192/48 kHz", BlurayStream(1, 0x86, null, 12, 12).description)
        assertNull(BlurayStream(1, 0x77).language)
        assertFalse(StreamCoding.kind(0x77) == StreamKind.AUDIO)
        assertTrue(StreamCoding.kind(0xA2) == StreamKind.AUDIO)
    }
}
