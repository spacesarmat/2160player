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
import tv.p2160.core.engine.AudioSpec
import tv.p2160.core.engine.CarryHint
import tv.p2160.core.engine.CarryTrack
import tv.p2160.core.engine.ExternalAudioTag
import tv.p2160.core.engine.SubtitleReload
import tv.p2160.core.engine.TrackCarry
import tv.p2160.core.resume.ResumeStore
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
}
