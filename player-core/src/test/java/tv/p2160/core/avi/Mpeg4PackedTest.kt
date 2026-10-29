package tv.p2160.core.avi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Mpeg4PackedTest {
    @Test
    fun secondVopWaitsForTheNextPacketAndNvopIsDropped() {
        val unpacker = Mpeg4PackedUnpacker().apply { setMime(Mpeg4PackedUnpacker.MIME_MP4V) }
        val iVop = vop(0) + byteArrayOf(1, 2, 3, 4)
        val bVop = vop(2) + byteArrayOf(9, 9)
        val packed = iVop + bVop
        val first = unpacker.consume(packed)
        assertEquals(iVop.toList(), first.toList())
        assertEquals(1, Mpeg4PackedUnpacker.vopCount(first))
        val nVop = vop(1) // короче 19 байт: заглушка
        assertTrue(nVop.size <= Mpeg4PackedUnpacker.MAX_NVOP_SIZE)
        val second = unpacker.consume(nVop)
        assertEquals(bVop.toList(), second.toList())
        assertEquals(1, Mpeg4PackedUnpacker.vopCount(second))
    }

    @Test
    fun singleVopPassesThroughAndPackedMarkerIsCleared() {
        val unpacker = Mpeg4PackedUnpacker().apply { setMime(Mpeg4PackedUnpacker.MIME_MP4V) }
        val user = byteArrayOf(0, 0, 1, 0xB2.toByte()) + "DivXp".encodeToByteArray() + byteArrayOf(0)
        val frame = user + vop(0) + byteArrayOf(7)
        val out = unpacker.consume(frame)
        assertFalse(out.contains('p'.code.toByte()) && out[out.indexOf('p'.code.toByte()) + 1] == 0.toByte())
        assertEquals(0.toByte(), out[user.lastIndex])
        assertEquals(1, Mpeg4PackedUnpacker.vopCount(out))
        assertTrue(Mpeg4PackedUnpacker.isIVop(out))
    }

    @Test
    fun otherMimesAreNotRewritten() {
        val unpacker = Mpeg4PackedUnpacker().apply { setMime("video/avc") }
        val packed = vop(0) + vop(2)
        assertEquals(packed.toList(), unpacker.consume(packed).toList())
    }

    private fun vop(type: Int): ByteArray = byteArrayOf(0, 0, 1, 0xB6.toByte(), (type shl 6).toByte())
}
