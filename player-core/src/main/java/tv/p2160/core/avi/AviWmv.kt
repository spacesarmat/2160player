package tv.p2160.core.avi

import androidx.media3.common.MimeTypes
import kotlin.math.abs

/**
 * WMV/VC-1 в AVI. Media3 такой fourcc выбрасывает, поэтому в заголовке он
 * подменяется на MJPG (чтобы дорожка не пропала), а настоящий MIME и хвост
 * BITMAPINFOHEADER (его ждёт декодер WMV3/VC-1) запоминаются здесь.
 * Патчинг заголовка - в [AviHeader.tryFixHdrl]; очередь дорожек - в [AviProbe].
 */
internal data class WmvVideo(
    val fourcc: String,
    val mime: String,
    val width: Int,
    val height: Int,
    val initializationData: ByteArray,
)

internal object AviWmv {
    const val MIME_WMV3 = "video/x-ms-wmv"
    const val MIME_WMV1 = "video/wmv1"
    const val MIME_WMV2 = "video/wmv2"
    /** Заглушка вместо WMV-fourcc: Media3 знает MJPG и оставляет видеодорожку. */
    const val PLACEHOLDER_FOURCC = "MJPG"

    /** MIME для fourcc или null, если это не WMV/VC-1. */
    fun mimeFor(fourcc: String): String? = when (fourcc) {
        "WVC1", "wvc1" -> MimeTypes.VIDEO_VC1
        "WMV3", "wmv3" -> MIME_WMV3
        "WMV1", "wmv1" -> MIME_WMV1
        "WMV2", "wmv2" -> MIME_WMV2
        else -> null
    }

    /** Собирает факт WMV-видео из strf: размеры (по модулю) и хвост BITMAPINFOHEADER. */
    fun fromStrf(fourcc: String, width: Int, height: Int, bodySize: Int, data: ByteArray, body: Int): WmvVideo {
        val tail = if (bodySize > 40) {
            data.copyOfRange(body + 40, minOf(body + bodySize, data.size))
        } else {
            ByteArray(0)
        }
        return WmvVideo(
            fourcc = fourcc.uppercase(),
            mime = mimeFor(fourcc) ?: MIME_WMV3,
            width = abs(width),
            height = abs(height),
            initializationData = tail,
        )
    }
}
