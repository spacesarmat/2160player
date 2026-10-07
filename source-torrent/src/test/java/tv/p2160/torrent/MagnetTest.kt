package tv.p2160.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MagnetTest {

    @Test
    fun parsesHexMagnetWithNameTrackersAndSeeds() {
        val m = MagnetLink.parse(
            "magnet:?xt=urn:btih:DD8255ECDC7CA55FB0BBF81323D87062DB1F6D1C&dn=Big+Buck+Bunny" +
                "&tr=udp%3A%2F%2Fexplodie.org%3A6969&tr=udp%3A%2F%2Fexplodie.org%3A6969" +
                "&tr.1=wss%3A%2F%2Ftracker.btorrent.xyz&ws=https%3A%2F%2Fwebtorrent.io%2Ftorrents%2F&xl=276134947"
        )
        assertNotNull(m)
        m!!
        assertEquals("dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c", m.infoHashV1)
        assertEquals("dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c", m.id)
        assertEquals("Big Buck Bunny", m.displayName)
        assertEquals(listOf("udp://explodie.org:6969", "wss://tracker.btorrent.xyz"), m.trackers)
        assertEquals(listOf("https://webtorrent.io/torrents/"), m.webSeeds)
        assertEquals(276134947L, m.exactLength)
    }

    @Test
    fun parsesBase32Hash() {
        // base32 от 20 байт 0x00..0x13
        val m = MagnetLink.parse("magnet:?xt=urn:btih:AAAQEAYEAUDAOCAJBIFQYDIOB4IBCEQT")
        assertEquals("000102030405060708090a0b0c0d0e0f10111213", m?.infoHashV1)
    }

    @Test
    fun acceptsBareHashAndCaseInsensitiveScheme() {
        assertEquals("dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c", MagnetLink.parse("  DD8255ECDC7CA55FB0BBF81323D87062DB1F6D1C \n")?.id)
        assertNotNull(MagnetLink.parse("MAGNET:?xt=urn:btih:dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c"))
    }

    @Test
    fun parsesV2OnlyMagnet() {
        val v2 = "a".repeat(64)
        val m = MagnetLink.parse("magnet:?xt=urn:btmh:1220$v2&dn=x")
        assertNull(m?.infoHashV1)
        assertEquals(v2, m?.infoHashV2)
        assertEquals("a".repeat(40), m?.id)
    }

    @Test
    fun hybridPrefersV1() {
        val m = MagnetLink.parse("magnet:?xt=urn:btmh:1220${"b".repeat(64)}&xt=urn:btih:${"c".repeat(40)}")
        assertEquals("c".repeat(40), m?.id)
    }

    @Test
    fun rejectsGarbage() {
        assertNull(MagnetLink.parse(""))
        assertNull(MagnetLink.parse("https://example.com/file.torrent"))
        assertNull(MagnetLink.parse("magnet:?dn=no-hash"))
        assertNull(MagnetLink.parse("magnet:?xt=urn:btih:12345"))
        assertNull(MagnetLink.parse("magnet:?xt=urn:btmh:1220abcd"))
    }
}
