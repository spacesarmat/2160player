package tv.p2160.core.avi

import kotlin.math.min

/**
 * Распаковка DivX/XviD packed bitstream, как `mpeg4_unpack_bframes` в FFmpeg.
 *
 * В AVI один кадр часто содержит два VOP: опорный и приклеенный B-кадр. MediaCodec
 * (и аппаратный CedarX на Allwinner, и c2.android.mpeg4) ждёт один кадр в буфере:
 * первые кадры проходят, на B-кадре декодер падает. Здесь второй VOP откладывается
 * и выходит следующим сэмплом, а короткий N-VOP (заглушка DivX, не длиннее 19 байт) выбрасывается.
 */
internal class Mpeg4PackedUnpacker {
    var enabled: Boolean = false
        private set
    private var stored: ByteArray? = null

    fun setMime(mime: String?) {
        enabled = mime == MIME_MP4V
    }

    fun reset() {
        stored = null
    }

    fun consume(packet: ByteArray): ByteArray {
        if (!enabled || packet.isEmpty()) return packet
        val scan = scan(packet)
        var current = packet
        if (scan.secondVop >= 0) {
            stored = packet.copyOfRange(scan.secondVop, packet.size)
            current = packet.copyOfRange(0, scan.secondVop)
        }
        if (scan.vopCount == 1 && stored != null) {
            val out = stored!!
            stored = current
            if (stored!!.size <= MAX_NVOP_SIZE) stored = null
            return out
        }
        if (scan.vopCount >= 2) return current
        val marker = scan.packedMarker
        if (marker >= 0 && marker < current.size) {
            val copy = current.copyOf()
            copy[marker] = 0
            return copy
        }
        return current
    }

    companion object {
        const val MIME_MP4V = "video/mp4v-es"
        /** Как MAX_NVOP_SIZE в libavcodec/mpeg4videodefs.h. */
        const val MAX_NVOP_SIZE = 19
        private const val USER_DATA = 0xB2
        private const val VOP = 0xB6

        fun stripMarker(data: ByteArray): ByteArray {
            val at = scan(data).packedMarker
            if (at < 0 || at >= data.size) return data
            val copy = data.copyOf()
            copy[at] = 0
            return copy
        }

        /** I-VOP: два старших бита сразу после стартового кода 00 00 01 B6 равны 0. */
        fun isIVop(data: ByteArray): Boolean {
            var i = 0
            while (i + 4 < data.size) {
                if (isStart(data, i) && (data[i + 3].toInt() and 0xFF) == VOP) {
                    return (data[i + 4].toInt() and 0xC0) == 0
                }
                i++
            }
            return false
        }

        fun vopCount(data: ByteArray): Int = scan(data).vopCount

        private data class Scan(val packedMarker: Int, val vopCount: Int, val secondVop: Int)

        private fun scan(packet: ByteArray): Scan {
            var marker = -1
            var vops = 0
            var second = -1
            var i = 0
            val end = packet.size
            while (i + 3 < end) {
                if (!isStart(packet, i)) {
                    i++
                    continue
                }
                val code = packet[i + 3].toInt() and 0xFF
                if (code == USER_DATA && marker < 0) {
                    val from = i + 4
                    val limit = min(end, from + 255)
                    var j = from
                    while (j + 1 < limit) {
                        if (packet[j] == 'p'.code.toByte() && packet[j + 1] == 0.toByte()) {
                            marker = j
                            break
                        }
                        j++
                    }
                } else if (code == VOP) {
                    vops++
                    if (vops == 2 && second < 0) second = i
                }
                i += 4
            }
            return Scan(marker, vops, second)
        }

        private fun isStart(data: ByteArray, i: Int): Boolean =
            data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()
    }
}
