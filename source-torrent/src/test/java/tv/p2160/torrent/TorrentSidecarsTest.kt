package tv.p2160.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentSidecarsTest {

    @Test
    fun episodeKeyNormalizesSeasonAndEpisode() {
        assertEquals("s01e01", TorrentSidecars.episodeKey("Show.S01E01.1080p.mkv"))
        assertEquals("s01e01", TorrentSidecars.episodeKey("Show.s1e1.mka"))
        assertEquals("s01e10", TorrentSidecars.episodeKey("Show.S01E10.mkv"))
        assertEquals("s01e02", TorrentSidecars.episodeKey("Show.1x02.ass"))
        assertNull(TorrentSidecars.episodeKey("Movie.mkv"))
    }

    @Test
    fun seriesMatchesStudioAndEpisodeNotTheNeighbour() {
        val files = listOf(
            file(0, "Show.S01E01.1080p.mkv"),
            file(1, "Show.S01E02.1080p.mkv"),
            file(2, "RUS Sound/LostFilm/Show.S01E01.mka"),
            file(3, "RUS Sound/LostFilm/Show.S01E02.ac3"),
            file(4, "RUS Sound/AlexFilm/Show.S01E01.mp3"),
            file(5, "Rus Subs/Show.S01E01.rus.srt"),
            file(6, "Rus Subs/LostFilm/Show.S01E02.ass"),
            file(7, "Audio/Show.S01E01.mp3"),
            file(8, "Subs/Show.S01E01.srt"),
            file(9, "Show.S01E01.en.srt"),
            file(10, "Movie2.mp3"),
        )
        val hits = TorrentSidecars.match(files[0], files, videoCount = 2)
        val audio = hits.filter { it.audio }
        val subs = hits.filter { !it.audio }
        assertEquals(listOf("AlexFilm", "LostFilm", null), audio.map { it.studio })
        assertEquals("lostfilm", audio.first { it.studio == "LostFilm" }.trackId)
        assertEquals("rus", audio.first { it.studio == "LostFilm" }.language)
        assertEquals("rus", audio.first { it.studio == "AlexFilm" }.language)
        assertTrue(audio.none { it.file.path.contains("S01E02") })
        assertEquals(listOf("Show.S01E01.en.srt", "Show.S01E01.rus.srt", "Show.S01E01.srt"), subs.map { it.file.name }.sorted())
        assertEquals("rus", subs.first { it.file.name.endsWith(".rus.srt") }.language)
        assertEquals("en", subs.first { it.file.name.endsWith(".en.srt") }.language)
        assertEquals("lostfilm", TorrentSidecars.match(files[1], files, 2).first { it.studio == "LostFilm" }.trackId)
    }

    @Test
    fun movieStemAndSingleVideoSidecarFolder() {
        val files = listOf(
            file(0, "Movie.mkv"),
            file(1, "Movie.ru.mka"),
            file(2, "Movie2.mp3"),
            file(3, "RUS Sound/LostFilm/dub.ac3"),
            file(4, "Subs/notes.srt"),
        )
        val hits = TorrentSidecars.match(files[0], files, videoCount = 1)
        assertEquals(setOf("Movie.ru.mka", "dub.ac3", "notes.srt"), hits.map { it.file.name }.toSet())
        assertEquals("ru", hits.first { it.file.name == "Movie.ru.mka" }.language)
        assertEquals("LostFilm", hits.first { it.file.name == "dub.ac3" }.studio)
        assertEquals("rus", hits.first { it.file.name == "dub.ac3" }.language)
    }

    @Test
    fun bracketedSubsFolderMatchesAndBonusIsNotAnEpisode() {
        assertTrue(TorrentSidecars.isSubsFolder("RUS Subs [Shift]"))
        assertTrue(TorrentSidecars.isAudioFolder("RUS Sound [Akikomi]"))
        assertEquals("rus", TorrentSidecars.folderLanguage("RUS Subs [Shift]"))
        val files = listOf(
            file(0, "11eyes/11eyes_01.mkv"),
            file(1, "11eyes/11eyes_02.mkv"),
            file(2, "11eyes/Bonus/menu.mkv"),
            file(3, "11eyes/Bonus/extra.mkv"),
            file(4, "RUS Sound/Akikomi/11eyes_01.mka"),
            file(5, "RUS Subs [Shift]/11eyes_01.ass"),
            file(6, "RUS Subs [Shift]/11eyes_02.ass"),
        )
        val episodes = TorrentSidecars.episodeFiles(files, files[0])
        assertEquals(listOf(0, 1), episodes.map { it.index })
        assertEquals(listOf(files[2]), TorrentSidecars.episodeFiles(files, files[2]))
        assertEquals(2, TorrentSidecars.mainVideoCount(files))
        val hits = TorrentSidecars.match(files[0], files, TorrentSidecars.mainVideoCount(files))
        assertEquals(setOf("11eyes_01.mka", "11eyes_01.ass"), hits.map { it.file.name }.toSet())
        assertEquals("Akikomi", hits.first { it.audio }.studio)
        assertTrue(hits.none { it.file.name.contains("02") })
        assertFalse(TorrentSidecars.isSubsFolder("substitution"))
    }

    @Test
    fun episodeTenDoesNotMatchEpisodeOne() {
        val files = listOf(
            file(0, "Show.S01E01.mkv"),
            file(1, "Show.S01E10.mkv"),
            file(2, "RUS Sound/LostFilm/Show.S01E10.mka"),
            file(3, "RUS Sound/LostFilm/Show.S01E01.mka"),
        )
        val first = TorrentSidecars.match(files[0], files, 2)
        assertEquals(listOf("Show.S01E01.mka"), first.map { it.file.name })
        assertEquals("s01e01", TorrentSidecars.episodeKey("Show.S01E1.mkv"))
        assertEquals("s01e01", TorrentSidecars.episodeKey("Show.1x01.mkv"))
    }

    private fun file(index: Int, path: String) = TorrentFile(index, path, 1_000)
}
