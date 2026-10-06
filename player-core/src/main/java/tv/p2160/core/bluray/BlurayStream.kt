package tv.p2160.core.bluray

import java.io.IOException

/** Повреждённый или неподдерживаемый файл структуры Blu-ray. */
class BlurayFormatException(message: String) : IOException(message)

enum class StreamKind { VIDEO, AUDIO, SUBTITLE, INTERACTIVE, TEXT, UNKNOWN }

/**
 * Элементарный поток из STN-таблицы плейлиста или ProgramInfo клипа.
 * [format]/[rate] — сырые коды: для видео формат кадра и частота, для аудио раскладка и частота.
 */
data class BlurayStream(
    val pid: Int,
    val codingType: Int,
    val language: String? = null,
    val format: Int = 0,
    val rate: Int = 0,
) {
    val kind: StreamKind get() = StreamCoding.kind(codingType)

    /** Название кодека: "Dolby TrueHD", "HEVC", "PGS"… */
    val codec: String get() = StreamCoding.name(codingType)

    /** Человекочитаемое описание: "Dolby TrueHD, multi-channel, 48 kHz". */
    val description: String
        get() = listOfNotNull(codec, StreamCoding.formatName(kind, format), StreamCoding.rateName(kind, rate))
            .joinToString(", ")
}

/** Коды stream_coding_type и атрибутов из спецификации BD-ROM. */
object StreamCoding {
    fun kind(type: Int): StreamKind = when (type) {
        0x01, 0x02, 0x1B, 0x20, 0x24, 0xEA -> StreamKind.VIDEO
        0x03, 0x04, in 0x80..0x86, 0xA1, 0xA2 -> StreamKind.AUDIO
        0x90 -> StreamKind.SUBTITLE
        0x91 -> StreamKind.INTERACTIVE
        0x92 -> StreamKind.TEXT
        else -> StreamKind.UNKNOWN
    }

    fun name(type: Int): String = when (type) {
        0x01 -> "MPEG-1 Video"
        0x02 -> "MPEG-2 Video"
        0x1B -> "H.264/AVC"
        0x20 -> "H.264/MVC"
        0x24 -> "HEVC"
        0xEA -> "VC-1"
        0x03 -> "MPEG-1 Audio"
        0x04 -> "MPEG-2 Audio"
        0x80 -> "LPCM"
        0x81 -> "Dolby Digital"
        0x82 -> "DTS"
        0x83 -> "Dolby TrueHD"
        0x84, 0xA1 -> "Dolby Digital Plus"
        0x85 -> "DTS-HD HRA"
        0x86 -> "DTS-HD MA"
        0xA2 -> "DTS Express"
        0x90 -> "PGS"
        0x91 -> "IGS"
        0x92 -> "Text subtitle"
        else -> "0x%02X".format(type)
    }

    fun formatName(kind: StreamKind, format: Int): String? = when (kind) {
        StreamKind.VIDEO -> when (format) {
            1 -> "480i"; 2 -> "576i"; 3 -> "480p"; 4 -> "1080i"; 5 -> "720p"; 6 -> "1080p"; 7 -> "576p"; 8 -> "2160p"
            else -> null
        }
        StreamKind.AUDIO -> when (format) {
            1 -> "mono"; 3 -> "stereo"; 6 -> "multi-channel"; 12 -> "stereo + multi-channel"
            else -> null
        }
        else -> null
    }

    fun rateName(kind: StreamKind, rate: Int): String? = when (kind) {
        StreamKind.VIDEO -> when (rate) {
            1 -> "23.976 fps"; 2 -> "24 fps"; 3 -> "25 fps"; 4 -> "29.97 fps"; 6 -> "50 fps"; 7 -> "59.94 fps"
            else -> null
        }
        StreamKind.AUDIO -> when (rate) {
            1 -> "48 kHz"; 4 -> "96 kHz"; 5 -> "192 kHz"; 12 -> "192/48 kHz"; 14 -> "96/48 kHz"
            else -> null
        }
        else -> null
    }

    /**
     * Разбирает атрибуты потока (stream_attributes в MPLS, StreamCodingInfo в CLPI):
     * [r] стоит на байте stream_coding_type, читать можно до [end].
     */
    internal fun parseAttributes(r: BeReader, pid: Int, end: Int): BlurayStream {
        val type = r.u8()
        var format = 0
        var rate = 0
        var lang: String? = null
        when (kind(type)) {
            StreamKind.VIDEO -> {
                val b = r.u8(); format = b ushr 4; rate = b and 0xF
            }
            StreamKind.AUDIO -> {
                val b = r.u8(); format = b ushr 4; rate = b and 0xF
                if (r.pos + 3 <= end) lang = language(r.ascii(3))
            }
            StreamKind.SUBTITLE, StreamKind.INTERACTIVE -> if (r.pos + 3 <= end) lang = language(r.ascii(3))
            StreamKind.TEXT -> {
                r.u8() // character_code
                if (r.pos + 3 <= end) lang = language(r.ascii(3))
            }
            StreamKind.UNKNOWN -> {}
        }
        return BlurayStream(pid, type, lang, format, rate)
    }

    private fun language(code: String): String? =
        code.trim().lowercase().takeIf { it.length == 3 && it.all { c -> c in 'a'..'z' } }
}

/** Чтение big-endian с проверкой границ: выход за [limit] → [BlurayFormatException]. */
internal class BeReader(val data: ByteArray, var pos: Int = 0, val limit: Int = data.size) {
    private fun need(n: Int) {
        if (n < 0 || pos < 0 || pos + n > limit) throw BlurayFormatException("Unexpected end of data at $pos (+$n)")
    }

    fun u8(): Int { need(1); return data[pos++].toInt() and 0xFF }
    fun u16(): Int { need(2); return (u8() shl 8) or u8() }
    fun u32(): Long { need(4); return (u16().toLong() shl 16) or u16().toLong() }
    fun skip(n: Int) { need(n); pos += n }
    fun ascii(n: Int): String { need(n); return String(data, pos, n, Charsets.ISO_8859_1).also { pos += n } }

    fun seek(p: Int) {
        if (p < 0 || p > limit) throw BlurayFormatException("Bad offset $p")
        pos = p
    }
}
