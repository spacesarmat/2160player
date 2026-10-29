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
import android.content.Intent
import android.provider.DocumentsContract
import tv.p2160.core.api.ExternalSubtitle
import tv.p2160.core.resume.ResumeStore
import tv.p2160.core.subtitle.ContentSidecars
import tv.p2160.core.subtitle.SubtitleFolderMemory
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
    fun documentParentAndTreeCover() {
        assertEquals("primary:Movies/PR20Test", ContentSidecars.primaryParentId("Movies/PR20Test/"))
        assertEquals("primary:Download", ContentSidecars.parentDocumentId("primary:Download/Tears of Steel.mp4"))
        assertEquals(null, ContentSidecars.parentDocumentId("msf:100"))
        assertTrue(ContentSidecars.treeCovers("primary:Download", "primary:Download/Tears of Steel.mp4"))
        assertTrue(ContentSidecars.treeCovers("primary:", "primary:Download/Tears of Steel.mp4"))
        assertFalse(ContentSidecars.treeCovers("primary:Movies", "primary:Download/Tears of Steel.mp4"))
        assertEquals(
            listOf("Tears of Steel.en.SRT", "Tears of Steel.ru.srt"),
            ContentSidecars.namesMatching(
                "Tears of Steel",
                listOf("Tears of Steel.ru.srt", "Tears of Steel2.srt", "Tears of Steel.en.SRT", "notes.txt"),
            ),
        )
    }

    @Test
    fun contentUriOnAReadableDirectoryFindsSidecarsWithoutAsking() {
        val context = RuntimeEnvironment.getApplication()
        val root = File(context.cacheDir, "ext-root").apply { mkdirs() }
        val previous = ContentSidecars.primaryRoot
        ContentSidecars.primaryRoot = { root }
        try {
            val dir = File(root, "Download").apply { mkdirs() }
            File(dir, "Tears of Steel.mp4").writeBytes(byteArrayOf(0))
            File(dir, "Tears of Steel.ru.srt").writeText("ru")
            File(dir, "Tears of Steel.en.ass").writeText("en")
            File(dir, "Tears of Steel2.srt").writeText("no")
            val uri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2FTears%20of%20Steel.mp4")
            val search = ContentSidecars.search(context, uri)
            assertFalse(search.askForFolder)
            assertEquals(listOf("Tears of Steel.en.ass", "Tears of Steel.ru.srt"), search.hits.map { it.name })
            val file = ContentSidecars.storageFile("primary:Download/Tears of Steel.mp4", root)
            assertEquals(File(dir, "Tears of Steel.mp4"), file)
        } finally {
            ContentSidecars.primaryRoot = previous
        }
    }

    @Test
    fun folderIsAskedOnceWhenTheDirectoryCannotBeListed() {
        val context = RuntimeEnvironment.getApplication()
        val root = File(context.cacheDir, "ext-missing").apply { mkdirs() }
        val previous = ContentSidecars.primaryRoot
        ContentSidecars.primaryRoot = { root }
        try {
            val uri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2FTears%20of%20Steel.mp4")
            val key = ContentSidecars.folderKey(uri)
            assertEquals("com.android.externalstorage.documents|primary:Download", key)
            assertTrue(ContentSidecars.search(context, uri).askForFolder)
            SubtitleFolderMemory.markAsked(context, key!!)
            assertFalse(ContentSidecars.search(context, uri).askForFolder)
            val intent = ContentSidecars.treeIntent(uri, "Open the folder")
            assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, intent.action)
            assertEquals("Open the folder", intent.getStringExtra(DocumentsContract.EXTRA_PROMPT))
            val initial = intent.getParcelableExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri::class.java)
            assertEquals("primary:Download", DocumentsContract.getDocumentId(initial))
        } finally {
            ContentSidecars.primaryRoot = previous
        }
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
