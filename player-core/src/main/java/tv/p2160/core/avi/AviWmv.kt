package tv.p2160.core.avi

import androidx.media3.common.MimeTypes
import kotlin.math.abs

/**
 * WMV/VC-1 в AVI. Media3 такой fourcc выбрасывает, поэтому в заголовке он
 * подменяется на MJPG (чтобы дорожка не пропала), а настоящий MIME и хвост
 * BITMAPINFOHEADER (его ждёт декодер WMV3/VC-1) запоминаются здесь.
 */
internal data class WmvVideo(
    val fourcc: String,
    val mime: String,
    val width: Int,
    val height: Int,
    val initializationData: ByteArray,
)

internal class AviHeaderState {
    val wmv = ArrayDeque<WmvVideo>()
    private val seenAt = HashSet<Long>()

    fun reset() {
        wmv.clear()
        seenAt.clear()
    }

    fun alreadySeen(fileOffset: Long): Boolean = !seenAt.add(fileOffset)
}

internal object AviWmv {
    const val MIME_WMV3 = "video/x-ms-wmv"
    const val MIME_WMV1 = "video/wmv1"
    const val MIME_WMV2 = "video/wmv2"
    private val PLACEHOLDER = "MJPG".encodeToByteArray()

    /** MIME для fourcc или null, если это не WMV/VC-1. */
    fun mimeFor(fourcc: String): String? = when (fourcc) {
        "WVC1", "wvc1" -> MimeTypes.VIDEO_VC1
        "WMV3", "wmv3" -> MIME_WMV3
        "WMV1", "wmv1" -> MIME_WMV1
        "WMV2", "wmv2" -> MIME_WMV2
        else -> null
    }

    /**
     * Ищет `strf` с WMV в окне заголовка. Подменяет biCompression на MJPG.
     * Повтор того же смещения в файле не добавляет дорожку второй раз.
     */
    fun claim(buffer: ByteArray, offset: Int, length: Int, filePosition: Long, state: AviHeaderState) {
        if (length < 32 || filePosition >= AviFourcc.HEADER_LIMIT) return
        val window = minOf(length, (AviFourcc.HEADER_LIMIT - filePosition).toInt())
        val start = offset
        val end = offset + window
        var i = start
        while (i + 8 + 40 <= end) {
            if (!isStrf(buffer, i)) {
                i++
                continue
            }
            val size = le32(buffer, i + 4)
            val body = i + 8
            if (size < 40 || body + 40 > end) {
                i++
                continue
            }
            val available = minOf(size, end - body)
            val fourcc = String(buffer, body + 16, 4, Charsets.ISO_8859_1)
            val mime = mimeFor(fourcc)
            if (mime == null) {
                i++
                continue
            }
            val at = filePosition + (i - offset)
            if (!state.alreadySeen(at)) {
                val extraEnd = body + available
                val extra = if (extraEnd > body + 40) buffer.copyOfRange(body + 40, extraEnd) else ByteArray(0)
                state.wmv += WmvVideo(
                    fourcc = fourcc.uppercase(),
                    mime = mime,
                    width = abs(le32(buffer, body + 4)),
                    height = abs(le32(buffer, body + 8)),
                    initializationData = extra,
                )
            }
            buffer[body + 16] = PLACEHOLDER[0]
            buffer[body + 17] = PLACEHOLDER[1]
            buffer[body + 18] = PLACEHOLDER[2]
            buffer[body + 19] = PLACEHOLDER[3]
            i = body + available
        }
    }

    private fun isStrf(buffer: ByteArray, i: Int): Boolean =
        buffer[i] == 's'.code.toByte() && buffer[i + 1] == 't'.code.toByte() &&
            buffer[i + 2] == 'r'.code.toByte() && buffer[i + 3] == 'f'.code.toByte()

    private fun le32(buffer: ByteArray, i: Int): Int =
        (buffer[i].toInt() and 0xFF) or
            ((buffer[i + 1].toInt() and 0xFF) shl 8) or
            ((buffer[i + 2].toInt() and 0xFF) shl 16) or
            ((buffer[i + 3].toInt() and 0xFF) shl 24)
}
