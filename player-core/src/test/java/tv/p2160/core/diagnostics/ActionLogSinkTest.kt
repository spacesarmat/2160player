package tv.p2160.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ActionLogSinkTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun eventsAreWrittenWithSourceAndMessage() {
        val sink = tv.p2160.core.diagnostics.ActionLog.Sink(tmp.newFolder("diag"))
        sink.openSession()
        sink.write("12:00:00.000 | ui | tap card")
        sink.write("12:00:01.000 | player | open item=0")
        sink.close()

        val file = sink.currentFile()!!
        val lines = file.readLines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("| ui | tap card"))
        assertTrue(lines[1].contains("| player | open item=0"))
        assertTrue(file.name.startsWith("actions-"))
    }

    @Test
    fun fileRotatesWhenItGrowsPastTheLimit() {
        val sink = tv.p2160.core.diagnostics.ActionLog.Sink(tmp.newFolder("diag"), maxBytes = 128, keepFiles = 8)
        sink.openSession()
        sink.write("12:00:00.000 | test | first line")
        val first = sink.dir.listFiles()!!.single { it.name.startsWith("actions-") }
        repeat(20) { sink.write("12:00:00.000 | test | line $it with some payload to grow the file quickly") }
        sink.close()

        val files = sink.dir.listFiles()!!.filter { it.name.startsWith("actions-") }
        assertTrue("rotation should create more files", files.size > 1)
        assertTrue("first file stays within the limit", first.length() <= 128 + 256)
        files.forEach { assertTrue(it.length() <= 128 + 256) }
    }

    @Test
    fun cleanupKeepsOnlyTheNewestFiles() {
        val dir = tmp.newFolder("diag")
        val sink = tv.p2160.core.diagnostics.ActionLog.Sink(dir, keepFiles = 3)
        repeat(6) { n ->
            File(dir, "actions-2026010${n + 1}-120000.log").writeText("x")
            Thread.sleep(5) // lastModified различается
        }
        sink.openSession()
        sink.close()

        val kept = dir.listFiles()!!.filter { it.name.startsWith("actions-") }
        // Три новейших из шести + новый файл сессии.
        assertEquals(4, kept.size)
        assertTrue(kept.none { it.name == "actions-20260101-120000.log" })
        assertTrue(kept.none { it.name == "actions-20260102-120000.log" })
        assertTrue(kept.none { it.name == "actions-20260103-120000.log" })
    }

    @Test
    fun currentFileIsNullBeforeAnyWrite() {
        val sink = tv.p2160.core.diagnostics.ActionLog.Sink(tmp.newFolder("diag"))
        assertNull(sink.currentFile())
        sink.openSession()
        sink.close()
        // Сессия без событий: файл пуст, делиться нечем.
        assertNull(sink.currentFile())
    }
}
