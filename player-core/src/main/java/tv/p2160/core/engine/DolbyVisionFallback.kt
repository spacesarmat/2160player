package tv.p2160.core.engine

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.TrackOutput

/**
 * Dolby Vision, которого устройство не умеет (чаще всего профиль 7 из UHD Blu-ray-ремуксов), Media3
 * просто отбрасывает — видео пропадает. Такой поток совместим с HEVC/AVC/AV1: подменяем формат на
 * базовый кодек, декодер играет базовый слой (HDR10), слой улучшения и RPU игнорируются.
 * Исходный профиль запоминаем в [Marker], чтобы сводка по файлу показала «Dolby Vision → HDR10».
 */
@OptIn(UnstableApi::class)
object DolbyVisionFallback {

    /** Отметка в [Format.metadata]: поток был Dolby Vision и играет по базовому слою. */
    data class Marker(val profile: Int?, val codecs: String?) : Metadata.Entry

    fun marker(format: Format): Marker? {
        val metadata = format.metadata ?: return null
        return (0 until metadata.length()).firstNotNullOfOrNull { metadata.get(it) as? Marker }
    }

    /** dvhe.07.06 → 7. */
    fun profile(codecs: String?): Int? = codecs?.split('.')?.getOrNull(1)?.toIntOrNull()

    /** Кодек базового слоя по строке codecs; null — совместимого слоя нет. */
    fun baseMimeType(codecs: String?, profile: Int?): String? {
        // Профиль 5 — без совместимого слоя (IPT): цвета будут неверными, пусть Media3 решает сама.
        if (profile == 5) return null
        val c = codecs?.lowercase() ?: return MimeTypes.VIDEO_H265
        return when {
            c.startsWith("dvhe") || c.startsWith("dvh1") -> MimeTypes.VIDEO_H265
            c.startsWith("dvav") || c.startsWith("dva1") -> MimeTypes.VIDEO_H264
            c.startsWith("dav1") -> MimeTypes.VIDEO_AV1
            else -> null
        }
    }

    private val supportCache = HashMap<String, Boolean>()

    private fun deviceSupports(context: Context, format: Format): Boolean = synchronized(supportCache) {
        supportCache.getOrPut(format.codecs.orEmpty()) {
            runCatching {
                MediaCodecUtil.getDecoderInfos(MimeTypes.VIDEO_DOLBY_VISION, false, false).any { it.isFormatSupported(context, format) }
            }.getOrDefault(false)
        }
    }

    /** Формат для декодера: исходный, если устройство умеет этот Dolby Vision, иначе базовый слой. */
    fun adapt(context: Context, format: Format): Format {
        if (format.sampleMimeType != MimeTypes.VIDEO_DOLBY_VISION) return format
        if (deviceSupports(context, format)) return format
        val profile = profile(format.codecs)
        val base = baseMimeType(format.codecs, profile) ?: return format
        val marker = Marker(profile, format.codecs)
        return format.buildUpon()
            .setSampleMimeType(base)
            .setCodecs(null)
            .setMetadata(format.metadata?.copyWithAppendedEntries(marker) ?: Metadata(marker))
            .build()
    }

    /** Оборачивает экстракторы фабрики: все видеоформаты проходят через [adapt]. */
    class ExtractorsFactoryWrapper(context: Context, private val delegate: ExtractorsFactory) : ExtractorsFactory by delegate {
        private val context = context.applicationContext

        override fun createExtractors(): Array<Extractor> = delegate.createExtractors().map(::wrap).toTypedArray()

        override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
            delegate.createExtractors(uri, responseHeaders).map(::wrap).toTypedArray()

        private fun wrap(extractor: Extractor): Extractor = object : Extractor by extractor {
            override fun init(output: ExtractorOutput) = extractor.init(object : ExtractorOutput by output {
                override fun track(id: Int, type: Int): TrackOutput {
                    val track = output.track(id, type)
                    return object : TrackOutput by track {
                        override fun format(format: Format) = track.format(adapt(context, format))
                    }
                }
            })
        }
    }
}
