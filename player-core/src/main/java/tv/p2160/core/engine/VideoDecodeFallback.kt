package tv.p2160.core.engine

import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecList
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegLibrary
import tv.p2160.core.avi.AviHeader
import tv.p2160.core.settings.DecoderPreference
import java.util.concurrent.atomic.AtomicBoolean

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
        softwareAvailable: Boolean = true,
    ): Int? {
        if (!allowFallback || !MimeTypes.isVideo(mime)) return null
        if (failedDecoder != null && "ffmpeg" in failedDecoder.lowercase()) return null
        val now = stage[mime] ?: DEFAULT
        if (now >= FFMPEG) return null
        var next = when {
            now == DEFAULT && failedDecoder != null && MediaReporter.isSoftware(failedDecoder) -> FFMPEG
            now == DEFAULT -> SOFTWARE
            else -> FFMPEG
        }
        // Пустой программный список не запоминаем: иначе повтор prepare выкидывает видео
        // и остаётся один звук (DIV3 на Dune, OMX.realtek.video.dec.mp43).
        if (next == SOFTWARE && !softwareAvailable) next = FFMPEG
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
    MimeTypes.VIDEO_MP4V -> "MPEG-4 XviD/DivX"
    MimeTypes.VIDEO_MP43 -> "MS-MPEG4 MP43"
    MimeTypes.VIDEO_MP42 -> "MS-MPEG4 MP42"
    AviHeader.MIME_DIVX3 -> "MS-MPEG4 DIV3"
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

/**
 * MS-MPEG4 v3 на части приставок объявлен не как video/mp43, а как video/divx3
 * (OMX.realtek.video.dec.mp43 и OMX.realtek.video.dec.divx3).
 */
internal fun isMsMpeg4Mime(mimeType: String): Boolean =
    mimeType == MimeTypes.VIDEO_MP43 || mimeType == MimeTypes.VIDEO_MP42 || mimeType == AviHeader.MIME_DIVX3

/**
 * Есть ли программный MediaCodec этого MIME (c2.android / OMX.google).
 * На Dune для video/divx3 и video/mp43 его нет: в списке только OMX.realtek.video.dec.mp43,
 * и OMX отказывает в создании с InvalidComponentName ещё до KEY_MIME. Пустой запасной
 * проход тогда нельзя запускать.
 */
@OptIn(UnstableApi::class)
internal fun softwareVideoDecoderExists(mime: String): Boolean {
    for (probe in decoderMimesFor(mime)) {
        val infos = try {
            MediaCodecSelector.DEFAULT.getDecoderInfos(probe, false, false)
        } catch (_: Throwable) {
            emptyList()
        }
        if (infos.any { it.softwareOnly }) return true
    }
    return false
}

internal fun decoderMimesFor(mimeType: String): List<String> = when {
    mimeType == AviHeader.MIME_DIVX3 -> listOf(AviHeader.MIME_DIVX3, MimeTypes.VIDEO_MP43, "video/divx", "video/x-divx")
    mimeType == MimeTypes.VIDEO_MP43 || mimeType == MimeTypes.VIDEO_MP42 ->
        listOf(mimeType, AviHeader.MIME_DIVX3, "video/divx", "video/x-divx").distinct()
    else -> listOf(mimeType)
}

/** Кодек устройства: имя и типы, которые он сам перечисляет. */
internal data class DeviceCodec(val name: String, val types: List<String>)

/** Имя декодера и MIME, который надо записать в MediaFormat.KEY_MIME. */
internal data class DeviceCodecChoice(val name: String, val codecMimeType: String)

private val MS_MPEG4_TYPES = listOf("video/mp43", "video/divx3", "video/divx", "video/x-divx", "video/mp42")

/**
 * Какие декодеры устройства вообще могут взять MS-MPEG4, и в каком порядке их пробовать.
 * Сначала video/mp43, затем video/divx3. Имя вроде OMX.realtek.video.dec.divx3 тоже считается,
 * даже если строка типа чуть другая.
 */
internal fun msMpeg4Candidates(codecs: List<DeviceCodec>, sampleMime: String): List<DeviceCodecChoice> {
    if (!isMsMpeg4Mime(sampleMime)) return emptyList()
    val preferDivx3 = sampleMime == AviHeader.MIME_DIVX3
    val ranked = ArrayList<Pair<Int, DeviceCodecChoice>>()
    for (codec in codecs) {
        val name = codec.name
        if (name.endsWith(".secure", ignoreCase = true)) continue
        val types = codec.types.map { it.trim() }.filter { it.isNotEmpty() }
        val lower = types.map { it.lowercase() }
        val alias = MS_MPEG4_TYPES.firstOrNull { it in lower }
        val lowerName = name.lowercase()
        val nameHit = "mp43" in lowerName || "divx3" in lowerName || "msmpeg4" in lowerName || ".divx" in lowerName
        if (alias == null && !nameHit) continue
        val codecMime = when {
            alias != null -> types.first { it.equals(alias, ignoreCase = true) }
            else -> types.firstOrNull { it.startsWith("video/", ignoreCase = true) } ?: "video/divx3"
        }
        val lowerMime = codecMime.lowercase()
        val rank = when {
            preferDivx3 && lowerMime == "video/divx3" -> 0
            preferDivx3 && (lowerMime == "video/mp43" || lowerMime == "video/mp42") -> 1
            !preferDivx3 && (lowerMime == "video/mp43" || lowerMime == "video/mp42") -> 0
            lowerMime == "video/divx3" -> 1
            lowerMime == "video/divx" || lowerMime == "video/x-divx" -> 2
            else -> 3
        }
        ranked += rank to DeviceCodecChoice(name, codecMime)
    }
    return ranked.sortedBy { it.first }.map { it.second }.distinctBy { it.name }
}

/**
 * Media3 сравнивает MediaCodecInfo.mimeType с MIME дорожки и отбрасывает video/divx3,
 * если дорожка помечена как video/mp43. mimeType оставляем как у дорожки, а в KEY_MIME
 * пишем тип, который объявил сам кодек.
 */
@OptIn(UnstableApi::class)
internal fun msMpeg4CodecInfo(
    name: String,
    sampleMime: String,
    codecMimeType: String,
    capabilities: CodecCapabilities?,
): MediaCodecInfo {
    val software = isSoftwareCodecName(name)
    return MediaCodecInfo.newInstance(
        name,
        sampleMime,
        codecMimeType,
        capabilities,
        /* hardwareAccelerated= */ !software,
        /* softwareOnly= */ software,
        /* vendor= */ !software,
        /* forceDisableAdaptive= */ true,
        /* forceSecure= */ false,
    )
}

private fun isSoftwareCodecName(name: String): Boolean {
    val n = name.lowercase()
    return n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.startsWith("c2.google.")
}

private val msMpeg4ScanCache = HashMap<String, List<MediaCodecInfo>>()

@OptIn(UnstableApi::class)
private fun scanMsMpeg4Decoders(sampleMime: String): List<MediaCodecInfo> {
    if (!isMsMpeg4Mime(sampleMime)) return emptyList()
    synchronized(msMpeg4ScanCache) {
        msMpeg4ScanCache[sampleMime]?.let { return it }
    }
    val found = scanMsMpeg4DecodersUncached(sampleMime)
    synchronized(msMpeg4ScanCache) {
        msMpeg4ScanCache.putIfAbsent(sampleMime, found)
    }
    return found
}

@OptIn(UnstableApi::class)
private fun scanMsMpeg4DecodersUncached(sampleMime: String): List<MediaCodecInfo> {
    val infos = try {
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
    } catch (_: Throwable) {
        return emptyList()
    }
    val devices = ArrayList<DeviceCodec>()
    val capsByName = HashMap<String, CodecCapabilities?>()
    for (info in infos) {
        if (info.isEncoder) continue
        val types = try {
            info.supportedTypes?.toList().orEmpty()
        } catch (_: Throwable) {
            emptyList()
        }
        devices += DeviceCodec(info.name, types)
    }
    val choices = msMpeg4Candidates(devices, sampleMime)
    val result = ArrayList<MediaCodecInfo>()
    for (choice in choices) {
        val platform = infos.firstOrNull { it.name == choice.name } ?: continue
        val caps = capsByName.getOrPut(choice.name) {
            try {
                platform.getCapabilitiesForType(choice.codecMimeType)
            } catch (_: Throwable) {
                null
            }
        }
        result += msMpeg4CodecInfo(choice.name, sampleMime, choice.codecMimeType, caps)
    }
    return result
}

private val msMpeg4Logged = AtomicBoolean(false)

@OptIn(UnstableApi::class)
internal class VideoMimeFallbackSelector(private val gate: VideoDecoderGate) : MediaCodecSelector {
    override fun getDecoderInfos(
        mimeType: String,
        requiresSecureDecoder: Boolean,
        requiresTunnelingDecoder: Boolean,
    ): List<MediaCodecInfo> {
        val merged = LinkedHashMap<String, MediaCodecInfo>()
        if (!requiresSecureDecoder && !requiresTunnelingDecoder) {
            for (info in scanMsMpeg4Decoders(mimeType)) merged.putIfAbsent(info.name, info)
        }
        for (mime in decoderMimesFor(mimeType)) {
            val found = try {
                MediaCodecSelector.DEFAULT.getDecoderInfos(mime, requiresSecureDecoder, requiresTunnelingDecoder)
            } catch (_: MediaCodecUtil.DecoderQueryException) {
                emptyList()
            }
            for (info in found) {
                val adapted = if (isMsMpeg4Mime(mimeType)) {
                    msMpeg4CodecInfo(info.name, mimeType, info.codecMimeType, info.capabilities)
                } else {
                    info
                }
                merged.putIfAbsent(adapted.name, adapted)
            }
        }
        if (merged.isNotEmpty() && isMsMpeg4Mime(mimeType) &&
            msMpeg4Logged.compareAndSet(false, true)
        ) {
            Log.i(
                "Player2160",
                "MS-MPEG4 $mimeType decoders: " + merged.values.joinToString { "${it.name} (${it.codecMimeType})" },
            )
        }
        val all = merged.values.toList()
        return when (gate.stage(mimeType)) {
            VideoDecoderGate.SOFTWARE -> all.filter { it.softwareOnly }
            VideoDecoderGate.FFMPEG -> emptyList()
            else -> all
        }
    }
}
