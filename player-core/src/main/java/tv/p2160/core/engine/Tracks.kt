package tv.p2160.core.engine

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import tv.p2160.core.i18n.Strings
import java.util.Locale

/** Дорожка, пригодная для показа в UI и выбора. */
data class TrackOption(
    val type: Int,
    val groupIndex: Int,
    val trackIndex: Int,
    val label: String,
    val language: String?,
    val rawLabel: String?,
    val selected: Boolean,
    val supported: Boolean,
    val external: Boolean,
)

internal const val EXTERNAL_SUB_PREFIX = "ext:"

@OptIn(UnstableApi::class)
internal object TrackLabels {

    fun collect(tracks: Tracks, type: Int, strings: Strings): List<TrackOption> =
        numberDuplicates(collectRaw(tracks, type, strings))

    /** Одинаковые подписи («Русский · PGS» ×3 на Blu-ray) различаем номером. */
    private fun numberDuplicates(options: List<TrackOption>): List<TrackOption> {
        val counts = options.groupingBy { it.label }.eachCount()
        val seen = HashMap<String, Int>()
        return options.map { o ->
            if ((counts[o.label] ?: 0) < 2) o
            else o.copy(label = "${o.label} #${seen.merge(o.label, 1, Int::plus)}")
        }
    }

    private fun collectRaw(tracks: Tracks, type: Int, strings: Strings): List<TrackOption> = buildList {
        tracks.groups.forEachIndexed { g, group ->
            if (group.type != type) return@forEachIndexed
            for (i in 0 until group.length) {
                val f = group.getTrackFormat(i)
                add(
                    TrackOption(
                        type = type,
                        groupIndex = g,
                        trackIndex = i,
                        label = label(f, type, strings),
                        language = f.language?.takeUnless { it == C.LANGUAGE_UNDETERMINED },
                        rawLabel = f.label,
                        selected = group.isTrackSelected(i),
                        supported = group.isTrackSupported(i, /* allowExceedsCapabilities = */ true),
                        external = f.id?.contains(EXTERNAL_SUB_PREFIX) == true,
                    )
                )
            }
        }
    }

    fun label(f: Format, type: Int, strings: Strings): String {
        val parts = mutableListOf<String>()
        val lang = languageName(f.language, strings.locale)
        val name = f.label?.takeIf { it.isNotBlank() && !it.equals(lang, ignoreCase = true) }
        when (type) {
            C.TRACK_TYPE_AUDIO -> {
                lang?.let(parts::add)
                name?.let(parts::add)
                audioCodec(f)?.let(parts::add)
                channels(f.channelCount, strings)?.let(parts::add)
            }
            C.TRACK_TYPE_TEXT -> {
                lang?.let(parts::add)
                name?.let(parts::add)
                textCodec(f)?.let(parts::add)
                if (f.selectionFlags and C.SELECTION_FLAG_FORCED != 0) parts += strings["track.forced"]
                if (f.id?.contains(EXTERNAL_SUB_PREFIX) == true) parts += strings["track.external"]
            }
            C.TRACK_TYPE_VIDEO -> {
                if (f.width > 0 && f.height > 0) parts += "${f.width}×${f.height}"
                videoCodec(f)?.let(parts::add)
                hdr(f)?.let(parts::add)
                if (f.frameRate > 0) parts += "%.3g fps".format(Locale.US, f.frameRate)
                if (f.bitrate > 0) parts += strings.format("track.mbps", f.bitrate / 1_000_000f)
            }
        }
        return parts.joinToString(" · ").ifEmpty { strings.format("track.unknown", f.id.orEmpty()).trim() }
    }

    fun languageName(code: String?, locale: Locale): String? {
        if (code.isNullOrBlank() || code == C.LANGUAGE_UNDETERMINED) return null
        val name = Locale.forLanguageTag(code).getDisplayLanguage(locale)
        return name.ifBlank { code }.replaceFirstChar { it.titlecase(locale) }
    }

    private fun audioCodec(f: Format): String? = when (f.sampleMimeType) {
        MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_AC3 -> "AC3"
        MimeTypes.AUDIO_E_AC3 -> "E-AC3"
        MimeTypes.AUDIO_E_AC3_JOC -> "E-AC3 Atmos"
        MimeTypes.AUDIO_AC4 -> "AC4"
        MimeTypes.AUDIO_TRUEHD -> "TrueHD"
        MimeTypes.AUDIO_DTS -> "DTS"
        MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
        MimeTypes.AUDIO_DTS_EXPRESS -> "DTS Express"
        MimeTypes.AUDIO_DTS_X -> "DTS:X"
        MimeTypes.AUDIO_MPEG, MimeTypes.AUDIO_MPEG_L2 -> "MP3"
        MimeTypes.AUDIO_FLAC -> "FLAC"
        MimeTypes.AUDIO_ALAC -> "ALAC"
        MimeTypes.AUDIO_OPUS -> "Opus"
        MimeTypes.AUDIO_VORBIS -> "Vorbis"
        MimeTypes.AUDIO_RAW -> "PCM"
        MimeTypes.AUDIO_WAV -> "WAV"
        else -> f.sampleMimeType?.substringAfter('/')?.uppercase()
    }

    // Субтитры, разобранные при извлечении, приходят как media3-cues; исходный формат лежит в codecs.
    private fun textCodec(f: Format): String? = when (
        if (f.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES) f.codecs else f.sampleMimeType ?: f.codecs
    ) {
        MimeTypes.APPLICATION_SUBRIP -> "SRT"
        MimeTypes.TEXT_SSA -> "ASS"
        MimeTypes.TEXT_VTT -> "VTT"
        MimeTypes.APPLICATION_TTML -> "TTML"
        MimeTypes.APPLICATION_PGS -> "PGS"
        MimeTypes.APPLICATION_VOBSUB -> "VobSub"
        MimeTypes.APPLICATION_DVBSUBS -> "DVB"
        MimeTypes.APPLICATION_CEA608, MimeTypes.APPLICATION_CEA708 -> "CC"
        MimeTypes.APPLICATION_TX3G -> "TX3G"
        else -> null
    }

    private fun videoCodec(f: Format): String? = when (f.sampleMimeType) {
        MimeTypes.VIDEO_H264 -> "AVC"
        MimeTypes.VIDEO_H265 -> "HEVC"
        MimeTypes.VIDEO_AV1 -> "AV1"
        MimeTypes.VIDEO_VP9 -> "VP9"
        MimeTypes.VIDEO_VP8 -> "VP8"
        MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
        MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
        MimeTypes.VIDEO_VC1 -> "VC-1"
        MimeTypes.VIDEO_MP4V -> "MPEG-4"
        else -> f.sampleMimeType?.substringAfter('/')?.uppercase()
    }

    private fun hdr(f: Format): String? = when (f.colorInfo?.colorTransfer) {
        C.COLOR_TRANSFER_ST2084 -> "HDR10"
        C.COLOR_TRANSFER_HLG -> "HLG"
        else -> null
    }

    private fun channels(count: Int, strings: Strings): String? = when (count) {
        Format.NO_VALUE, 0 -> null
        1 -> strings["track.mono"]
        2 -> "2.0"
        3 -> "2.1"
        6 -> "5.1"
        7 -> "6.1"
        8 -> "7.1"
        else -> "${count}ch"
    }
}
