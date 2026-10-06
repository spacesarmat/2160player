package tv.p2160.core.m2ts

import android.util.SparseArray
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.ts.Ac3Reader
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.DtsReader
import androidx.media3.extractor.ts.ElementaryStreamReader
import androidx.media3.extractor.ts.PesReader
import androidx.media3.extractor.ts.TsPayloadReader

/**
 * Фабрика читателей ES для Blu-ray: добавляет типы потоков BDMV, остальное — [DefaultTsPayloadReaderFactory].
 *
 * | stream_type | что это (BD)                | читатель                         |
 * |-------------|-----------------------------|----------------------------------|
 * | 0x80        | LPCM                        | [BdLpcmReader] (только [hdmv])   |
 * | 0x81        | AC-3                        | штатный Ac3Reader                |
 * | 0x82        | DTS                         | DtsReader (только [hdmv])        |
 * | 0x83        | TrueHD (+ AC-3 ядро)        | [TrueHdReader]                   |
 * | 0x84, 0xA1  | E-AC-3 (основной/вторичный) | Ac3Reader                        |
 * | 0x85        | DTS-HD High Resolution      | DtsReader (ядро + ExSS)          |
 * | 0x86        | DTS-HD Master Audio         | DtsReader (только [hdmv])        |
 * | 0xA2        | DTS-HD LBR (вторичный)      | DtsReader                        |
 * | 0x90        | PGS                         | [PgsReader]                      |
 *
 * DtsReader создаётся так же, как Media3 делает для 0x88 (DTS-HD): максимальный заголовок ExSS
 * 4096 байт → ядро + расширение идут одним сэмплом, MIME — AUDIO_DTS_HD/AUDIO_DTS_EXPRESS по ExSS.
 *
 * @param hdmv трактовать неоднозначные типы по-блюрейному: 0x80 — LPCM (а не DigiCipher II видео),
 *   0x82 — DTS (а не SCTE-27), 0x86 — DTS-HD MA (а не SCTE-35). Для M2TS — всегда true.
 * @param languageForPid язык дорожки по PID (из STN-таблицы MPLS); null — оставить из PMT.
 * @param labelForPid подпись дорожки по PID; null — без подписи.
 * @param exposeTrueHdAc3Core выводить AC-3 ядро TrueHD отдельной дорожкой (id = PID + 0x2000).
 */
@OptIn(UnstableApi::class)
class BlurayTsPayloadReaderFactory @JvmOverloads constructor(
    defaultFlags: Int = DEFAULT_FLAGS,
    private val hdmv: Boolean = true,
    private val languageForPid: ((Int) -> String?)? = null,
    private val labelForPid: ((Int) -> String?)? = null,
    private val exposeTrueHdAc3Core: Boolean = false,
) : TsPayloadReader.Factory {

    private val delegate = DefaultTsPayloadReaderFactory(
        if (hdmv) defaultFlags or DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS else defaultFlags,
    )

    override fun createInitialPayloadReaders(): SparseArray<TsPayloadReader> =
        delegate.createInitialPayloadReaders()

    override fun createPayloadReader(streamType: Int, esInfo: TsPayloadReader.EsInfo): TsPayloadReader? {
        val language = esInfo.language
        val roleFlags = esInfo.roleFlags
        val es: ElementaryStreamReader? = when (streamType) {
            STREAM_TYPE_LPCM -> if (hdmv) BdLpcmReader(language, roleFlags) else null
            STREAM_TYPE_DTS -> if (hdmv) dts(language, roleFlags) else null
            STREAM_TYPE_TRUEHD -> TrueHdReader(language, roleFlags, exposeTrueHdAc3Core)
            STREAM_TYPE_EAC3, STREAM_TYPE_EAC3_SECONDARY ->
                Ac3Reader(language, roleFlags, MimeTypes.VIDEO_MP2T)
            STREAM_TYPE_DTS_HD_HRA, STREAM_TYPE_DTS_HD_SECONDARY -> dts(language, roleFlags)
            STREAM_TYPE_DTS_HD_MA -> if (hdmv) dts(language, roleFlags) else null
            STREAM_TYPE_PGS -> PgsReader(language, roleFlags)
            else -> null
        }
        val reader: TsPayloadReader = when {
            es != null -> PesReader(es)
            // В режиме hdmv не отдаём 0x80/0x86 штатной фабрике (там это видео DC2 и SCTE-35).
            hdmv && (streamType == STREAM_TYPE_LPCM || streamType == STREAM_TYPE_DTS_HD_MA) -> return null
            else -> delegate.createPayloadReader(streamType, esInfo) ?: return null
        }
        return if (languageForPid == null && labelForPid == null) reader
        else PidLabelingPayloadReader(reader, languageForPid, labelForPid)
    }

    private fun dts(language: String?, roleFlags: Int) =
        DtsReader(language, roleFlags, DTS_EXTSS_HEADER_SIZE_MAX, MimeTypes.VIDEO_MP2T)

    companion object {
        const val DEFAULT_FLAGS = DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
            DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS

        const val STREAM_TYPE_LPCM = 0x80
        const val STREAM_TYPE_DTS = 0x82
        const val STREAM_TYPE_TRUEHD = 0x83
        const val STREAM_TYPE_EAC3 = 0x84
        const val STREAM_TYPE_DTS_HD_HRA = 0x85
        const val STREAM_TYPE_DTS_HD_MA = 0x86
        const val STREAM_TYPE_PGS = 0x90
        const val STREAM_TYPE_EAC3_SECONDARY = 0xA1
        const val STREAM_TYPE_DTS_HD_SECONDARY = 0xA2

        /** = DtsReader.EXTSS_HEADER_SIZE_MAX (package-private в Media3). */
        private const val DTS_EXTSS_HEADER_SIZE_MAX = 4096
    }
}
