package tv.p2160.core.avi

import kotlin.math.max

/**
 * VOL MPEG-4 Part 2: sprite_enable 2 или 3 - это GMC.
 * Аппаратный декодер MPEG-4 при этом часто есть, но такой поток не берёт.
 */
internal data class VolScan(val sawVol: Boolean, val gmc: Boolean)

internal object Mpeg4Vol {
    fun scan(data: ByteArray): VolScan {
        var saw = false
        var i = 0
        while (i + 5 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                val code = data[i + 3].toInt() and 0xFF
                if (code in 0x20..0x2F) {
                    saw = true
                    if (spriteEnable(data, i + 4) >= 2) return VolScan(sawVol = true, gmc = true)
                }
            }
            i++
        }
        return VolScan(sawVol = saw, gmc = false)
    }

    fun usesGmc(data: ByteArray): Boolean = scan(data).gmc

    private fun spriteEnable(data: ByteArray, offset: Int): Int {
        val bits = Bits(data, offset * 8)
        return try {
            bits.bit() // random_accessible_vol
            bits.bits(8) // video_object_type_indication
            val identified = bits.bit() == 1
            var verid = 1
            if (identified) {
                verid = bits.bits(4)
                bits.bits(3)
            }
            val aspect = bits.bits(4)
            if (aspect == 0xF) {
                bits.bits(8)
                bits.bits(8)
            }
            if (bits.bit() == 1) {
                bits.bits(2)
                bits.bit()
                if (bits.bit() == 1) {
                    bits.bits(15); bits.bit()
                    bits.bits(15); bits.bit()
                    bits.bits(15); bits.bit()
                    bits.bits(3)
                    bits.bits(11); bits.bit()
                    bits.bits(15); bits.bit()
                }
            }
            val shape = bits.bits(2)
            bits.bit()
            val resolution = bits.bits(16)
            bits.bit()
            if (bits.bit() == 1) {
                val width = if (resolution <= 1) 1 else 32 - Integer.numberOfLeadingZeros(resolution - 1)
                bits.bits(max(1, width))
            }
            if (shape != 2) {
                bits.bit()
                bits.bits(13)
                bits.bit()
                bits.bits(13)
                bits.bit()
            }
            bits.bit() // interlaced
            bits.bit() // obmc_disable
            if (verid == 1) bits.bit() else bits.bits(2)
        } catch (_: Throwable) {
            -1
        }
    }

    private class Bits(private val data: ByteArray, private var index: Int) {
        fun bit(): Int {
            if (index / 8 >= data.size) throw IndexOutOfBoundsException()
            val value = (data[index / 8].toInt() ushr (7 - index % 8)) and 1
            index++
            return value
        }

        fun bits(count: Int): Int {
            var value = 0
            repeat(count) { value = (value shl 1) or bit() }
            return value
        }
    }
}
