package tv.p2160.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalBrowserTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun foldersFirstThenMediaSortedNaturallyWithoutHiddenAndOtherFiles() {
        val root = tmp.newFolder("media")
        File(root, "Сериал").mkdir()
        File(root, ".thumbnails").mkdir()
        File(root, "BDMV").mkdir()
        listOf("Серия 10.mkv", "Серия 2.mkv", "song.flac", "movie.iso", "Серия 2.ru.srt", "list.m3u", "notes.txt", ".hidden.mp4", "info.xml")
            .forEach { File(root, it).writeText("x") }

        val names = listLocalMedia(root)!!.map { it.name }

        assertEquals(
            listOf("BDMV", "Сериал", "list.m3u", "movie.iso", "song.flac", "Серия 2.mkv", "Серия 2.ru.srt", "Серия 10.mkv"),
            names,
        )
    }

    @Test
    fun kinds() {
        val dir = tmp.newFolder("BDMV")
        assertEquals(LocalKind.DISC, localKind(dir))
        assertEquals(LocalKind.FOLDER, localKind(tmp.newFolder("Movies")))
        assertEquals(LocalKind.VIDEO, localKind(File("a.MKV"), false))
        assertEquals(LocalKind.AUDIO, localKind(File("a.flac"), false))
        assertEquals(LocalKind.DISC, localKind(File("a.iso"), false))
        assertEquals(LocalKind.SUBTITLE, localKind(File("a.ass"), false))
        assertEquals(LocalKind.PLAYLIST, localKind(File("a.m3u8"), false))
        assertEquals(LocalKind.OTHER, localKind(File("a.nfo"), false))
    }

    @Test
    fun unreadableFolderIsNull() {
        assertNull(listLocalMedia(File(tmp.root, "missing")))
    }

    @Test
    fun naturalOrder() {
        val sorted = listOf("e10", "e2", "E1", "e02b").sortedWith(NaturalNameOrder)
        assertEquals(listOf("E1", "e2", "e02b", "e10"), sorted)
    }
}
