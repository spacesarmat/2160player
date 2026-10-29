package tv.p2160.core.audio

import android.content.Intent
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tv.p2160.core.api.ExternalAudio
import tv.p2160.core.api.ExternalSubtitle
import tv.p2160.core.api.ExternalTrackJson
import tv.p2160.core.api.IntentApi
import tv.p2160.core.api.MediaEntry
import tv.p2160.core.api.PlaybackRequest
import tv.p2160.core.api.PlaybackResult
import tv.p2160.core.api.SelectedTrack
import tv.p2160.core.api.TrackSelect
import tv.p2160.core.api.stableId
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException
import tv.p2160.core.engine.AudioSpec
import tv.p2160.core.engine.failingExternalIndex
import tv.p2160.core.engine.playbackSpeedFor
import tv.p2160.core.engine.CarryHint
import tv.p2160.core.engine.CarryTrack
import tv.p2160.core.engine.ExternalAudioTag
import tv.p2160.core.engine.SubtitleReload
import tv.p2160.core.engine.TrackCarry
import tv.p2160.core.resume.ResumeStore
import tv.p2160.core.subtitle.ContentSidecars
import tv.p2160.core.subtitle.SubtitleSupport
import java.io.File
import java.nio.charset.Charset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ExternalAudioTest {

    @Test
    fun sidecarNamesMatchStemAndAudioFolders() {
        val dir = File(RuntimeEnvironment.getApplication().cacheDir, "audio-side").apply { mkdirs() }
        File(dir, "Movie.mkv").writeBytes(byteArrayOf(1))
        File(dir, "Movie.ru.mka").writeText("a")
        File(dir, "Movie.rus.ac3").writeText("b")
        File(dir, "Movie2.mp3").writeText("no")
        File(dir, "Movie.srt").writeText("sub")
        val audio = File(dir, "Audio").apply { mkdirs() }
        File(audio, "Movie.en.m4a").writeText("en")
        val rus = File(dir, "RUS Sound").apply { mkdirs() }
        File(rus, "Movie.ac3").writeText("rus")
        val found = AudioSupport.collectDirectory(dir, "Movie")
        val names = found.map { it.first.name }.sorted()
        assertEquals(listOf("Movie.ac3", "Movie.en.m4a", "Movie.ru.mka", "Movie.rus.ac3"), names)
        assertEquals("rus", found.first { it.first.name == "Movie.ac3" }.second)
        assertEquals("ru", SubtitleSupport.languageFromName("Movie.ru.mka"))
        assertEquals("rus", SubtitleSupport.languageFromName("Movie.rus.ac3"))
        assertTrue(AudioSupport.isAudioFolder("RUS Sound"))
        assertTrue(AudioSupport.isAudioFolder("Audio"))
        assertFalse(AudioSupport.isAudioFolder("Subs"))
        assertEquals("eng", AudioSupport.folderLanguage("ENG Sound"))
        assertNull(AudioSupport.folderLanguage("Audio"))
    }

    @Test
    fun httpCandidatesSkipQueryLinksAndStayBounded() {
        val plain = Uri.parse("http://nas/movies/Movie.mkv")
        val names = AudioSupport.httpRelativeNames("Movie", listOf("ru", "en"), limit = 8)
        assertTrue(names.first().startsWith("Movie."))
        assertTrue(names.size <= 8)
        assertEquals("http://nas/movies/Movie.ru.mka", AudioSupport.siblingUri(plain, "Movie.ru.mka").toString())
        assertEquals(
            "http://nas/movies/RUS%20Sound/Movie.ac3",
            AudioSupport.siblingUri(plain, "RUS Sound/Movie.ac3").toString(),
        )
    }

    @Test
    fun jsonRoundTripKeepsFieldsAndLegacySubs() {
        val audio = ExternalAudio(
            Uri.parse("http://127.0.0.1:8090/stream/LostFilm.mka"),
            name = "LostFilm",
            language = "rus",
            id = "lostfilm",
            mimeType = "audio/x-matroska",
            delayMs = 120,
        )
        val sub = ExternalSubtitle(
            Uri.parse("http://127.0.0.1:8090/stream/ru.srt"),
            name = "Русские",
            language = "rus",
            id = "rus-subs",
            charset = "windows-1251",
            select = true,
        )
        val parsedAudio = ExternalTrackJson.parseAudio(ExternalTrackJson.audioJson(listOf(audio))).single()
        assertEquals(audio.uri, parsedAudio.uri)
        assertEquals("lostfilm", parsedAudio.stableId())
        assertEquals(120L, parsedAudio.delayMs)
        val parsedSub = ExternalTrackJson.parseSubtitles(ExternalTrackJson.subtitleJson(listOf(sub))).single()
        assertEquals("windows-1251", parsedSub.charset)
        assertTrue(parsedSub.select)

        val e1 = Uri.parse("http://cdn/e1.mkv")
        val e2 = Uri.parse("http://cdn/e2.mkv")
        val request = PlaybackRequest(
            items = listOf(
                MediaEntry(e1, "E1", audio = listOf(audio), subtitles = listOf(sub), audioSelect = "id:lostfilm", textSelect = "id:rus-subs"),
                MediaEntry(e2, "E2", audio = listOf(audio.copy(uri = Uri.parse("http://cdn/e2.mka")))),
            ),
            startIndex = 0,
        )
        val intent = IntentApi.toIntent(request, Intent(IntentApi.ACTION_PLAY))
        val again = IntentApi.parse(intent)!!
        assertEquals("lostfilm", again.items[0].audio.single().id)
        assertEquals("id:lostfilm", again.items[0].audioSelect)
        assertEquals("id:rus-subs", again.items[0].textSelect)
        assertEquals("windows-1251", again.items[0].subtitles.first { it.id == "rus-subs" }.charset)
        assertEquals("http://cdn/e2.mka", again.items[1].audio.single().uri.toString())

        val legacy = Intent(IntentApi.ACTION_PLAY).setData(e1).putExtra(IntentApi.EXTRA_VLC_SUBTITLE, "/sdcard/Movie.ru.srt")
        val legacyParsed = IntentApi.parse(legacy)!!
        assertEquals(Uri.parse("file:///sdcard/Movie.ru.srt"), legacyParsed.items.single().subtitles.single().uri)
        assertTrue(legacyParsed.items.single().subtitles.single().select)
    }

    @Test
    fun selectTokensAndCarryPreferStudioThenLanguage() {
        assertTrue(TrackSelect.parse("off")!!.off)
        assertEquals("lostfilm", TrackSelect.parse("id:lostfilm")!!.externalId)
        assertEquals(1, TrackSelect.parse("index:1")!!.externalIndex)
        assertEquals(2, TrackSelect.parse("embedded:2")!!.embeddedIndex)
        assertNull(TrackSelect.parse("nope"))

        val tracks = listOf(
            CarryTrack(externalId = "lostfilm", name = "LostFilm", language = "rus", external = true),
            CarryTrack(externalId = "original", name = "Original", language = "eng", external = true),
            CarryTrack(language = "rus", embeddedIndex = 0),
        )
        assertEquals(0, TrackCarry.pick(CarryHint(externalId = "lostfilm"), tracks))
        assertEquals(0, TrackCarry.pick(CarryHint(name = "LostFilm"), tracks))
        assertEquals(0, TrackCarry.pick(CarryHint(externalId = "other", language = "rus"), tracks))
        assertEquals(2, TrackCarry.pick(CarryHint(embeddedIndex = 0, language = "rus"), tracks))
        assertNull(TrackCarry.pick(CarryHint(off = true), tracks))
    }

    @Test
    fun resultKeepsMxFieldsAndAddsTrackIdentity() {
        val result = PlaybackResult(
            Uri.parse("http://cdn/e1.mkv"),
            10_000,
            50_000,
            false,
            audio = SelectedTrack("lostfilm", "LostFilm", "rus", external = true, externalIndex = 0),
            text = SelectedTrack("rus-subs", "Русские", "rus", external = true, externalIndex = 0),
        )
        val intent = IntentApi.buildResult(result)
        assertEquals(10_000, intent.getIntExtra(IntentApi.RESULT_POSITION, -1))
        assertEquals("lostfilm", intent.getStringExtra(IntentApi.EXTRA_RESULT_AUDIO_ID))
        assertEquals("LostFilm", intent.getStringExtra(IntentApi.EXTRA_RESULT_AUDIO_NAME))
        assertTrue(intent.getBooleanExtra(IntentApi.EXTRA_RESULT_AUDIO_EXTERNAL, false))
        assertEquals(0, intent.getIntExtra(IntentApi.EXTRA_RESULT_AUDIO_EXTERNAL_INDEX, -1))
        assertFalse(intent.getBooleanExtra(IntentApi.EXTRA_RESULT_TEXT_OFF, true))
        val parsed = IntentApi.parseResult(android.app.Activity.RESULT_OK, intent)!!
        assertEquals("lostfilm", parsed.audio?.id)
        assertEquals("rus-subs", parsed.text?.id)
        assertFalse(parsed.textOff)
    }

    @Test
    fun charsetOverridesAutodetect() {
        val cp = "Привет".toByteArray(Charset.forName("windows-1251"))
        assertEquals("Привет", SubtitleSupport.decodeCharset(cp, "windows-1251"))
        assertNull(SubtitleSupport.decodeCharset(cp, "not-a-charset"))
    }

    @OptIn(UnstableApi::class)
    @Test
    fun sameVideoUriRebuildsWhenExternalAudioChanges() {
        val context = RuntimeEnvironment.getApplication()
        val video = MediaItem.Builder().setUri(Uri.parse("file:///Movie.mkv")).build()
        val withAudio = video.buildUpon().setTag(
            ExternalAudioTag(listOf(AudioSpec(Uri.parse("file:///Movie.ru.mka"), 0, "Movie.ru", "ru", MimeTypes.AUDIO_MPEG, 0))),
        ).build()
        val factory = SubtitleReload.Factory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context)))
        val source = factory.createMediaSource(video)
        assertFalse(source.canUpdateMediaItem(withAudio))
        assertTrue(source.canUpdateMediaItem(video))
    }

    @Test
    fun rememberedAudioIsStoredForTheFile() {
        val context = RuntimeEnvironment.getApplication()
        val store = ResumeStore.get(context)
        val key = "audio-test-${System.nanoTime()}"
        val audio = ExternalAudio(Uri.parse("content://audio/lostfilm"), "LostFilm", "rus", select = true, id = "lostfilm", delayMs = 40)
        store.upsertExternalAudio(key, audio)
        val saved = store.externalAudio(key).single()
        assertEquals("lostfilm", saved.id)
        assertEquals(40L, saved.delayMs)
        assertTrue(saved.select)
        store.setExternalAudioSelected(key, null)
        assertFalse(store.externalAudio(key).single().select)
        store.delete(key)
        assertTrue(store.externalAudio(key).isEmpty())
    }

    @Test
    fun failedRememberedAudioIsDroppedWithoutForgettingTheOther() {
        val context = RuntimeEnvironment.getApplication()
        val store = ResumeStore.get(context)
        val key = "audio-drop-${System.nanoTime()}"
        store.upsertExternalAudio(key, ExternalAudio(Uri.parse("file:///missing.mka"), "Bad", "ru", select = true))
        store.upsertExternalAudio(key, ExternalAudio(Uri.parse("file:///ok.mka"), "Ok", "en"))
        store.deleteExternalAudio(key, "file:///missing.mka")
        assertEquals(listOf("file:///ok.mka"), store.externalAudio(key).map { it.uri.toString() })
        store.deleteExternalAudio(key)
        assertTrue(store.externalAudio(key).isEmpty())
    }

    @Test
    fun torrentUriIsNotProbedAndAMissingFileIs() {
        val context = RuntimeEnvironment.getApplication()
        assertTrue(AudioSupport.reachable(context, Uri.parse("torrent://0123456789abcdef0123456789abcdef01234567/3/Movie.ru.mka"), emptyMap(), 1000))
        assertFalse(AudioSupport.reachable(context, Uri.parse("file:///no/such/Movie.ru.mka"), emptyMap(), 1000))
    }

    @Test
    fun adbSplitsJsonOnCommasAndASingleStringStillParses() {
        val json = """[{"uri":"http://nas/Movie.ru.mka","language":"rus","name":"Studio"}]"""
        val pieces = json.split(",")
        assertTrue(pieces.size > 1)
        assertEquals(listOf(json), ExternalTrackJson.reassemble(pieces))
        val video = Uri.parse("http://nas/Movie.mkv")
        val shattered = Intent(IntentApi.ACTION_PLAY).setData(video)
            .putExtra(IntentApi.EXTRA_AUDIO_TRACKS, pieces.toTypedArray())
        val fromPieces = IntentApi.parse(shattered)!!
        assertEquals("Studio", fromPieces.items.single().audio.single().name)
        val escaped = json.replace(",", "\\,")
        val escapedIntent = Intent(IntentApi.ACTION_PLAY).setData(video)
            .putExtra(IntentApi.EXTRA_AUDIO_TRACKS, escaped)
        assertEquals("Studio", IntentApi.parse(escapedIntent)!!.items.single().audio.single().name)
        assertEquals("rus", fromPieces.items.single().audio.single().language)

        val whole = Intent(IntentApi.ACTION_PLAY).setData(video).putExtra(IntentApi.EXTRA_AUDIO_TRACKS, json)
        assertEquals("Studio", IntentApi.parse(whole)!!.items.single().audio.single().name)

        val e1 = Uri.parse("http://nas/e1.mkv")
        val e2 = Uri.parse("http://nas/e2.mkv")
        val first = """[{"uri":"http://nas/e1.mka","name":"A"}]"""
        val second = """[{"uri":"http://nas/e2.mka","name":"B"}]"""
        val both = Intent(IntentApi.ACTION_PLAY).setData(e1)
            .putExtra(IntentApi.EXTRA_PLAYLIST, arrayOf(e1.toString(), e2.toString()))
            .putExtra(IntentApi.EXTRA_AUDIO_TRACKS, ExternalTrackJson.reassemble((first + "," + second).split(","))!!.toTypedArray())
        val parsed = IntentApi.parse(both)!!
        assertEquals("A", parsed.items[0].audio.single().name)
        assertEquals("B", parsed.items[1].audio.single().name)
    }

    @Test
    fun contentAudioUsesSubtitlePathAndOneAudioSubfolder() {
        val context = RuntimeEnvironment.getApplication()
        val root = File(context.cacheDir, "audio-root-${System.nanoTime()}").apply { mkdirs() }
        val previous = ContentSidecars.primaryRoot
        ContentSidecars.primaryRoot = { root }
        try {
            val dir = File(root, "Download").apply { mkdirs() }
            File(dir, "Movie.mp4").writeBytes(byteArrayOf(0))
            File(dir, "Movie.ru.srt").writeText("s")
            File(dir, "Movie.ru.mka").writeText("a")
            val sound = File(dir, "RUS Sound").apply { mkdirs() }
            File(sound, "Movie.ac3").writeText("b")
            val uri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2FMovie.mp4")
            val found = AudioSupport.findSidecars(context, uri, emptyMap())
            assertEquals(setOf("Movie.ru.mka", "Movie.ac3"), found.map { it.uri.lastPathSegment }.toSet())
            assertEquals("rus", found.first { it.uri.lastPathSegment == "Movie.ac3" }.language)
            assertTrue(AudioSupport.isAudioFolder("RUS Sound [Shift]"))
            assertFalse(ContentSidecars.search(context, uri).hits.any { it.name.endsWith(".mka") || it.name.endsWith(".ac3") })
        } finally {
            ContentSidecars.primaryRoot = previous
        }
    }

    @Test
    fun englishAndRussianSidecarsAreBothKept() {
        val dir = File(RuntimeEnvironment.getApplication().cacheDir, "lang-side").apply { mkdirs() }
        File(dir, "Movie.mkv").writeBytes(byteArrayOf(1))
        File(dir, "Movie.en.ac3").writeText("en")
        File(dir, "Movie.ru.ac3").writeText("ru")
        val names = AudioSupport.collectDirectory(dir, "Movie").map { it.first.name }
        assertEquals(listOf("Movie.en.ac3", "Movie.ru.ac3"), names)
    }

    @OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test
    fun oneTimesSpeedIsKeptAndOnlyTheBadExternalFileIsIdentified() {
        assertEquals(1f, playbackSpeedFor(1f, 1.5f, live = false), 0.001f)
        assertEquals(1.5f, playbackSpeedFor(1.5f, 1f, live = false), 0.001f)
        assertEquals(1.5f, playbackSpeedFor(null, 1.5f, live = false), 0.001f)
        assertEquals(1f, playbackSpeedFor(1.5f, 1f, live = true), 0.001f)
        val bad = ExternalAudio(Uri.parse("file:///Bad.ac3"), "Bad")
        val good = ExternalAudio(Uri.parse("file:///V1.ru.mka"), "Good")
        val error = UnrecognizedInputFormatException("none", bad.uri)
        assertEquals(0, failingExternalIndex(error, listOf(bad, good)))
        assertEquals(null, failingExternalIndex(error, listOf(good)))
    }
}
