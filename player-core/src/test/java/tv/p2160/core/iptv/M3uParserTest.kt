package tv.p2160.core.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uParserTest {

    private fun resource(name: String): String =
        javaClass.classLoader!!.getResourceAsStream(name)!!.use { it.readBytes().toString(Charsets.UTF_8) }

    @Test
    fun parsesRealIptvOrgSample() {
        val playlist = M3uParser.parse(resource("iptv/iptv-org-ru-sample.m3u"), "https://iptv-org.github.io/iptv/countries/ru.m3u")
        assertEquals(11, playlist.channels.size)
        assertFalse(playlist.looksLikeHls)

        val first = playlist.channels[0]
        assertEquals("1HD Music Television (1080p)", first.name)
        assertEquals("http://176.118.197.101/1HD/index.m3u8", first.url)
        assertEquals("1HDMusicTelevision.ru@HD", first.tvgId)
        assertEquals("https://i.imgur.com/4Ww7CsR.png", first.logo)
        assertEquals(listOf("Music"), first.groups)

        // Атрибуты http-referrer/http-user-agent и #EXTVLCOPT.
        val krasnoyarsk = playlist.channels.first { it.name.startsWith("Channel 8 Krasnoyarsk") }
        assertEquals("https://tshift-1.telecoma.tv/8channel/tracks-v1a1/mono.ts.m3u8", krasnoyarsk.referrer)
        assertTrue(krasnoyarsk.userAgent!!.startsWith("Mozilla/5.0"))
        val kuban = playlist.channels.first { it.name.startsWith("Kuban 24") }
        assertEquals("WINK/1.40.1 (AndroidTV/9) HlsWinkPlayer", kuban.userAgent)
        assertEquals("WINK/1.40.1 (AndroidTV/9) HlsWinkPlayer", kuban.httpHeaders()["User-Agent"])

        // Повторяющиеся каналы с одинаковым логотипом остаются отдельными.
        assertEquals(2, playlist.channels.count { it.name.startsWith("Russia-24") })
        assertEquals(playlist.channels.size, playlist.channels.map { it.id }.toSet().size)
        assertTrue(playlist.groups.containsAll(listOf("Music", "Entertainment", "General", "News")))
    }

    @Test
    fun handlesBomCrlfAndHeaderEpg() {
        val text = "\uFEFF#EXTM3U url-tvg=\"http://epg.one/a.xml.gz,http://epg.two/b.xml\" x-tvg-url=\"http://epg.three/c.xml\"\r\n" +
            "#EXTINF:-1 tvg-id=\"ch1\" tvg-chno=\"5\",Первый канал HD\r\n" +
            "http://host/1.m3u8\r\n"
        val p = M3uParser.parse(text)
        assertEquals(listOf("http://epg.one/a.xml.gz", "http://epg.two/b.xml", "http://epg.three/c.xml"), p.epgUrls)
        assertEquals(1, p.channels.size)
        assertEquals("Первый канал HD", p.channels[0].name)
        assertEquals(5, p.channels[0].number)
        assertEquals("http://host/1.m3u8", p.channels[0].url)
    }

    @Test
    fun attributesWithCommasQuotesAndUnquotedValues() {
        val text = """
            #EXTM3U
            #EXTINF:-1 tvg-id=ch.ru tvg-name='Канал, один' group-title="Новости, инфо;Избранное" catchup="shift" catchup-days="7" catchup-source="?utc={utc}",Канал, один: вечер
            http://h/ch
        """.trimIndent()
        val c = M3uParser.parse(text).channels.single()
        assertEquals("ch.ru", c.tvgId)
        assertEquals("Канал, один", c.tvgName)
        assertEquals("Канал, один: вечер", c.name)
        assertEquals(listOf("Новости, инфо", "Избранное"), c.groups)
        assertEquals("shift", c.catchup)
        assertEquals(7, c.catchupDays)
        assertEquals("?utc={utc}", c.catchupSource)
    }

    @Test
    fun noSpaceBeforeCommaAndMissingTitleFallsBackToTvgName() {
        val text = "#EXTM3U\n#EXTINF:-1,Simple\nhttp://a/1\n#EXTINF:0 tvg-name=\"From tvg\",\nhttp://a/2\n#EXTINF:-1\nhttp://a/stream3.ts\n"
        val ch = M3uParser.parse(text).channels
        assertEquals(listOf("Simple", "From tvg", "stream3"), ch.map { it.name })
    }

    @Test
    fun extGrpVlcOptKodiPropExtHttpAndPipeHeaders() {
        val text = """
            #EXTM3U
            #EXTINF:-1,One
            #EXTGRP:Спорт
            #EXTVLCOPT:http-user-agent=VLC/3
            #EXTVLCOPT:http-referrer=https://ref.one/
            http://a/1
            #EXTINF:-1,Two
            #KODIPROP:inputstream.adaptive.stream_headers=User-Agent=Kodi%2F20&Referer=https://ref.two/
            http://a/2
            #EXTINF:-1,Three
            #EXTHTTP:{"User-Agent":"Tivi/1","Cookie":"a=b"}
            http://a/3
            #EXTINF:-1,Four
            http://a/4|User-Agent=Pipe/1&Referer=https%3A%2F%2Fref.four%2F
            #EXTINF:-1,Five
            http://a/5
        """.trimIndent()
        val ch = M3uParser.parse(text).channels
        assertEquals(listOf("Спорт"), ch[0].groups)
        assertEquals("VLC/3", ch[0].userAgent)
        assertEquals("https://ref.one/", ch[0].referrer)
        assertEquals("Kodi/20", ch[1].userAgent)
        assertEquals("https://ref.two/", ch[1].referrer)
        assertEquals("Tivi/1", ch[2].userAgent)
        assertEquals("a=b", ch[2].headers["Cookie"])
        assertEquals("http://a/4", ch[3].url)
        assertEquals("Pipe/1", ch[3].userAgent)
        assertEquals("https://ref.four/", ch[3].referrer)
        // Параметры не «протекают» на следующую запись.
        assertNull(ch[4].userAgent)
        assertTrue(ch[4].groups.isEmpty())
    }

    @Test
    fun titleContinuedOnNextLine() {
        val text = "#EXTM3U\n#EXTINF:-1 tvg-id=\"x\",Очень длинное\nназвание канала\nhttp://a/x.m3u8\n"
        assertEquals("Очень длинное название канала", M3uParser.parse(text).channels.single().name)
    }

    @Test
    fun relativeUrlsResolvedAgainstPlaylist() {
        val text = "#EXTM3U\n#EXTINF:-1 tvg-logo=\"logos/a.png\",A\nstreams/a.m3u8\n#EXTINF:-1,B\n/abs/b.m3u8\n"
        val ch = M3uParser.parse(text, "http://srv.local/lists/my.m3u").channels
        assertEquals("http://srv.local/lists/streams/a.m3u8", ch[0].url)
        assertEquals("http://srv.local/lists/logos/a.png", ch[0].logo)
        assertEquals("http://srv.local/abs/b.m3u8", ch[1].url)
        // Для локального файла (content://) относительный адрес оставляем как есть.
        assertEquals("streams/a.m3u8", M3uParser.parse(text, "content://x/y").channels[0].url)
    }

    @Test
    fun duplicateUrlsGetUniqueIds() {
        val text = "#EXTM3U\n#EXTINF:-1,A\nhttp://a/1\n#EXTINF:-1,A\nhttp://a/1\n#EXTINF:-1,A\nhttp://a/2\n"
        val ch = M3uParser.parse(text).channels
        assertEquals(listOf("http://a/1", "http://a/1#2", "http://a/2"), ch.map { it.id })
        assertEquals(listOf("A", "A", "A"), ch.map { it.name })
    }

    @Test
    fun plainUrlListWithoutExtInf() {
        val ch = M3uParser.parse("http://a/one.ts\n\nrtsp://cam/live\n").channels
        assertEquals(listOf("one", "live"), ch.map { it.name })
    }

    @Test
    fun hlsManifestIsDetected() {
        val text = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:6\n#EXTINF:6.0,\nseg1.ts\n#EXTINF:6.0,\nseg2.ts\n"
        assertTrue(M3uParser.parse(text).looksLikeHls)
        val master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nlow/index.m3u8\n"
        assertTrue(M3uParser.parse(master).looksLikeHls)
    }

    @Test
    fun garbageAndUnclosedQuotesDoNotCrash() {
        val html = "<html>\n<head><title>404 Not Found</title></head>\n<body>nope</body>\n</html>\n"
        assertTrue(M3uParser.parse(html).channels.isEmpty())
        val broken = "#EXTM3U\n#EXTINF:-1 tvg-id=\"open group-title=\"News\",Name\nhttp://a/1\n"
        val c = M3uParser.parse(broken).channels.single()
        assertEquals("http://a/1", c.url)
        assertTrue(c.name.isNotEmpty())
    }

    @Test
    fun parseAttributesStopsAtFirstCommaOutsideQuotes() {
        val (attrs, end) = M3uParser.parseAttributes(""" a="1,2" b=3,rest""")
        assertEquals(mapOf("a" to "1,2", "b" to "3"), attrs)
        assertEquals(",rest", """ a="1,2" b=3,rest""".substring(end))
    }
}
