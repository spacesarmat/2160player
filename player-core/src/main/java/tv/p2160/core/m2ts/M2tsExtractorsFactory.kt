package tv.p2160.core.m2ts

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser

/** Язык/подпись дорожек по PID для конкретного файла (из MPLS STN). */
class PidTrackHints(
    val languageForPid: (Int) -> String?,
    val labelForPid: (Int) -> String? = { null },
)

/**
 * ExtractorsFactory: первым идёт [M2tsExtractor] (TS 188 и M2TS 192 байта, типы потоков Blu-ray),
 * за ним — все экстракторы [fallback] для прочих форматов.
 *
 * Штатный TsExtractor M2TS не распознаёт вовсе (sniff ищет sync-байты с шагом 188), поэтому
 * [M2tsExtractor] стоит первым всегда: его sniff — это peek ~1,2 КБ.
 *
 * Настройки субтитров, которые DefaultMediaSourceFactory проталкивает в фабрику
 * ([setSubtitleParserFactory], [experimentalSetTextTrackTranscodingEnabled]), применяются и к
 * [M2tsExtractor], и к [fallback]. По умолчанию (как в Media3 ≥ 1.4) PGS транскодируется в cues
 * при извлечении через [DefaultSubtitleParserFactory] → PgsParser.
 *
 * @param hintsForUri язык/подписи дорожек по PID для данного URI (null — брать из PMT).
 * @param forceHdmv трактовать и 188-байтный TS как Blu-ray (0x80 LPCM, 0x86 DTS-HD MA).
 */
@OptIn(UnstableApi::class)
class M2tsExtractorsFactory @JvmOverloads constructor(
    private val fallback: ExtractorsFactory = DefaultExtractorsFactory(),
    private val tsReaderFlags: Int = BlurayTsPayloadReaderFactory.DEFAULT_FLAGS,
    private val exposeTrueHdAc3Core: Boolean = false,
    private val forceHdmv: Boolean = false,
    private val hintsForUri: ((Uri?) -> PidTrackHints?)? = null,
) : ExtractorsFactory {

    @Volatile private var subtitleParserFactory: SubtitleParser.Factory = DefaultSubtitleParserFactory()
    @Volatile private var textTrackTranscodingEnabled = true

    override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): ExtractorsFactory {
        this.subtitleParserFactory = subtitleParserFactory
        fallback.setSubtitleParserFactory(subtitleParserFactory)
        return this
    }

    @Deprecated("Media3: legacy subtitle decoding path")
    @OptIn(androidx.media3.common.util.ExperimentalApi::class)
    override fun experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled: Boolean): ExtractorsFactory {
        this.textTrackTranscodingEnabled = textTrackTranscodingEnabled
        @Suppress("DEPRECATION")
        fallback.experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled)
        return this
    }

    override fun createExtractors(): Array<Extractor> =
        arrayOf(createM2tsExtractor(null), *fallback.createExtractors())

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        arrayOf(createM2tsExtractor(uri), *fallback.createExtractors(uri, responseHeaders))

    /** Отдельный экстрактор — например, для ProgressiveMediaSource.Factory с заведомо M2TS. */
    fun createM2tsExtractor(uri: Uri?): M2tsExtractor {
        val hints = hintsForUri?.invoke(uri)
        return M2tsExtractor(
            payloadReaderFactory = { isM2ts ->
                BlurayTsPayloadReaderFactory(
                    defaultFlags = tsReaderFlags,
                    hdmv = isM2ts || forceHdmv,
                    languageForPid = hints?.languageForPid,
                    labelForPid = hints?.labelForPid,
                    exposeTrueHdAc3Core = exposeTrueHdAc3Core,
                )
            },
            subtitleParserFactory = subtitleParserFactory,
            emitRawSubtitleData = !textTrackTranscodingEnabled,
        )
    }
}
