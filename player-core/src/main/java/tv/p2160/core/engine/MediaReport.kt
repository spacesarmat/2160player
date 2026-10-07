package tv.p2160.core.engine

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import tv.p2160.core.i18n.Strings

/** Строка сводки: подпись, значение и уровень важности. */
data class ReportRow(val label: String, val value: String, val level: Level = Level.INFO) {
    enum class Level { INFO, OK, WARN }
}

data class ReportSection(val title: String, val rows: List<ReportRow>)

data class MediaReport(val sections: List<ReportSection>, val warnings: List<String>)

/** Какие декодеры реально работают сейчас (из AnalyticsListener). */
data class ActiveDecoders(
    val video: String? = null,
    val audio: String? = null,
    /** Звук уходит на ресивер без декодирования (AC3/E-AC3/DTS/TrueHD passthrough). */
    val audioPassthrough: Boolean = false,
)

/**
 * Сводка «что внутри и как это будет играть»: контейнер, видео (кодек, HDR/Dolby Vision),
 * дорожки, какие декодеры реально выбраны и чего устройство не умеет аппаратно.
 */
@OptIn(UnstableApi::class)
object MediaReporter {

    fun build(
        context: Context,
        title: String,
        container: String?,
        durationMs: Long,
        tracks: Tracks,
        decoders: ActiveDecoders,
        t: Strings,
    ): MediaReport {
        val warnings = mutableListOf<String>()
        val sections = mutableListOf<ReportSection>()

        sections += ReportSection(
            t["info.file"],
            listOfNotNull(
                ReportRow(t["info.title"], title),
                container?.let { ReportRow(t["info.container"], it) },
                durationMs.takeIf { it > 0 }?.let { ReportRow(t["info.duration"], tv.p2160.core.ui.formatTime(it)) },
            ),
        )

        // Видео.
        val video = selectedFormat(tracks, C.TRACK_TYPE_VIDEO)
        if (video != null) {
            val rows = mutableListOf<ReportRow>()
            rows += ReportRow(t["info.codec"], videoCodecName(video))
            if (video.width > 0) rows += ReportRow(t["info.resolution"], "${video.width}×${video.height}${resolutionClass(video)}")
            if (video.frameRate > 0) rows += ReportRow(t["info.fps"], "%.3f".format(video.frameRate).trimEnd('0').trimEnd('.'))
            if (video.bitrate > 0) rows += ReportRow(t["info.bitrate"], t.format("track.mbps", video.bitrate / 1_000_000f))

            val hdr = hdrName(video)
            if (hdr != null) {
                rows += ReportRow("HDR", hdr)
                val displayHdr = displayHdrTypes(context)
                if (displayHdr != null && displayHdr.isEmpty()) {
                    warnings += t["info.warn_sdr_display"]
                }
            }
            val dv = isDolbyVision(video)
            if (dv) {
                // Маркер — плеер уже переключился на базовый слой: декодера Dolby Vision нет или он не умеет этот профиль.
                val dvDecoder = DolbyVisionFallback.marker(video) == null && hasDecoder(MimeTypes.VIDEO_DOLBY_VISION, hardwareOnly = true)
                if (!dvDecoder) {
                    rows += ReportRow("Dolby Vision", t["info.dv_fallback"], ReportRow.Level.WARN)
                    warnings += t["info.warn_dv"]
                }
            }

            val hw = hardwareSupports(context, video)
            val active = decoders.video
            rows += when {
                active != null && isSoftware(active) -> ReportRow(t["info.decoder"], "${t["info.decoder_sw"]} ($active)", ReportRow.Level.WARN)
                active != null -> ReportRow(t["info.decoder"], "${t["info.decoder_hw"]} ($active)", ReportRow.Level.OK)
                hw -> ReportRow(t["info.decoder"], t["info.decoder_hw"], ReportRow.Level.OK)
                else -> ReportRow(t["info.decoder"], t["info.decoder_sw"], ReportRow.Level.WARN)
            }
            if (!hw || (active != null && isSoftware(active))) {
                warnings += if (video.height >= 2000) t["info.warn_video_sw_4k"] else t["info.warn_video_sw"]
            }
            sections += ReportSection(t["info.video"], rows)
        }

        // Аудио: все дорожки + как играет выбранная.
        val audioRows = mutableListOf<ReportRow>()
        TrackLabels.collect(tracks, C.TRACK_TYPE_AUDIO, t).forEach { o ->
            val f = tracks.groups[o.groupIndex].getTrackFormat(o.trackIndex)
            val mark = if (o.selected) "▶ " else ""
            val level = when {
                !o.supported -> ReportRow.Level.WARN
                o.selected -> ReportRow.Level.OK
                else -> ReportRow.Level.INFO
            }
            val how = when {
                !o.supported -> t["info.unsupported"]
                o.selected && decoders.audioPassthrough -> t["info.passthrough"]
                o.selected && decoders.audio != null && isSoftware(decoders.audio) -> t["info.decoder_sw"]
                o.selected && decoders.audio != null -> t["info.decoder_hw"]
                hasDecoder(f.sampleMimeType, hardwareOnly = false) -> t["info.decoder_system"]
                else -> "FFmpeg"
            }
            audioRows += ReportRow(mark + o.label, how, level)
        }
        if (audioRows.isNotEmpty()) sections += ReportSection(t["info.audio"], audioRows)

        val textRows = TrackLabels.collect(tracks, C.TRACK_TYPE_TEXT, t).map { o ->
            ReportRow((if (o.selected) "▶ " else "") + o.label, if (o.external) t["track.external"] else t["info.embedded"])
        }
        if (textRows.isNotEmpty()) sections += ReportSection(t["info.subtitles"], textRows)

        return MediaReport(sections, warnings.distinct())
    }

    private fun selectedFormat(tracks: Tracks, type: Int): Format? {
        tracks.groups.filter { it.type == type }.forEach { g ->
            for (i in 0 until g.length) if (g.isTrackSelected(i)) return g.getTrackFormat(i)
        }
        return tracks.groups.firstOrNull { it.type == type }?.getTrackFormat(0)
    }

    private fun videoCodecName(f: Format): String {
        val base = TrackLabels.label(f, C.TRACK_TYPE_VIDEO, Strings.Empty).split(" · ").getOrNull(1)
            ?: f.sampleMimeType.orEmpty()
        val profile = f.codecs?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        return base + profile
    }

    private fun resolutionClass(f: Format): String = when {
        f.height >= 2000 || f.width >= 3800 -> " · 4K"
        f.height >= 1000 || f.width >= 1900 -> " · Full HD"
        f.height >= 700 -> " · HD"
        else -> ""
    }

    private fun isDolbyVision(f: Format): Boolean =
        DolbyVisionFallback.marker(f) != null ||
        f.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION ||
            f.codecs?.let { it.startsWith("dvh") || it.startsWith("dva") || it.startsWith("dav1") } == true

    private fun hdrName(f: Format): String? = when {
        isDolbyVision(f) -> "Dolby Vision" + (
            (DolbyVisionFallback.marker(f)?.profile ?: DolbyVisionFallback.profile(f.codecs))?.let { " (profile $it)" } ?: ""
        )
        f.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 -> if (f.colorInfo?.hdrStaticInfo != null) "HDR10" else "HDR10 / PQ"
        f.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG -> "HLG"
        else -> null
    }

    /** null — узнать нельзя; пустой список — экран не HDR. */
    private fun displayHdrTypes(context: Context): IntArray? = runCatching {
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY) ?: return null
        @Suppress("DEPRECATION")
        display.hdrCapabilities?.supportedHdrTypes
    }.getOrNull()

    private fun hardwareSupports(context: Context, f: Format): Boolean = runCatching {
        val mime = f.sampleMimeType ?: return false
        MediaCodecUtil.getDecoderInfos(mime, false, false).any { it.hardwareAccelerated && it.isFormatSupported(context, f) }
    }.getOrDefault(false)

    private fun hasDecoder(mime: String?, hardwareOnly: Boolean): Boolean = runCatching {
        mime ?: return false
        MediaCodecUtil.getDecoderInfos(mime, false, false).any { !hardwareOnly || it.hardwareAccelerated }
    }.getOrDefault(false)

    /** Программные декодеры: FFmpeg (nextlib) и системные c2.android/OMX.google. */
    fun isSoftware(name: String): Boolean {
        val n = name.lowercase()
        return "ffmpeg" in n || n.startsWith("c2.android.") || n.startsWith("omx.google.") || n.startsWith("c2.google.")
    }
}
