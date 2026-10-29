package tv.p2160.core.engine

import android.net.Uri
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Label
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.BaseMediaSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Заголовок дорожки в Matroska уже лежит в Format.labels. Голый setLabel роняет Format.<init>.
 * Общий период готовится только если ребёнок сообщает наружу тот же экземпляр, который ждал merge.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ExternalAudioMergeTest {

    @Test
    fun titledTrackKeepsLabelInsideLabels() {
        val original = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_AAC)
            .setLanguage("und")
            .setLabel("Inner Title")
            .setLabels(listOf(Label("und", "Inner Title")))
            .build()
        val spec = AudioSpec(Uri.parse("file:///Movie.ru.mka"), 0, "Studio", "rus", null, 0)
        val out = relabelFormat(original, spec, groupLength = 1, track = 0)
        assertEquals("Studio", out.label)
        assertEquals(listOf("Studio"), out.labels.map { it.value })
        assertEquals("ru", out.language)
        assertEquals("exta:0", out.id)
    }

    @Test
    fun mergedSourcePreparesAndExposesTheExternalLabel() {
        val context = RuntimeEnvironment.getApplication()
        val spec = AudioSpec(Uri.parse("file:///Movie.ru.mka"), 0, "Studio", "ru", MimeTypes.AUDIO_AAC, 0)
        val item = MediaItem.Builder()
            .setUri(Uri.parse("file:///Movie.mkv"))
            .setMediaId("video")
            .setTag(ExternalAudioTag(listOf(spec)))
            .build()
        val source = ExternalAudioMerge.Factory(ReadyFactory()).createMediaSource(item)
        var timeline: Timeline? = null
        val caller = MediaSource.MediaSourceCaller { _, refreshed -> timeline = refreshed }
        source.prepareSource(caller, PlayerId.UNSET, DefaultBandwidthMeter.getSingletonInstance(context))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val ready = timeline
        assertNotNull(ready)
        source.enable(caller)
        val period = source.createPeriod(
            MediaSource.MediaPeriodId(ready!!.getUidOfPeriod(0)),
            DefaultAllocator(true, 64 * 1024),
            0L,
        )
        var prepared = false
        period.prepare(object : MediaPeriod.Callback {
            override fun onPrepared(mediaPeriod: MediaPeriod) {
                prepared = true
            }

            override fun onContinueLoadingRequested(source: MediaPeriod) = Unit
        }, 0L)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(prepared)
        val labels = buildList {
            val groups = period.trackGroups
            for (g in 0 until groups.length) {
                val group = groups[g]
                for (t in 0 until group.length) add(group.getFormat(t).label)
            }
        }
        assertTrue(labels.contains("Studio"))
        source.releasePeriod(period)
        source.releaseSource(caller)
    }

    private class ReadyFactory : MediaSource.Factory {
        override fun setDrmSessionManagerProvider(drmSessionManagerProvider: androidx.media3.exoplayer.drm.DrmSessionManagerProvider) = this
        override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy) = this
        override fun getSupportedTypes(): IntArray = intArrayOf(C.CONTENT_TYPE_OTHER)
        override fun createMediaSource(mediaItem: MediaItem): MediaSource = ReadySource(mediaItem)
    }

    private class ReadySource(private val item: MediaItem) : BaseMediaSource() {
        private val audio = item.mediaId.startsWith("exta:")

        override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
            refreshSourceInfo(SinglePeriodTimeline(1_000_000L, true, false, false, null, item))
        }

        override fun releaseSourceInternal() = Unit
        override fun maybeThrowSourceInfoRefreshError() = Unit
        override fun getMediaItem(): MediaItem = item

        override fun createPeriod(id: MediaSource.MediaPeriodId, allocator: Allocator, startPositionUs: Long): MediaPeriod =
            ReadyPeriod(audio)

        override fun releasePeriod(mediaPeriod: MediaPeriod) = Unit
    }

    private class ReadyPeriod(private val audio: Boolean) : MediaPeriod {
        private val format = if (audio) {
            Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_AAC)
                .setId("inner")
                .setLabel("Inner Title")
                .setLabels(listOf(Label("und", "Inner Title")))
                .build()
        } else {
            Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).setId("video").build()
        }

        override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
            callback.onPrepared(this)
        }

        override fun maybeThrowPrepareError() = Unit
        override fun getTrackGroups(): TrackGroupArray = TrackGroupArray(androidx.media3.common.TrackGroup(format))
        override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) = Unit
        override fun readDiscontinuity(): Long = C.TIME_UNSET
        override fun seekToUs(positionUs: Long): Long = positionUs
        override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters): Long = positionUs
        override fun getBufferedPositionUs(): Long = C.TIME_END_OF_SOURCE
        override fun getNextLoadPositionUs(): Long = C.TIME_END_OF_SOURCE
        override fun continueLoading(loadPositionUs: androidx.media3.exoplayer.LoadingInfo): Boolean = false
        override fun isLoading(): Boolean = false
        override fun reevaluateBuffer(positionUs: Long) = Unit

        override fun selectTracks(
            selections: Array<out ExoTrackSelection?>,
            mayRetainStreamFlags: BooleanArray,
            streams: Array<out SampleStream?>,
            streamResetFlags: BooleanArray,
            positionUs: Long,
        ): Long = positionUs
    }
}
