package tv.p2160.core.engine

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegLibrary
import tv.p2160.core.settings.DecoderPreference

/**
 * После ошибки MediaCodec посреди потока (аппаратный декодер уже отдал первые кадры)
 * [androidx.media3.exoplayer.DefaultRenderersFactory.setEnableDecoderFallback] сам следующий
 * декодер не берёт: запасной список используется только на ошибке инициализации.
 * Для этого MIME сначала оставляем только программные MediaCodec (c2.android / OMX.google),
 * затем FFmpeg, но только если [ffmpegCanDecode] истинно: имя в таблице кодеков
 * ещё не значит, что декодер собран в libavcodec. Иначе видео молча пропадает и играет звук.
 * Режим общий на видео не переключается: H.264 в том же плейлисте остаётся аппаратным.
 */
@OptIn(UnstableApi::class)
internal class VideoDecoderGate {
    private val stage = HashMap<String, Int>()

    @Synchronized
    fun stage(mime: String): Int = stage[mime] ?: DEFAULT

    /**
     * @return SOFTWARE или FFMPEG, если надо пересобрать декодер, иначе null (ошибку показываем).
     */
    @Synchronized
    fun advance(
        mime: String,
        failedDecoder: String?,
        allowFallback: Boolean,
        ffmpegAvailable: Boolean = ffmpegCanDecode(mime),
    ): Int? {
        if (!allowFallback || !MimeTypes.isVideo(mime)) return null
        if (failedDecoder != null && "ffmpeg" in failedDecoder.lowercase()) return null
        val now = stage[mime] ?: DEFAULT
        if (now >= FFMPEG) return null
        val next = when {
            now == DEFAULT && failedDecoder != null && MediaReporter.isSoftware(failedDecoder) -> FFMPEG
            now == DEFAULT -> SOFTWARE
            else -> FFMPEG
        }
        if (next == FFMPEG && !ffmpegAvailable) return null
        stage[mime] = next
        return next
    }

    companion object {
        const val DEFAULT = 0
        const val SOFTWARE = 1
        const val FFMPEG = 2
    }
}

/**
 * Имя в [FfmpegLibrary.getCodecName] ещё не декодер. На устройстве это
 * [FfmpegLibrary.supportsFormat]: оно вызывает avcodec_find_decoder и для mpeg4/msmpeg4
 * в сборке nextlib возвращает false (в .so только таблица имён, без кода декодера).
 */
@OptIn(UnstableApi::class)
internal fun ffmpegCanDecode(mime: String?): Boolean {
    if (mime.isNullOrEmpty()) return false
    return try {
        FfmpegLibrary.isAvailable() && FfmpegLibrary.supportsFormat(mime)
    } catch (_: Throwable) {
        false
    }
}

/** MIME, для которых getCodecName вообще знает имя. Не значит, что декодер собран. */
internal fun ffmpegMaps(mime: String?): Boolean = when (mime) {
    "audio/mp4a-latm", "audio/mpeg", "audio/mpeg-L1", "audio/mpeg-L2",
    "audio/ac3", "audio/eac3", "audio/eac3-joc", "audio/true-hd",
    "audio/vnd.dts", "audio/vnd.dts.hd", "audio/vorbis", "audio/opus",
    "audio/3gpp", "audio/amr-wb", "audio/flac", "audio/alac",
    "audio/g711-mlaw", "audio/g711-alaw",
    "video/avc", "video/hevc", "video/av01", "video/mpeg", "video/mpeg2",
    "video/x-vnd.on2.vp8", "video/x-vnd.on2.vp9",
    "video/mp4v-es", "video/mp42", "video/mp43", "video/3gpp",
    -> true
    else -> false
}

@OptIn(UnstableApi::class)
internal fun codecLabel(mime: String?): String = when (mime) {
    MimeTypes.VIDEO_MP4V -> "MPEG-4 (XviD/DivX)"
    MimeTypes.VIDEO_MP42, MimeTypes.VIDEO_MP43 -> "MS-MPEG4 (DIV3)"
    MimeTypes.VIDEO_H264 -> "H.264"
    MimeTypes.VIDEO_H265 -> "H.265"
    MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
    MimeTypes.VIDEO_MPEG -> "MPEG-1"
    MimeTypes.VIDEO_VP8 -> "VP8"
    MimeTypes.VIDEO_VP9 -> "VP9"
    MimeTypes.VIDEO_AV1 -> "AV1"
    MimeTypes.VIDEO_H263 -> "H.263"
    MimeTypes.AUDIO_AC3 -> "AC-3"
    MimeTypes.AUDIO_E_AC3 -> "E-AC-3"
    MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD -> "DTS"
    MimeTypes.AUDIO_MPEG -> "MP3"
    MimeTypes.AUDIO_AAC -> "AAC"
    null, "" -> "unknown"
    else -> mime
}

/**
 * Ключ строки ошибки декодера.
 * Если декодера нет в сборке, не советуем режим FFmpeg: он этот кодек не откроет.
 */
internal fun decoderErrorKey(
    mime: String?,
    mode: DecoderPreference,
    stage: Int,
    ffmpegAvailable: Boolean = ffmpegCanDecode(mime),
): String {
    if (mime == null) return "error.decoder"
    val tried = mode == DecoderPreference.FFMPEG || stage >= VideoDecoderGate.FFMPEG
    return when {
        !ffmpegAvailable -> "error.decoder_codec_no_ffmpeg"
        tried -> "error.decoder_codec_ffmpeg"
        else -> "error.decoder_codec"
    }
}

/**
 * MIME видео, которое есть в файле, но ни один декодер его не берёт.
 * null - видео нет, оно отключено (фон) или хотя бы одна дорожка поддерживается.
 * Обложки и вспомогательные дорожки не считаются: из-за них звук без картинки не ошибка.
 */
@OptIn(UnstableApi::class)
internal fun unsupportedVideoMime(groups: List<Tracks.Group>, videoDisabled: Boolean): String? {
    if (videoDisabled) return null
    var mime: String? = null
    var sawMainVideo = false
    for (group in groups) {
        if (group.type != C.TRACK_TYPE_VIDEO) continue
        for (i in 0 until group.length) {
            val format: Format = group.getTrackFormat(i)
            val sampleMime = format.sampleMimeType
            if (sampleMime != null && MimeTypes.isImage(sampleMime)) continue
            if (format.roleFlags and C.ROLE_FLAG_AUXILIARY != 0) continue
            sawMainVideo = true
            if (group.isTrackSupported(i, /* allowExceedsCapabilities = */ true)) return null
            if (mime == null) mime = sampleMime
        }
    }
    return if (sawMainVideo) mime ?: "" else null
}

@OptIn(UnstableApi::class)
internal class VideoMimeFallbackSelector(private val gate: VideoDecoderGate) : MediaCodecSelector {
    override fun getDecoderInfos(
        mimeType: String,
        requiresSecureDecoder: Boolean,
        requiresTunnelingDecoder: Boolean,
    ): List<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> {
        val all = try {
            MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
        } catch (_: MediaCodecUtil.DecoderQueryException) {
            emptyList()
        }
        return when (gate.stage(mimeType)) {
            VideoDecoderGate.SOFTWARE -> all.filter { it.softwareOnly }
            VideoDecoderGate.FFMPEG -> emptyList()
            else -> all
        }
    }
}
