package tv.p2160.core.avi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AviHeaderTest {
    @Test
    fun tarkovskyDurationUsesVideoStrhNotAvihOrSampleSize() {
        val strh = AviHeader.resolveDurationUs(
            videoLength = 32_981,
            videoScale = 1001,
            videoRate = 30_000,
            videoSampleSize = 1_036_800,
            idx1VideoFrames = 32_981,
            avihFrames = 78_833,
            avihFrameUs = 33_367,
        )
        assertEquals(32_981L * 1001L * 1_000_000L / 30_000L, strh)
        val avih = 78_833L * 33_367L
        assertTrue(strh < avih)
    }

    @Test
    fun idx1CountIsUsedWhenStrhLengthIsMissing() {
        val fromIndex = AviHeader.resolveDurationUs(
            videoLength = 0,
            videoScale = 1001,
            videoRate = 30_000,
            videoSampleSize = 1,
            idx1VideoFrames = 32_981,
            avihFrames = 78_833,
            avihFrameUs = 33_367,
        )
        assertEquals(32_981L * 1001L * 1_000_000L / 30_000L, fromIndex)
    }

    @Test
    fun avihIsOnlyTheLastFallback() {
        val onlyAvih = AviHeader.resolveDurationUs(0, 0, 0, 0, 0, 78_833, 33_367)
        assertEquals(78_833L * 33_367L, onlyAvih)
    }

    @Test
    fun strfBeforeStrhAndDiv3BecomeMp43InTheBuffer() {
        val hdrl = hdrlBody(strfFirst = true, compression = "DIV3", handler = "DIV3")
        val facts = checkNotNull(AviHeader.tryFixHdrl(hdrl, 0, hdrl.size))
        assertTrue(facts.divx3)
        assertTrue(facts.videos.single().strfBeforeStrh)
        assertEquals("MP43", facts.videos.single().compression)
        assertEquals(32_981L * 1001L * 1_000_000L / 30_000L, facts.durationUs)
        assertTrue(strhPrecedesStrf(hdrl))
        assertTrue(String(hdrl, Charsets.ISO_8859_1).contains("MP43"))
    }

    @Test
    fun lowercaseXvidStaysAVideoFact() {
        val hdrl = hdrlBody(strfFirst = false, compression = "xvid", handler = "xvid")
        val facts = checkNotNull(AviHeader.tryFixHdrl(hdrl, 0, hdrl.size))
        assertEquals("xvid", facts.videos.single().compression)
        assertFalse(facts.divx3)
        assertFalse(facts.videos.single().strfBeforeStrh)
    }

    private fun strhPrecedesStrf(body: ByteArray): Boolean {
        val text = String(body, Charsets.ISO_8859_1)
        return text.indexOf("strh") in 0 until text.indexOf("strf")
    }

    private fun hdrlBody(strfFirst: Boolean, compression: String, handler: String): ByteArray {
        val strh = ByteArray(56)
        "vids".toByteArray(Charsets.US_ASCII).copyInto(strh, 0)
        handler.toByteArray(Charsets.US_ASCII).copyInto(strh, 4)
        le(1001).copyInto(strh, 20)
        le(30_000).copyInto(strh, 24)
        le(32_981).copyInto(strh, 32)
        le(1_036_800).copyInto(strh, 44)
        val strf = ByteArray(40)
        le(40).copyInto(strf, 0)
        le(672).copyInto(strf, 4)
        le(480).copyInto(strf, 8)
        strf[12] = 1
        strf[14] = 24
        compression.toByteArray(Charsets.US_ASCII).copyInto(strf, 16)
        val strhBox = box("strh", strh)
        val strfBox = box("strf", strf)
        val inner = if (strfFirst) strfBox + strhBox else strhBox + strfBox
        val avih = ByteArray(56)
        le(33_367).copyInto(avih, 0)
        le(78_833).copyInto(avih, 16)
        return box("avih", avih) + box("LIST", "strl".toByteArray(Charsets.US_ASCII) + inner)
    }

    private fun box(id: String, body: ByteArray): ByteArray =
        id.toByteArray(Charsets.US_ASCII) + le(body.size) + body + if (body.size % 2 == 1) byteArrayOf(0) else ByteArray(0)

    private fun le(value: Int): ByteArray = byteArrayOf(
        (value and 0xff).toByte(),
        ((value ushr 8) and 0xff).toByte(),
        ((value ushr 16) and 0xff).toByte(),
        ((value ushr 24) and 0xff).toByte(),
    )
}
