package tv.p2160.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.p2160.core.bluray.FileRandomAccessSource
import java.io.File

class MatroskaChaptersTest {
    @Test
    fun sampleHeaderHasIntroMiddleAndEnd() {
        val bytes = javaClass.getResourceAsStream("/c-chapters.mkv")!!.readBytes()
        val file = File.createTempFile("c-chapters", ".mkv")
        file.writeBytes(bytes)
        FileRandomAccessSource(file).use { source ->
            val chapters = MatroskaChapters.read(source)
            assertEquals(listOf("Intro", "Middle", "End"), chapters.map { it.title })
            assertEquals(listOf(0L, 60_000L, 180_000L), chapters.map { it.startMs })
            assertEquals(listOf(60_000L, 180_000L, 300_000L), chapters.map { it.endMs })
        }
    }

    @Test
    fun aNonMatroskaFileHasNoChapters() {
        val file = File.createTempFile("plain", ".bin")
        file.writeBytes(ByteArray(32) { 1 })
        FileRandomAccessSource(file).use { source ->
            assertTrue(MatroskaChapters.read(source).isEmpty())
        }
    }
}
