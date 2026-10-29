package tv.p2160.core.engine

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Label
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.ClippingMediaPeriod
import androidx.media3.exoplayer.source.FilteringMediaSource
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.source.WrappingMediaSource
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.trackselection.ForwardingTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import java.util.IdentityHashMap

/**
 * Внешнее аудио подмешивается к видео через [MergingMediaSource]:
 * шкала времени остаётся шкалой видео (`clipDurations = false`), старт периодов выравнивается.
 * Файл длиннее видео обрезается концом ролика. Файл короче не обрывает видео:
 * перемотка за его конец не роняет общий источник.
 *
 * Смена набора дорожек запрещает обновление на месте. Иначе плеер видит тот же URI видео
 * и не пересобирает источник.
 */
@OptIn(UnstableApi::class)
internal object ExternalAudioMerge {

    class Factory(private val delegate: MediaSource.Factory) : MediaSource.Factory by delegate {
        override fun createMediaSource(mediaItem: MediaItem): MediaSource {
            val primary = delegate.createMediaSource(mediaItem)
            val tag = mediaItem.localConfiguration?.tag as? ExternalAudioTag
            val live = mediaItem.liveConfiguration != MediaItem.LiveConfiguration.UNSET
            val specs = if (live) emptyList() else tag?.tracks.orEmpty()
            if (specs.isEmpty()) return primary
            val videoDuration = VideoDuration()
            val children = ArrayList<MediaSource>(specs.size + 1)
            children += DurationProbe(primary, videoDuration)
            for (spec in specs) {
                val raw = delegate.createMediaSource(spec.toMediaItem())
                val audioOnly = FilteringMediaSource(raw, C.TRACK_TYPE_AUDIO)
                children += LenientSource(RelabelSource(audioOnly, spec), videoDuration)
            }
            return MergingMediaSource(true, false, *children.toTypedArray())
        }
    }
}

internal data class AudioSpec(
    val uri: Uri,
    val listIndex: Int,
    val name: String?,
    val language: String?,
    val mime: String?,
    val delayMs: Long,
) {
    fun toMediaItem(): MediaItem {
        val builder = MediaItem.Builder().setUri(uri).setMediaId("$EXTERNAL_AUDIO_PREFIX$listIndex")
        if (!mime.isNullOrBlank()) builder.setMimeType(mime)
        return builder.build()
    }
}

internal data class ExternalAudioTag(val tracks: List<AudioSpec>) {
    fun key(): String = tracks.joinToString("\n") {
        "${it.uri}\u0001${it.listIndex}\u0001${it.language}\u0001${it.name}\u0001${it.mime}\u0001${it.delayMs}"
    }

    companion object {
        fun keyOf(item: MediaItem): String = (item.localConfiguration?.tag as? ExternalAudioTag)?.key().orEmpty()
    }
}

@OptIn(UnstableApi::class)
private class RelabelSource(
    source: MediaSource,
    private val spec: AudioSpec,
) : WrappingMediaSource(source) {
    override fun createPeriod(id: MediaSource.MediaPeriodId, allocator: Allocator, startPositionUs: Long): MediaPeriod =
        RelabelPeriod(mediaSource.createPeriod(id, allocator, startPositionUs), spec)

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        mediaSource.releasePeriod((mediaPeriod as RelabelPeriod).inner)
    }
}

@OptIn(UnstableApi::class)
private class RelabelPeriod(
    val inner: MediaPeriod,
    private val spec: AudioSpec,
) : MediaPeriod by inner {
    private val originalByRelabeled = IdentityHashMap<TrackGroup, TrackGroup>()
    private var groups: TrackGroupArray? = null

    override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
        inner.prepare(object : MediaPeriod.Callback {
            override fun onPrepared(mediaPeriod: MediaPeriod) {
                groups = relabel(inner.trackGroups)
                callback.onPrepared(this@RelabelPeriod)
            }

            override fun onContinueLoadingRequested(source: MediaPeriod) {
                callback.onContinueLoadingRequested(this@RelabelPeriod)
            }
        }, positionUs)
    }

    override fun getTrackGroups(): TrackGroupArray = groups ?: TrackGroupArray()

    override fun selectTracks(
        selections: Array<out ExoTrackSelection?>,
        mayRetainStreamFlags: BooleanArray,
        streams: Array<out SampleStream?>,
        streamResetFlags: BooleanArray,
        positionUs: Long,
    ): Long {
        val mapped = Array<ExoTrackSelection?>(selections.size) { index ->
            val selection = selections[index] ?: return@Array null
            val original = originalByRelabeled[selection.trackGroup]
            if (original == null) selection else RemapSelection(selection, original)
        }
        return inner.selectTracks(mapped, mayRetainStreamFlags, streams, streamResetFlags, positionUs)
    }

    private fun relabel(source: TrackGroupArray): TrackGroupArray {
        val result = Array(source.length) { index ->
            val group = source[index]
            val formats = Array(group.length) { track ->
                relabelFormat(group.getFormat(track), spec, group.length, track)
            }
            // Свой id без двоеточия: склейка пишет индекс ребёнка до первого ':'.
            TrackGroup("$EXTERNAL_AUDIO_PREFIX${spec.listIndex}".replace(":", ""), *formats)
                .also { originalByRelabeled[it] = group }
        }
        return TrackGroupArray(*result)
    }
}

/**
 * Media3 1.11 throws if [Format.label] is set and is not one of [Format.labels].
 * Matroska already fills `labels` from the track title, so a bare [Format.Builder.setLabel]
 * crashes inside the playback thread. The name and the list are written together.
 */
@OptIn(UnstableApi::class)
internal fun relabelFormat(format: Format, spec: AudioSpec, groupLength: Int, track: Int): Format {
    val builder = format.buildUpon()
        .setId("$EXTERNAL_AUDIO_PREFIX${spec.listIndex}" + if (groupLength > 1) "#$track" else "")
    val lang = spec.language?.let { TrackRules.normalizeLang(it) ?: it }
    if (lang != null) builder.setLanguage(lang)
    val name = spec.name?.takeIf { it.isNotBlank() }
    if (name != null) {
        builder.setLabel(name)
        builder.setLabels(listOf(Label(lang ?: format.language, name)))
    } else if (lang != null && format.labels.isNotEmpty()) {
        val updated = format.labels.map { Label(lang, it.value) }
        builder.setLabels(updated)
        val current = format.label
        if (current != null && updated.any { it.value == current }) builder.setLabel(current)
    }
    return builder.build()
}

@OptIn(UnstableApi::class)
private class RemapSelection(
    selection: ExoTrackSelection,
    private val original: TrackGroup,
) : ForwardingTrackSelection(selection) {
    override fun getTrackGroup(): TrackGroup = original
}

/** Длительность видео, известная после его timeline. Внешний звук длиннее неё обрезается. */
internal class VideoDuration {
    @Volatile var us: Long = C.TIME_UNSET
}

@OptIn(UnstableApi::class)
private class DurationProbe(
    source: MediaSource,
    private val slot: VideoDuration,
) : WrappingMediaSource(source) {
    override fun onChildSourceInfoRefreshed(newTimeline: Timeline) {
        if (newTimeline.periodCount > 0) {
            val period = Timeline.Period()
            newTimeline.getPeriod(0, period)
            if (period.durationUs != C.TIME_UNSET && period.durationUs > 0) slot.us = period.durationUs
        }
        super.onChildSourceInfoRefreshed(newTimeline)
    }
}

/**
 * Сэмплы внешнего звука за концом видео становятся концом потока.
 * Иначе отдача AC3 на ресивер продолжает часы после ролика, позиция уходит за длительность,
 * и плеер не переходит в конец.
 */
@OptIn(UnstableApi::class)
internal fun clipExternalToVideo(period: MediaPeriod, videoDurationUs: Long): MediaPeriod =
    if (videoDurationUs != C.TIME_UNSET && videoDurationUs > 0) {
        ClippingMediaPeriod(period, /* enableInitialDiscontinuity = */ false, /* startUs = */ 0, videoDurationUs)
    } else {
        period
    }

/**
 * Короткий аудиофайл не должен ронять перемотку общего источника:
 * [MergingMediaPeriod] требует, чтобы все дети вернули ту же позицию.
 * Длинный обрезается длительностью видео, чтобы часы отдачи не шли после ролика.
 */
@OptIn(UnstableApi::class)
private class LenientSource(
    source: MediaSource,
    private val videoDuration: VideoDuration,
) : WrappingMediaSource(source) {
    @Volatile private var durationUs: Long = C.TIME_UNSET

    override fun onChildSourceInfoRefreshed(newTimeline: Timeline) {
        if (newTimeline.periodCount > 0) {
            val period = Timeline.Period()
            newTimeline.getPeriod(0, period)
            durationUs = period.durationUs
        }
        super.onChildSourceInfoRefreshed(newTimeline)
    }

    override fun createPeriod(id: MediaSource.MediaPeriodId, allocator: Allocator, startPositionUs: Long): MediaPeriod {
        val raw = mediaSource.createPeriod(id, allocator, startPositionUs)
        return LenientPeriod(clipExternalToVideo(raw, videoDuration.us)) { durationUs }
    }

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        val inner = (mediaPeriod as LenientPeriod).inner
        val raw = (inner as? ClippingMediaPeriod)?.mediaPeriod ?: inner
        mediaSource.releasePeriod(raw)
    }
}

@OptIn(UnstableApi::class)
private class LenientPeriod(
    val inner: MediaPeriod,
    private val durationUs: () -> Long,
) : MediaPeriod by inner {
    // MergingMediaPeriod снимает ребёнка с ожидания только если onPrepared получил тот же
    // экземпляр, который он передал в prepare. Делегация `by inner` сообщала внутренний период,
    // список не пустел, и общий источник не готовился: спиннер на 0:00, без MediaCodec.
    override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
        inner.prepare(object : MediaPeriod.Callback {
            override fun onPrepared(mediaPeriod: MediaPeriod) {
                callback.onPrepared(this@LenientPeriod)
            }

            override fun onContinueLoadingRequested(source: MediaPeriod) {
                callback.onContinueLoadingRequested(this@LenientPeriod)
            }
        }, positionUs)
    }

    override fun selectTracks(
        selections: Array<out ExoTrackSelection?>,
        mayRetainStreamFlags: BooleanArray,
        streams: Array<out SampleStream?>,
        streamResetFlags: BooleanArray,
        positionUs: Long,
    ): Long {
        // Сырой AC3 (Ac3Extractor без своей шкалы) возвращает 0. Склейка требует ту же
        // позицию, что у видео, иначе IllegalStateException в selectTracks.
        inner.selectTracks(selections, mayRetainStreamFlags, streams, streamResetFlags, positionUs)
        return positionUs
    }

    override fun seekToUs(positionUs: Long): Long {
        val dur = durationUs()
        val target = if (dur != C.TIME_UNSET && dur > 0 && positionUs >= dur) (dur - 1).coerceAtLeast(0) else positionUs
        inner.seekToUs(target)
        return if (pastEnd(positionUs)) positionUs else target
    }

    override fun readDiscontinuity(): Long = C.TIME_UNSET

    override fun getBufferedPositionUs(): Long {
        val buffered = inner.getBufferedPositionUs()
        val dur = durationUs()
        if (buffered == C.TIME_END_OF_SOURCE) return buffered
        if (dur != C.TIME_UNSET && dur > 0 && buffered >= dur - 1) return C.TIME_END_OF_SOURCE
        return buffered
    }

    private fun pastEnd(positionUs: Long): Boolean {
        val dur = durationUs()
        return dur != C.TIME_UNSET && dur > 0 && positionUs >= dur
    }
}
