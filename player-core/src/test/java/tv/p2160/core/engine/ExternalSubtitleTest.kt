package tv.p2160.core.engine

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import tv.p2160.core.api.ExternalSubtitle
import tv.p2160.core.resume.ResumeStore
import tv.p2160.core.subtitle.SubtitleSupport
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.Charset

/**
 * Внешний .srt, выбранный в плеере, не становился дорожкой: Media3 обновляет прогрессивный
 * источник на месте, если URI видео тот же, и игнорирует новый [MediaItem.SubtitleConfiguration].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ExternalSubtitleTest {

    private val srt = "1\n00:00:01,000 --> 00:00:02,000\nПривет\n"

    @OptIn(UnstableApi::class)
    @Test
    fun replaceKeepsSourceWhenOnlySubtitlesChange() {
        val context = RuntimeEnvironment.getApplication()
        val video = MediaItem.fromUri(Uri.parse("file:///Download/Tears%20of%20Steel.mp4"))
        val factory = ProgressiveMediaSource.Factory(DefaultDataSource.Factory(context))
        val source = factory.createMediaSource(video)
        val withSub = video.buildUpon()
            .setSubtitleConfigurations(
                listOf(
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse("content://downloads/document/msf:9"))
                        .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                        .setLanguage("ru")
                        .setId("ext:0:9")
                        .build()
                )
            )
            .build()
        assertTrue(source.canUpdateMediaItem(withSub))
        val wrapped = SubtitleReload.Factory(factory).createMediaSource(video)
        assertFalse(wrapped.canUpdateMediaItem(withSub))
        assertTrue(wrapped.canUpdateMediaItem(video))
    }

    @Test
    fun contentUriIsCopiedToUtf8File() {
        val context = RuntimeEnvironment.getApplication()
        val name = "Tears of Steel.ru.srt"
        val uri = Uri.parse("content://test.subtitles/document/${Uri.encode(name)}")
        val bytes = srt.toByteArray(Charset.forName("windows-1251"))
        Shadows.shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))
        val out = runBlocking { SubtitleSupport.normalize(context, uri, emptyMap(), name) }
        assertEquals("file", out.scheme)
        assertTrue(out.lastPathSegment!!.endsWith(".srt"))
        assertEquals(srt, File(out.path!!).readText(Charsets.UTF_8))
    }

    @Test
    fun contentUriHasNoSidecarsButAFileDoes() {
        val context = RuntimeEnvironment.getApplication()
        val dir = File(context.cacheDir, "tos-side").apply { mkdirs() }
        val video = File(dir, "Tears of Steel.mp4").apply { writeBytes(byteArrayOf(0)) }
        File(dir, "Tears of Steel.ru.srt").writeText("ru")
        File(dir, "Tears of Steel.en.srt").writeText("en")
        File(dir, "Tears of Steel2.srt").writeText("no")
        val found = SubtitleSupport.findSidecars(context, Uri.fromFile(video)).map { it.lastPathSegment!! }
        assertEquals(listOf("Tears of Steel.en.srt", "Tears of Steel.ru.srt"), found.sorted())
        assertTrue(SubtitleSupport.findSidecars(context, Uri.parse("content://downloads/document/msf:1")).isEmpty())
    }

    @Test
    fun rememberedSubtitleIsMergedAndStoredForTheFile() {
        val context = RuntimeEnvironment.getApplication()
        val picked = ExternalSubtitle(
            Uri.parse("content://downloads/document/msf:42"),
            "Tears of Steel.ru.srt",
            "ru",
            select = true,
        )
        val side = ExternalSubtitle(Uri.parse("file:///tmp/Tears%20of%20Steel.en.srt"), "Tears of Steel.en.srt", "en")
        val merged = SubtitleSupport.mergeExternal(emptyList(), listOf(picked), listOf(side), includeSidecars = true)
        assertEquals(2, merged.size)
        assertTrue(merged.first().select)
        val intentOnly = SubtitleSupport.mergeExternal(listOf(picked), emptyList(), listOf(side), includeSidecars = false)
        assertEquals(1, intentOnly.size)

        val store = ResumeStore.get(context)
        val key = "content://video/${System.nanoTime()}"
        store.upsertExternalSubtitle(key, picked)
        val saved = store.externalSubtitles(key).single()
        assertEquals(picked.uri, saved.uri)
        assertEquals("ru", saved.language)
        assertTrue(saved.select)
        store.setExternalSubtitleSelected(key, null)
        assertFalse(store.externalSubtitles(key).single().select)
        store.delete(key)
        assertTrue(store.externalSubtitles(key).isEmpty())
    }
}
