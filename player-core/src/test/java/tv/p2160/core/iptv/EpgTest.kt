package tv.p2160.core.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class EpgTest {

    /** 2026-10-07 07:15 UTC = 10:15 МСК. */
    private val now = XmltvParser.parseTime("20261007071500 +0000")!!

    private val channels = listOf(
        IptvChannel(id = "r24", name = "Russia-24 HD (1080p)", url = "http://a/r24", tvgId = "Russia24.ru@HD"),
        IptvChannel(id = "2x2", name = "2x2 (576i)", url = "http://a/2x2", tvgId = "2x2.ru@SD"),
        IptvChannel(id = "p1", name = "Первый канал HD", url = "http://a/p1"),
        IptvChannel(id = "nope", name = "Unknown", url = "http://a/x", tvgId = "missing.id"),
    )

    private fun sampleBytes(): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("iptv/sample-epg.xml")!!.use { it.readBytes() }

    private fun parse(bytes: ByteArray = sampleBytes(), filter: EpgFilter? = EpgFilter(channels)): EpgData =
        XmltvParser.parse(ByteArrayInputStream(bytes), KXmlParser(), now, filter)

    @Test
    fun parsesTimes() {
        assertEquals(0L, XmltvParser.parseTime("19700101000000 +0000"))
        assertEquals(0L, XmltvParser.parseTime("19700101030000 +0300"))
        assertEquals(3_600_000L, XmltvParser.parseTime("19700101000000 -0100"))
        assertEquals(0L, XmltvParser.parseTime("197001010000"))
        assertEquals(1_000L, XmltvParser.parseTime("19700101000001Z"))
        assertEquals(1_782_000_000_000L, XmltvParser.parseTime("20260621000000 +0000"))
        assertEquals(XmltvParser.parseTime("20240229120000 +0000")!! + 86_400_000L, XmltvParser.parseTime("20240301120000 +0000"))
        assertEquals(-19_800_000L, XmltvParser.parseTime("19700101000000 +05:30"))
        assertNull(XmltvParser.parseTime("garbage"))
        assertNull(XmltvParser.parseTime(null))
        assertNull(XmltvParser.parseTime("20261399000000"))
    }

    @Test
    fun parsesSampleWithWindowAndFilter() {
        val data = parse()
        assertEquals(setOf("Russia24.ru", "2x2.ru", "perviy"), data.programmes.keys)
        val r24 = data.programmes.getValue("Russia24.ru")
        // Старые и далёкие передачи отброшены, пустое название — тоже.
        assertEquals(listOf("Вести", "Вести. Экономика", "Вести. Спорт"), r24.map { it.title })
        assertEquals("Новости & погода", r24[0].description)
        assertEquals("Выпуск 2", r24[1].description)
        assertEquals(listOf("Россия 24", "Russia-24"), data.channelNames["Russia24.ru"])
    }

    @Test
    fun withoutFilterKeepsAllChannels() {
        assertTrue("unused" in parse(filter = null).programmes.keys)
    }

    @Test
    fun missingStopIsFilledFromNextProgramme() {
        val list = parse().programmes.getValue("2x2.ru")
        assertEquals(list[1].startMs, list[0].stopMs)
        assertEquals(list[1].startMs + 3_600_000L, list[1].stopMs)
    }

    @Test
    fun gzipIsDetected() {
        val gz = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(sampleBytes()) } }.toByteArray()
        assertEquals(parse().programmes.keys, parse(gz).programmes.keys)
    }

    @Test
    fun truncatedFileKeepsWhatWasParsed() {
        val bytes = sampleBytes()
        val text = bytes.toString(Charsets.UTF_8)
        val cut = text.substring(0, text.indexOf("<programme start=\"20261001")).toByteArray()
        val data = parse(cut)
        assertEquals(3, data.programmes.getValue("Russia24.ru").size)
    }

    @Test
    fun guideMatchesByTvgIdSuffixAndName() {
        val guide = EpgGuide(parse())
        assertEquals("Russia24.ru", guide.keyFor(channels[0]))
        assertEquals("2x2.ru", guide.keyFor(channels[1]))
        assertEquals("perviy", guide.keyFor(channels[2]))
        assertNull(guide.keyFor(channels[3]))

        val r24 = guide.nowNext(channels[0], now)!!
        assertEquals("Вести. Экономика", r24.now!!.title)
        assertEquals("Вести. Спорт", r24.next!!.title)
        assertEquals(0.25f, r24.now!!.progress(now), 0.001f)

        val toons = guide.nowNext(channels[1], now)!!
        assertEquals("Cartoon A", toons.now!!.title)
        assertEquals("Cartoon B", toons.next!!.title)
        assertNull(guide.nowNext(channels[3], now))
    }

    @Test
    fun nowNextEdgeCases() {
        val list = listOf(EpgProgramme(100, 200, "a"), EpgProgramme(300, 400, "b"))
        assertEquals(NowNext(null, list[0]), EpgGuide.nowNext(list, 50))
        assertEquals(NowNext(list[0], list[1]), EpgGuide.nowNext(list, 100))
        // Перерыв между передачами: сейчас ничего, далее — «b».
        assertEquals(NowNext(null, list[1]), EpgGuide.nowNext(list, 250))
        assertEquals(NowNext(list[1], null), EpgGuide.nowNext(list, 399))
        assertEquals(NowNext(null, null), EpgGuide.nowNext(list, 400))
        assertEquals(NowNext(null, null), EpgGuide.nowNext(emptyList(), 1))
    }

    @Test
    fun normalizesNames() {
        assertEquals("russia24", EpgGuide.normalizeName("Russia-24 HD (1080p) [Geo-blocked]"))
        assertEquals("первыйканал", EpgGuide.normalizeName("Первый канал FHD"))
        assertEquals("тнт4", EpgGuide.normalizeName("ТНТ4"))
        assertEquals("1hdmusictelevision", EpgGuide.normalizeName("1HD Music Television (1080p)"))
        assertEquals("ено", EpgGuide.normalizeName("Ёно"))
    }

    @Test
    fun codecRoundTrip() {
        val data = EpgData(
            mapOf("a\tb" to listOf(EpgProgramme(1, 2, "Title\nwith\\slash", "desc\tx"), EpgProgramme(2, 3, "Plain"))),
            mapOf("a\tb" to listOf("Name 1", "Имя")),
        )
        val decoded = EpgCodec.decode(EpgCodec.encode(data))
        assertEquals(data.programmes, decoded.programmes)
        assertEquals(data.channelNames, decoded.channelNames)
        assertTrue(EpgCodec.decode("not a cache\nP\tx\t1\t2\tt\t").isEmpty)
    }

    @Test
    fun mergeAndTrim() {
        val a = EpgData(mapOf("x" to listOf(EpgProgramme(0, 10, "old"), EpgProgramme(10, 20, "new"))))
        val b = EpgData(mapOf("x" to listOf(EpgProgramme(0, 10, "other")), "y" to listOf(EpgProgramme(0, 5, "y"))))
        val merged = a + b
        assertEquals("old", merged.programmes.getValue("x")[0].title)
        assertNotNull(merged.programmes["y"])
        val trimmed = merged.trimmed(10)
        assertEquals(listOf("new"), trimmed.programmes.getValue("x").map { it.title })
        assertFalse("y" in trimmed.programmes)
    }

    @Test
    fun refreshPolicy() {
        val hour = 3_600_000L
        assertTrue(IptvStore.isStale(0, now, 24))
        assertFalse(IptvStore.isStale(now - 23 * hour, now, 24))
        assertTrue(IptvStore.isStale(now - 25 * hour, now, 24))
        assertTrue(IptvStore.isStale(now + 5 * hour, now, 24))

        val fresh = EpgData(mapOf("x" to listOf(EpgProgramme(now, now + 10 * hour, "long"))))
        assertFalse(IptvStore.needsEpgRefresh(now - hour, fresh, now))
        val ending = EpgData(mapOf("x" to listOf(EpgProgramme(now, now + hour, "short"))))
        assertTrue(IptvStore.needsEpgRefresh(now - hour, ending, now))
        assertTrue(IptvStore.needsEpgRefresh(now - 13 * hour, fresh, now))
        assertTrue(IptvStore.needsEpgRefresh(now - hour, EpgData.EMPTY, now))
    }

    @Test
    fun playbackWindowAndHeaders() {
        assertEquals(0 until 10, IptvPlayback.window(10, 3, 1000))
        assertEquals(0 until 1000, IptvPlayback.window(5000, 10, 1000))
        assertEquals(2000 until 3000, IptvPlayback.window(5000, 2500, 1000))
        assertEquals(4000 until 5000, IptvPlayback.window(5000, 4999, 1000))

        val own = IptvChannel(id = "a", name = "a", url = "u", userAgent = "Own/1", referrer = "https://r/")
        assertEquals(mapOf("User-Agent" to "Own/1", "Referer" to "https://r/"), IptvPlayback.headersFor(own, "List/1"))
        val bare = IptvChannel(id = "b", name = "b", url = "u")
        assertEquals(mapOf("User-Agent" to "List/1"), IptvPlayback.headersFor(bare, "List/1"))
        assertEquals(emptyMap<String, String>(), IptvPlayback.headersFor(bare, " "))
    }
}
