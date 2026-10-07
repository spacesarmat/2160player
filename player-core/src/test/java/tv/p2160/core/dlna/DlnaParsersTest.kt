package tv.p2160.core.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import tv.p2160.core.source.dlna.ContentDirectory
import tv.p2160.core.source.dlna.DeviceDescriptionParser
import tv.p2160.core.source.dlna.DidlParser
import tv.p2160.core.source.dlna.DlnaContainer
import tv.p2160.core.source.dlna.DlnaException
import tv.p2160.core.source.dlna.DlnaHttp
import tv.p2160.core.source.dlna.DlnaItem
import tv.p2160.core.source.dlna.DlnaMediaKind
import tv.p2160.core.source.dlna.DlnaPlayback
import tv.p2160.core.source.dlna.DlnaServer
import tv.p2160.core.source.dlna.HttpResult
import tv.p2160.core.source.dlna.SsdpMessage
import tv.p2160.core.source.dlna.XmlLite

class DlnaParsersTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("dlna/$name")!!.use { it.readBytes().toString(Charsets.UTF_8) }

    private fun browse(name: String) = ContentDirectory.parseBrowseResponse(HttpResult(200, fixture(name)))

    // ---------- SSDP ----------

    @Test
    fun parsesSearchResponse() {
        val text = "HTTP/1.1 200 OK\r\n" +
            "CACHE-CONTROL: max-age=1800\r\n" +
            "DATE: Wed, 07 Oct 2026 10:00:00 GMT\r\n" +
            "EXT:\r\n" +
            "LOCATION: http://192.168.1.191:8200/rootDesc.xml\r\n" +
            "SERVER: 5.10 DLNADOC/1.50 UPnP/1.0 MiniDLNA/1.3.3\r\n" +
            "ST: urn:schemas-upnp-org:device:MediaServer:1\r\n" +
            "USN: uuid:4d696e69-444c-164e-9d41-0242ac110002::urn:schemas-upnp-org:device:MediaServer:1\r\n" +
            "Content-Length: 0\r\n\r\n"
        val msg = SsdpMessage.parse(text)!!
        assertEquals(SsdpMessage.Kind.RESPONSE, msg.kind)
        assertEquals("http://192.168.1.191:8200/rootDesc.xml", msg.location)
        assertEquals("uuid:4d696e69-444c-164e-9d41-0242ac110002", msg.udn)
        assertTrue(msg.looksLikeMediaServer)
        assertFalse(msg.isByeBye)
    }

    @Test
    fun parsesNotifyAliveAndByeBye() {
        val alive = SsdpMessage.parse(
            "NOTIFY * HTTP/1.1\nHOST: 239.255.255.250:1900\nNT: urn:schemas-upnp-org:service:ContentDirectory:1\n" +
                "NTS: ssdp:alive\nLocation: http://10.0.0.5:9000/desc\nUSN: uuid:abc::urn:schemas-upnp-org:service:ContentDirectory:1\n\n"
        )!!
        assertEquals(SsdpMessage.Kind.NOTIFY, alive.kind)
        assertEquals("http://10.0.0.5:9000/desc", alive.location) // заголовки без учёта регистра
        assertTrue(alive.looksLikeMediaServer)
        assertFalse(alive.isByeBye)

        val bye = SsdpMessage.parse("NOTIFY * HTTP/1.1\r\nNT: upnp:rootdevice\r\nNTS: ssdp:byebye\r\nUSN: uuid:abc::upnp:rootdevice\r\n\r\n")!!
        assertTrue(bye.isByeBye)
        assertEquals("uuid:abc", bye.udn)
        assertFalse(bye.looksLikeMediaServer)
    }

    @Test
    fun rejectsGarbageAndErrors() {
        assertNull(SsdpMessage.parse("hello"))
        assertNull(SsdpMessage.parse("HTTP/1.1 404 Not Found\r\n\r\n"))
        assertEquals(SsdpMessage.Kind.SEARCH, SsdpMessage.parse(SsdpMessage.search(SsdpMessage.ST_ALL))!!.kind)
        assertTrue(SsdpMessage.search(SsdpMessage.ST_MEDIA_SERVER).endsWith("\r\n\r\n"))
    }

    // ---------- Описание устройства ----------

    @Test
    fun parsesMiniDlnaDescription() {
        val s = DeviceDescriptionParser.parse(fixture("minidlna_description.xml"), "http://192.168.1.191:8200/rootDesc.xml")!!
        assertEquals("ZimaOS: minidlna", s.friendlyName)
        assertEquals("Justin Maggard", s.manufacturer)
        assertEquals("uuid:4d696e69-444c-164e-9d41-0242ac110002", s.udn)
        assertEquals("http://192.168.1.191:8200/ctl/ContentDir", s.contentDirectoryControlUrl)
        assertEquals("urn:schemas-upnp-org:service:ContentDirectory:1", s.contentDirectoryType)
        assertEquals(4, s.icons.size)
        assertEquals("http://192.168.1.191:8200/icons/lrg.png", s.bestIcon()!!.url)
        assertEquals("192.168.1.191", s.host)
    }

    @Test
    fun parsesEmbeddedDeviceWithUrlBase() {
        val s = DeviceDescriptionParser.parse(fixture("embedded_description.xml"), "http://192.168.1.20:50001/desc/device.xml")!!
        assertEquals("DiskStation Media Server & Video", s.friendlyName)
        assertEquals("uuid:73796E6F-6473-6D00-0000-001132aabbcd", s.udn)
        // Относительно URLBase, а не адреса описания.
        assertEquals("http://192.168.1.20:50001/upnp/cd/control", s.contentDirectoryControlUrl)
        assertEquals("urn:schemas-upnp-org:service:ContentDirectory:2", s.contentDirectoryType)
        // У вложенного устройства иконок нет — берём у корневого.
        assertEquals("http://192.168.1.20:50001/upnp/icons/big.png", s.bestIcon()!!.url)
    }

    @Test
    fun parsesWindowsMediaPlayerDescription() {
        val s = DeviceDescriptionParser.parse(fixture("wmp_description.xml"), "http://192.168.1.30:2869/upnphost/udhisapi.dll?content=uuid:abc")!!
        assertEquals("Windows Media Player Sharing", s.modelName)
        assertEquals(
            "http://192.168.1.30:2869/upnphost/udhisapi.dll?control=uuid:2b1a0b6a-4d0e-4b7c-9f6a-1234567890ab+urn:upnp-org:serviceId:ContentDirectory",
            s.contentDirectoryControlUrl,
        )
    }

    @Test
    fun descriptionWithoutContentDirectoryIsIgnored() {
        assertNull(DeviceDescriptionParser.parse(fixture("router_description.xml"), "http://192.168.1.1:1900/igd.xml"))
        assertNull(DeviceDescriptionParser.parse("not xml at all", "http://x/"))
    }

    @Test
    fun resolvesUrlBaseWithoutPath() {
        val xml = fixture("minidlna_description.xml").replace("/ctl/ContentDir", "ctl/ContentDir")
            .replace("<specVersion>", "<URLBase>http://10.0.0.9:8200</URLBase><specVersion>")
        val s = DeviceDescriptionParser.parse(xml, "http://10.0.0.9:8200/rootDesc.xml")!!
        assertEquals("http://10.0.0.9:8200/ctl/ContentDir", s.contentDirectoryControlUrl)
    }

    // ---------- DIDL-Lite ----------

    @Test
    fun parsesEscapedRoot() {
        val page = browse("minidlna_browse_root.xml")
        assertEquals(4, page.numberReturned)
        assertEquals(4, page.totalMatches)
        assertEquals("17", page.updateId)
        val titles = page.objects.map { it.title }
        assertEquals(listOf("Browse Folders", "Music", "Pictures", "Video"), titles)
        val video = page.objects.last() as DlnaContainer
        assertEquals("2", video.id)
        assertEquals(3, video.childCount)
    }

    @Test
    fun parsesMiniDlnaItemsWithCaptions() {
        val page = browse("minidlna_browse_videos.xml")
        val folder = page.objects[0] as DlnaContainer
        assertEquals("64$0$1", folder.id)
        assertEquals("Сериалы", folder.title)

        val dune = page.objects[1] as DlnaItem
        assertEquals("Дюна: Часть вторая (2024)", dune.title)
        assertEquals(DlnaMediaKind.VIDEO, dune.kind)
        val res = dune.bestResource!!
        assertEquals("http://192.168.1.191:8200/MediaItems/412.mkv", res.url)
        assertEquals("video/x-matroska", res.mimeType)
        assertEquals(23_756_851_234L, res.size)
        assertEquals((2 * 3600 + 46 * 60 + 1) * 1000L + 120, res.durationMs)
        assertEquals(3840, res.width)
        assertEquals(1600, res.height)
        assertEquals(2_384_965L, res.bitrate)
        assertFalse(res.isConverted)
        assertEquals("http://192.168.1.191:8200/AlbumArt/88-412.jpg", dune.albumArtUrl)
        // CaptionInfoEx и res text/srt указывают на один файл — одна дорожка.
        assertEquals(1, dune.subtitles.size)
        assertEquals("srt", dune.subtitles[0].format)
        assertEquals("http://192.168.1.191:8200/Captions/412.srt", dune.subtitles[0].url)

        val tom = page.objects[2] as DlnaItem
        assertEquals("Tom & Jerry", tom.title)
        assertEquals("AVC_MP4_MP_HD_720p_AAC", tom.bestResource!!.dlnaProfile)

        val notes = page.objects[3] as DlnaItem
        assertEquals(DlnaMediaKind.OTHER, notes.kind)
        assertNull(notes.bestResource)
    }

    @Test
    fun parsesJellyfinPrefersOriginalOverTranscode() {
        val item = browse("jellyfin_browse.xml").objects.single() as DlnaItem
        assertEquals("Pilot", item.title)
        val best = item.bestResource!!
        assertEquals("http://192.168.1.191:8096/dlna/0d3a/videos/7d4c/stream.mkv?Static=true&MediaSourceId=7d4c&DeviceId=abc", best.url)
        assertEquals(58 * 60 * 1000L + 7000, item.durationMs)
        assertEquals(2, item.subtitles.size)
        assertEquals("srt", item.subtitles[0].format)
        assertEquals("vtt", item.subtitles[1].format)
        assertEquals("rus", item.subtitles[1].language)
    }

    @Test
    fun parsesPlexCdataPrefersHighestOriginal() {
        val objects = browse("plex_browse.xml").objects
        val movie = objects[0] as DlnaItem
        assertEquals("{ffd5b0b6-5f2b-4c3d-a9c3-plex}223", movie.id)
        assertEquals("http://192.168.1.5:32469/object/223/file.mkv", movie.bestResource!!.url)
        assertEquals(DlnaMediaKind.VIDEO, movie.kind)
        val song = objects[1] as DlnaItem
        assertEquals(DlnaMediaKind.AUDIO, song.kind)
        assertEquals("Artist", song.artist)
        assertEquals(210_000L, song.durationMs)
    }

    @Test
    fun parsesUnescapedDidlAndUnknownTotal() {
        val page = browse("raw_didl_browse.xml")
        assertEquals(0, page.totalMatches)
        assertEquals("Movies & Shows", page.objects[0].title)
        val clip = page.objects[1] as DlnaItem
        assertEquals(DlnaMediaKind.VIDEO, clip.kind) // класс не указан — по MIME
        assertEquals("http://10.0.0.2:9000/disk/clip.avi", clip.bestResource!!.url)
    }

    @Test
    fun parsesDoubleEscapedResult() {
        val didl = "<DIDL-Lite><container id=\"a\" parentID=\"0\"><dc:title>X &amp; Y</dc:title></container></DIDL-Lite>"
        val once = XmlLite.escape(didl)
        val twice = XmlLite.escape(once)
        val body = "<s:Envelope><s:Body><u:BrowseResponse><Result>$twice</Result><NumberReturned>1</NumberReturned>" +
            "<TotalMatches>1</TotalMatches></u:BrowseResponse></s:Body></s:Envelope>"
        val page = ContentDirectory.parseBrowseResponse(HttpResult(200, body))
        assertEquals("X & Y", page.objects.single().title)
    }

    @Test
    fun soapFaultThrows() {
        try {
            ContentDirectory.parseBrowseResponse(HttpResult(500, fixture("fault.xml")))
            fail("expected DlnaException")
        } catch (e: DlnaException) {
            assertEquals(701, e.code)
            assertTrue(e.message!!.contains("No such object"))
        }
    }

    @Test
    fun parsesDurations() {
        assertEquals(3_723_000L, DidlParser.parseDuration("1:02:03"))
        assertEquals(3_723_500L, DidlParser.parseDuration("01:02:03.500"))
        assertEquals(3_723_500L, DidlParser.parseDuration("1:02:03.1/2"))
        assertEquals(90_000L, DidlParser.parseDuration("1:30"))
        assertEquals(45_000L, DidlParser.parseDuration("45"))
        assertNull(DidlParser.parseDuration("0:00:00"))
        assertNull(DidlParser.parseDuration("garbage"))
        assertNull(DidlParser.parseDuration(null))
    }

    @Test
    fun envelopeEscapesObjectId() {
        val env = ContentDirectory.browseEnvelope("urn:schemas-upnp-org:service:ContentDirectory:1", "a<b&c", 200, 50)
        assertTrue(env.contains("<ObjectID>a&lt;b&amp;c</ObjectID>"))
        assertTrue(env.contains("<StartingIndex>200</StartingIndex><RequestedCount>50</RequestedCount>"))
        assertTrue(env.contains("BrowseDirectChildren"))
    }

    // ---------- Постраничная загрузка ----------

    private val server = DlnaServer(
        udn = "uuid:test", friendlyName = "Test", manufacturer = null, modelName = null,
        deviceType = "urn:schemas-upnp-org:device:MediaServer:1", location = "http://h/desc.xml",
        contentDirectoryControlUrl = "http://h/ctl", contentDirectoryType = "urn:schemas-upnp-org:service:ContentDirectory:1",
    )

    /** Сервер со [total] элементами; [reportTotal] = false — отвечает TotalMatches=0; [ignoreStart] — всегда с начала. */
    private class FakeServer(val total: Int, val maxPage: Int, val reportTotal: Boolean = true, val ignoreStart: Boolean = false) : DlnaHttp {
        val requests = ArrayList<Pair<Int, Int>>()
        val headers = ArrayList<Map<String, String>>()
        override fun get(url: String) = HttpResult(404, "")
        override fun post(url: String, headers: Map<String, String>, body: String): HttpResult {
            this.headers += headers
            val start = Regex("<StartingIndex>(\\d+)").find(body)!!.groupValues[1].toInt()
            val count = Regex("<RequestedCount>(\\d+)").find(body)!!.groupValues[1].toInt()
            requests += start to count
            val from = if (ignoreStart) 0 else start
            val n = minOf(count, maxPage, (total - from).coerceAtLeast(0))
            val didl = buildString {
                append("<DIDL-Lite>")
                (from until from + n).forEach { i ->
                    append("<item id=\"i$i\" parentID=\"0\"><dc:title>Item $i</dc:title><upnp:class>object.item.videoItem</upnp:class>")
                    append("<res protocolInfo=\"http-get:*:video/mp4:*\">http://h/$i.mp4</res></item>")
                }
                append("</DIDL-Lite>")
            }
            val resp = "<s:Envelope><s:Body><u:BrowseResponse><Result>${XmlLite.escape(didl)}</Result>" +
                "<NumberReturned>$n</NumberReturned><TotalMatches>${if (reportTotal) total else 0}</TotalMatches>" +
                "</u:BrowseResponse></s:Body></s:Envelope>"
            return HttpResult(200, resp)
        }
    }

    @Test
    fun pagesUntilTotalMatches() {
        val http = FakeServer(total = 450, maxPage = 200)
        val all = ContentDirectory(server, http).browseAll("0", pageSize = 200)
        assertEquals(450, all.size)
        assertEquals(listOf(0 to 200, 200 to 200, 400 to 200), http.requests)
        assertEquals("\"urn:schemas-upnp-org:service:ContentDirectory:1#Browse\"", http.headers[0]["SOAPAction"])
    }

    @Test
    fun pagesWhenServerCapsPageSize() {
        // Сервер отдаёт не больше 50 за раз, хотя просили 200 (так делает, например, Plex).
        val http = FakeServer(total = 120, maxPage = 50)
        val all = ContentDirectory(server, http).browseAll("0", pageSize = 200)
        assertEquals(120, all.size)
        assertEquals(listOf(0, 50, 100), http.requests.map { it.first })
    }

    @Test
    fun pagesWithUnknownTotal() {
        val http = FakeServer(total = 250, maxPage = 100, reportTotal = false)
        val all = ContentDirectory(server, http).browseAll("0", pageSize = 100)
        assertEquals(250, all.size)
        assertEquals(listOf(0, 100, 200), http.requests.map { it.first })
    }

    @Test
    fun stopsWhenServerIgnoresStartingIndex() {
        val http = FakeServer(total = 500, maxPage = 100, reportTotal = true, ignoreStart = true)
        val all = ContentDirectory(server, http).browseAll("0", pageSize = 100)
        assertEquals(100, all.size)
        assertEquals(2, http.requests.size)
    }

    // ---------- Воспроизведение ----------

    @Test
    fun mapsItemToPlaybackSpec() {
        val item = browse("jellyfin_browse.xml").objects.single() as DlnaItem
        val spec = DlnaPlayback.spec(item)!!
        assertEquals(item.bestResource!!.url, spec.url)
        assertEquals("Pilot", spec.title)
        assertEquals("video/x-matroska", spec.mimeType)
        assertEquals(listOf("SRT 1.srt", "RUS.vtt"), spec.subtitles.map { it.name })
        assertEquals("rus", spec.subtitles[1].language)
    }

    @Test
    fun playlistSkipsUnplayable() {
        val items = browse("minidlna_browse_videos.xml").objects.filterIsInstance<DlnaItem>()
        // [Дюна, Tom & Jerry, notes.txt] — выбрали Tom & Jerry.
        val (playable, start) = DlnaPlayback.playlistIndex(items, 1)!!
        assertEquals(2, playable.size)
        assertEquals(1, start)
        assertNull(DlnaPlayback.playlistIndex(items, 2))
        assertNotNull(DlnaPlayback.spec(items[0]))
    }

    // ---------- Добавление по адресу ----------

    private class MapHttp(val pages: Map<String, String>) : DlnaHttp {
        val asked = ArrayList<String>()
        override fun get(url: String): HttpResult {
            asked += url
            return pages[url]?.let { HttpResult(200, it) } ?: HttpResult(404, "")
        }
        override fun post(url: String, headers: Map<String, String>, body: String) = HttpResult(404, "")
    }

    @Test
    fun probesJellyfinByHost() {
        val desc = fixture("minidlna_description.xml").replace("ZimaOS: minidlna", "HomeServer")
        val http = MapHttp(
            mapOf(
                "http://192.168.1.191:8096/System/Info/Public" to "{\"ServerName\":\"HomeServer\",\"Version\":\"10.9.11\",\"Id\":\"54eaab1aecfc40fda52f838c7145f303\"}",
                "http://192.168.1.191:8096/dlna/54eaab1aecfc40fda52f838c7145f303/description.xml" to desc,
            )
        )
        val s = tv.p2160.core.source.dlna.DlnaProbe.resolve("192.168.1.191", http)!!
        assertEquals("HomeServer", s.friendlyName)
        assertEquals("http://192.168.1.191:8096/ctl/ContentDir", s.contentDirectoryControlUrl)
        assertEquals("http://192.168.1.191:8200/rootDesc.xml", http.asked.first()) // MiniDLNA проверяется первым
    }

    @Test
    fun probesHostPortAndDirectUrl() {
        val desc = fixture("minidlna_description.xml")
        val http = MapHttp(mapOf("http://nas:9000/description.xml" to desc, "http://nas:8200/rootDesc.xml" to desc))
        assertEquals("http://nas:9000/ctl/ContentDir", tv.p2160.core.source.dlna.DlnaProbe.resolve("nas:9000", http)!!.contentDirectoryControlUrl)
        assertNotNull(tv.p2160.core.source.dlna.DlnaProbe.resolve("http://nas:8200/rootDesc.xml", http))
        assertNotNull(tv.p2160.core.source.dlna.DlnaProbe.resolve("http://nas", http))
        assertNull(tv.p2160.core.source.dlna.DlnaProbe.resolve("other", http))
        assertNull(tv.p2160.core.source.dlna.DlnaProbe.resolve("  ", http))
    }

    // ---------- XML ----------

    @Test
    fun xmlLiteToleratesBrokenMarkup() {
        val root = XmlLite.parseRoot("<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY a \"b\">]><a x='1' y=\"a>b\"><b>one &unknown; &#1076;&#x430;</b><c/><d>unclosed<e>t</e></a>")!!
        assertEquals("a", root.name)
        assertEquals("a>b", root.attr("y"))
        assertEquals("one &unknown; да", root.childText("b"))
        assertNotNull(root.child("c"))
        assertEquals("t", root.find("e")!!.text)
    }
}
