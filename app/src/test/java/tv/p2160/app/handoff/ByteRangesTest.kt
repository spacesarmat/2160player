package tv.p2160.app.handoff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ByteRangesTest {
    @Test
    fun fullFileWhenHeaderMissing() {
        val range = ByteRanges.resolve(null, 1000)
        assertEquals(0L, range!!.start)
        assertEquals(999L, range.endInclusive)
        assertEquals(1000L, range.length)
        assertFalse(range.partial)
    }

    @Test
    fun openEndedAndSuffix() {
        val open = ByteRanges.resolve("bytes=100-", 1000)!!
        assertEquals(100L, open.start)
        assertEquals(999L, open.endInclusive)
        assertTrue(open.partial)
        val suffix = ByteRanges.resolve("bytes=-50", 1000)!!
        assertEquals(950L, suffix.start)
        assertEquals(999L, suffix.endInclusive)
        val clamped = ByteRanges.resolve("bytes=-5000", 1000)!!
        assertEquals(0L, clamped.start)
        assertEquals(999L, clamped.endInclusive)
    }

    @Test
    fun unsatisfiableDoesNotInventANegativeLength() {
        assertNull(ByteRanges.resolve("bytes=1000-", 1000))
        assertNull(ByteRanges.resolve("bytes=500-100", 1000))
        assertNull(ByteRanges.resolve("bytes=0-0", 0))
        assertNull(ByteRanges.resolve("bytes=not-a-number", 1000))
        val empty = ByteRanges.resolve(null, 0)!!
        assertEquals(0L, empty.length)
    }
}
