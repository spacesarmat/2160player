package tv.p2160.core.m2ts

import androidx.media3.common.C
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class M2tsPacketInputTest {

    private val packets = 500
    /** Физический M2TS: 4 байта заголовка (случайные, в т.ч. 0x47) + 188 байт TS. */
    private val physical: ByteArray
    /** Ожидаемый логический поток (только 188-байтные TS-пакеты). */
    private val logical: ByteArray

    init {
        val random = Random(42)
        val phys = ByteArrayOutputStream()
        val log = ByteArrayOutputStream()
        repeat(packets) {
            val header = ByteArray(4) { 0x47 }
            random.nextBytes(header, 1, 4)
            val ts = random.nextBytes(188)
            ts[0] = 0x47
            phys.write(header)
            phys.write(ts)
            log.write(ts)
        }
        physical = phys.toByteArray()
        logical = log.toByteArray()
    }

    @Test
    fun positionMapping() {
        val input = M2tsPacketInput(phase = 4)
        assertEquals(4L, input.toPhysical(0))
        assertEquals(4L + 187, input.toPhysical(187))
        assertEquals(4L + 192, input.toPhysical(188))
        assertEquals(0L, input.toLogical(0))
        assertEquals(0L, input.toLogical(4))
        assertEquals(188L, input.toLogical(192)) // заголовок пакета 1 → начало пакета 1
        assertEquals(188L, input.toLogical(196))
        for (l in longArrayOf(0, 1, 187, 188, 1000, 20_000_000_000L)) {
            assertEquals(l, input.toLogical(input.toPhysical(l)))
        }
    }

    @Test
    fun readsStrippedStreamWithOddReadSizes() {
        val source = ArrayByteSource(physical)
        val input = M2tsPacketInput(phase = 4)
        input.bind(SourceExtractorInput(source, 0))
        assertEquals(logical.size.toLong(), input.length)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1100)
        var size = 1
        while (true) {
            val n = input.read(buf, 0, size)
            if (n == C.RESULT_END_OF_INPUT) break
            out.write(buf, 0, n)
            size = size % 997 + 7
        }
        assertArrayEquals(logical, out.toByteArray())
        assertEquals(logical.size.toLong(), input.position)
    }

    @Test
    fun peekThenReadAndSeek() {
        val source = ArrayByteSource(physical)
        val input = M2tsPacketInput(phase = 4)
        input.bind(SourceExtractorInput(source, 0))
        val peeked = ByteArray(50_000)
        input.peekFully(peeked, 0, peeked.size)
        assertArrayEquals(logical.copyOfRange(0, 50_000), peeked)
        assertEquals(50_000L, input.peekPosition)
        assertEquals(0L, input.position)
        input.skipFully(1000)
        input.resetPeekPosition()
        val read = ByteArray(300)
        input.readFully(read, 0, 300)
        assertArrayEquals(logical.copyOfRange(1000, 1300), read)

        // «Seek»: новый физический ввод на позиции логического 188·100+10.
        val target = 188L * 100 + 10
        input.invalidate()
        input.bind(SourceExtractorInput(source, input.toPhysical(target)))
        assertEquals(target, input.position)
        input.readFully(read, 0, 300)
        assertArrayEquals(logical.copyOfRange(target.toInt(), target.toInt() + 300), read)

        // Конец ввода.
        input.invalidate()
        input.bind(SourceExtractorInput(source, physical.size.toLong()))
        assertFalse(input.readFully(read, 0, 10, true))
    }

    @Test
    fun continuationWithNewInputAtSamePhysicalPosition() {
        val source = ArrayByteSource(physical)
        val input = M2tsPacketInput(phase = 4)
        val first = SourceExtractorInput(source, 0)
        input.bind(first)
        val a = ByteArray(5000)
        input.readFully(a, 0, a.size)
        // Загрузчик переоткрыл источник там, где остановился физический ввод.
        input.bind(SourceExtractorInput(source, first.position))
        val b = ByteArray(5000)
        input.readFully(b, 0, b.size)
        assertArrayEquals(logical.copyOfRange(0, 10_000), a + b)
    }
}
