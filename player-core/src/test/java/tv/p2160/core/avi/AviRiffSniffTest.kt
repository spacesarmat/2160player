@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package tv.p2160.core.avi

import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.avi.AviExtractor
import androidx.media3.extractor.mp3.Mp3Extractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.p2160.core.m2ts.ArrayByteSource
import tv.p2160.core.m2ts.SourceExtractorInput

class AviRiffSniffTest {
    companion object {
        init {
            // Media3 сравнивает Build.FINGERPRINT без проверки null. В JVM-тесте поле пустое,
            // и sniff падает в static init. До первого sniff ставим значение, как у Robolectric.
            val fingerprint = android.os.Build::class.java.getDeclaredField("FINGERPRINT")
            fingerprint.isAccessible = true
            if (fingerprint.get(null) == null) {
                val unsafeClass = Class.forName("sun.misc.Unsafe")
                val theUnsafe = unsafeClass.getDeclaredField("theUnsafe")
                theUnsafe.isAccessible = true
                val unsafe = theUnsafe.get(null)
                val base = unsafeClass.getMethod("staticFieldBase", java.lang.reflect.Field::class.java).invoke(unsafe, fingerprint)
                val offset = unsafeClass.getMethod("staticFieldOffset", java.lang.reflect.Field::class.java).invoke(unsafe, fingerprint) as Long
                unsafeClass.getMethod("putObject", Any::class.java, java.lang.Long.TYPE, Any::class.java)
                    .invoke(unsafe, base, offset, "robolectric")
            }
        }
    }

    @Test
    fun riffAviWhoseMoviStartsWithMp3FramesStaysWithAvi() {
        val bytes = riffAviWithMp3(at = 10_260)
        assertTrue(Mp3Extractor().sniff(input(bytes)))
        assertTrue(AviExtractor().sniff(input(bytes)))

        val ordered = aviThenMp3()
        assertEquals("AviFixExtractor", ordered[0].javaClass.simpleName)
        assertTrue(ordered[0].sniff(input(bytes)))
        val mp3 = ordered.first { it is RiffAviBlocked }
        assertFalse(mp3.sniff(input(bytes)))

        var claimed: String? = null
        for (extractor in ordered) {
            if (extractor.sniff(input(bytes))) {
                claimed = extractor.javaClass.simpleName
                break
            }
        }
        assertEquals("AviFixExtractor", claimed)
    }

    @Test
    fun plainMp3IsStillOpenedByTheMp3Extractor() {
        val frame = mpeg1Layer3Frame()
        val bytes = ByteArray(frame.size * 4)
        repeat(4) { frame.copyInto(bytes, it * frame.size) }
        val ordered = aviThenMp3()
        assertFalse(ordered[0].sniff(input(bytes)))
        assertTrue(ordered.first { it is RiffAviBlocked }.sniff(input(bytes)))
    }

    private fun aviThenMp3(): Array<Extractor> =
        AviPlayback.factory(object : ExtractorsFactory {
            override fun createExtractors(): Array<Extractor> = arrayOf(Mp3Extractor(), AviExtractor())
        }).createExtractors()

    private fun input(bytes: ByteArray) = SourceExtractorInput(ArrayByteSource(bytes), 0)

    private fun riffAviWithMp3(at: Int): ByteArray {
        val frame = mpeg1Layer3Frame()
        val frames = ByteArray(frame.size * 4)
        repeat(4) { frame.copyInto(frames, it * frame.size) }
        val data = ByteArray(at + frames.size)
        data[0] = 'R'.code.toByte()
        data[1] = 'I'.code.toByte()
        data[2] = 'F'.code.toByte()
        data[3] = 'F'.code.toByte()
        val size = data.size - 8
        data[4] = (size and 0xFF).toByte()
        data[5] = ((size shr 8) and 0xFF).toByte()
        data[6] = ((size shr 16) and 0xFF).toByte()
        data[7] = ((size shr 24) and 0xFF).toByte()
        data[8] = 'A'.code.toByte()
        data[9] = 'V'.code.toByte()
        data[10] = 'I'.code.toByte()
        data[11] = ' '.code.toByte()
        frames.copyInto(data, at)
        return data
    }

    private fun mpeg1Layer3Frame(): ByteArray {
        val frame = ByteArray(417)
        frame[0] = 0xFF.toByte()
        frame[1] = 0xFB.toByte()
        frame[2] = 0x90.toByte()
        frame[3] = 0x00
        return frame
    }
}
