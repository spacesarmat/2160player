package tv.p2160.core.bluray

/** STC-последовательность клипа: время показа в тиках 45 кГц. */
data class StcSequence(val pcrPid: Int, val spnStart: Long, val presentationStart45k: Long, val presentationEnd45k: Long)

/**
 * Карта точек входа (EP_map) одного потока: [pts45k] по возрастанию и номера source packet [spn].
 * Байтовое смещение в m2ts = spn * 192.
 */
class EpMap(val pid: Int, val pts45k: LongArray, val spn: LongArray) {
    /** SPN последней точки входа не позже [time45k] (или первой, если время раньше всех). */
    fun spnAt(time45k: Long): Long {
        if (spn.isEmpty()) return 0
        var i = pts45k.binarySearch(time45k)
        if (i < 0) i = -i - 2
        return spn[i.coerceIn(0, spn.size - 1)]
    }

    fun byteOffsetAt(time45k: Long): Long = spnAt(time45k) * ClipInfo.SOURCE_PACKET_SIZE
}

/** Содержимое BDMV/CLIPINF/xxxxx.clpi. */
data class ClipInfo(
    val version: String,
    val clipStreamType: Int,
    val applicationType: Int,
    val tsRecordingRate: Long,
    val sourcePacketCount: Long,
    val sequences: List<StcSequence>,
    val streams: List<BlurayStream>,
    val epMaps: List<EpMap>,
) {
    /** EP_map первого видеопотока — для перемотки по времени. */
    val videoEpMap: EpMap?
        get() = epMaps.firstOrNull { m -> streams.any { it.pid == m.pid && it.kind == StreamKind.VIDEO } }
            ?: epMaps.firstOrNull()

    companion object {
        const val SOURCE_PACKET_SIZE = 192L
    }
}

/** Разбор CLPI ("HDMV" 0100/0200/0300): ClipInfo, SequenceInfo, ProgramInfo, CPI/EP_map. */
object ClpiParser {

    fun parse(data: ByteArray): ClipInfo {
        val r = BeReader(data)
        if (r.ascii(4) != "HDMV") throw BlurayFormatException("Not a CLPI file")
        val version = r.ascii(4)
        val sequenceStart = r.u32().toInt()
        val programStart = r.u32().toInt()
        val cpiStart = r.u32().toInt()

        r.seek(40) // ClipInfo
        r.u32()
        r.skip(2)
        val streamType = r.u8()
        val appType = r.u8()
        r.skip(4)
        val rate = r.u32()
        val packets = r.u32()

        val sequences = runCatching { parseSequences(data, sequenceStart) }.getOrDefault(emptyList())
        val streams = parsePrograms(data, programStart)
        val epMaps = if (cpiStart > 0) runCatching { parseCpi(data, cpiStart) }.getOrDefault(emptyList()) else emptyList()
        return ClipInfo(version, streamType, appType, rate, packets, sequences, streams, epMaps)
    }

    private fun parseSequences(data: ByteArray, start: Int): List<StcSequence> {
        val r = BeReader(data, start)
        r.u32()
        r.skip(1)
        val atcCount = r.u8()
        val result = ArrayList<StcSequence>()
        repeat(atcCount) {
            r.u32() // SPN_ATC_start
            val stcCount = r.u8()
            r.skip(1) // offset_STC_id
            repeat(stcCount) {
                val pcrPid = r.u16()
                val spn = r.u32()
                result += StcSequence(pcrPid, spn, r.u32(), r.u32())
            }
        }
        return result
    }

    private fun parsePrograms(data: ByteArray, start: Int): List<BlurayStream> {
        val r = BeReader(data, start)
        r.u32()
        r.skip(1)
        val programCount = r.u8()
        val result = ArrayList<BlurayStream>()
        repeat(programCount) {
            r.u32() // SPN_program_sequence_start
            r.u16() // program_map_PID
            val streamCount = r.u8()
            r.skip(1) // num_groups
            repeat(streamCount) {
                val pid = r.u16()
                val len = r.u8()
                val end = r.pos + len
                if (end > data.size) throw BlurayFormatException("StreamCodingInfo out of bounds")
                result += StreamCoding.parseAttributes(BeReader(data, r.pos, end), pid, end)
                r.seek(end)
            }
        }
        return result
    }

    private fun parseCpi(data: ByteArray, start: Int): List<EpMap> {
        val r = BeReader(data, start)
        val len = r.u32()
        if (len == 0L) return emptyList()
        val type = r.u16() and 0xF
        if (type != 1) return emptyList()
        val epMapStart = r.pos
        r.skip(1)
        val pidCount = r.u8()
        val result = ArrayList<EpMap>()
        repeat(pidCount) {
            val pid = r.u16()
            // 48 бит: reserved 10, EP_stream_type 4, number_of_EP_coarse 16, number_of_EP_fine 18.
            val packed = (r.u16().toLong() shl 32) or r.u32()
            val coarseCount = ((packed ushr 18) and 0xFFFF).toInt()
            val fineCount = (packed and 0x3FFFF).toInt()
            val mapStart = epMapStart + r.u32().toInt()
            result += parseEpStream(data, pid, mapStart, coarseCount, fineCount)
        }
        return result
    }

    private fun parseEpStream(data: ByteArray, pid: Int, start: Int, coarseCount: Int, fineCount: Int): EpMap {
        val r = BeReader(data, start)
        val fineStart = start + r.u32().toInt()
        val coarseFine = IntArray(coarseCount)
        val coarsePts = LongArray(coarseCount)
        val coarseSpn = LongArray(coarseCount)
        for (i in 0 until coarseCount) {
            val v = r.u32()
            coarseFine[i] = (v ushr 14).toInt()
            coarsePts[i] = v and 0x3FFF
            coarseSpn[i] = r.u32()
        }
        val f = BeReader(data, fineStart)
        val pts = LongArray(fineCount)
        val spn = LongArray(fineCount)
        var c = 0
        for (i in 0 until fineCount) {
            while (c + 1 < coarseCount && coarseFine[c + 1] <= i) c++
            val v = f.u32()
            val finePts = (v ushr 17) and 0x7FF
            val fineSpn = v and 0x1FFFF
            if (coarseCount == 0) throw BlurayFormatException("EP_map without coarse entries")
            // Как в libbluray: старшие биты из coarse, младшие из fine (время в 45 кГц).
            pts[i] = ((coarsePts[c] and 0x01.inv().toLong()) shl 18) + (finePts shl 8)
            spn[i] = (coarseSpn[c] and 0x1FFFF.inv().toLong()) + fineSpn
        }
        return EpMap(pid, pts, spn)
    }
}
